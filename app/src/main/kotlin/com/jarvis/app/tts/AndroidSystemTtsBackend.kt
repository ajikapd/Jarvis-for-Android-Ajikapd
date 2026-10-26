package com.jarvis.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.core.voice.StreamingTtsBackend
import com.jarvis.core.voice.TtsAudioResult
import com.jarvis.core.voice.TtsErrorKind
import com.jarvis.core.voice.TtsVoiceConfig
import com.jarvis.core.voice.VoiceProviderAvailability
import com.jarvis.core.voice.VoiceProviderKind
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2-09 cloud/system TTS via Android [TextToSpeech] → WAV file → float PCM.
 * No API keys. One conversion boundary (WAV→float). Playback stays in TtsPlayer.
 */
class AndroidSystemTtsBackend(
    private val appContext: Context,
    private val online: () -> Boolean = { true },
) : StreamingTtsBackend {
    override val id: String = "tts.cloud.android_tts"
    override val kind: VoiceProviderKind = VoiceProviderKind.CLOUD
    override val model: String = "android.speech.tts.TextToSpeech"
    override val defaultVoice: String = "ru-RU"
    override val supportsStreamingAudio: Boolean = false

    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    private val initLock = Any()

    override fun availability(): VoiceProviderAvailability {
        if (!online()) return VoiceProviderAvailability.UNAVAILABLE
        ensureInit()
        return if (ready) VoiceProviderAvailability.AVAILABLE
        else VoiceProviderAvailability.UNAVAILABLE
    }

    private fun ensureInit() {
        if (ready && engine != null) return
        synchronized(initLock) {
            if (ready && engine != null) return
            val latch = CountDownLatch(1)
            val tts = TextToSpeech(appContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                latch.countDown()
            }
            engine = tts
            latch.await(3, TimeUnit.SECONDS)
            if (ready) {
                tts.language = Locale("ru", "RU")
            }
        }
    }

    override fun synthesize(
        text: String,
        voice: TtsVoiceConfig,
        cancelFlag: () -> Boolean,
    ): TtsAudioResult {
        if (cancelFlag()) return err(TtsErrorKind.CANCELLED, voice)
        ensureInit()
        val tts = engine
        if (!ready || tts == null) return err(TtsErrorKind.PROVIDER_UNAVAILABLE, voice)

        val lang = runCatching {
            val parts = voice.language.replace('_', '-').split('-')
            if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale("ru", "RU")
        }.getOrDefault(Locale("ru", "RU"))
        val avail = tts.isLanguageAvailable(lang)
        if (avail < TextToSpeech.LANG_AVAILABLE) {
            return err(TtsErrorKind.UNSUPPORTED_VOICE, voice)
        }
        tts.language = lang
        tts.setSpeechRate(voice.speakingRate.coerceIn(0.5f, 2.0f))
        tts.setPitch(voice.pitch.coerceIn(0.5f, 2.0f))

        val outFile = File(appContext.cacheDir, "jarvis_tts_${UUID.randomUUID()}.wav")
        val done = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val t0 = System.currentTimeMillis()
        val utteranceId = UUID.randomUUID().toString()

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                done.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                failed.set(true)
                done.countDown()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                failed.set(true)
                done.countDown()
            }
        })

        val params = android.os.Bundle()
        val result = tts.synthesizeToFile(text, params, outFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            outFile.delete()
            return err(TtsErrorKind.PROVIDER_UNAVAILABLE, voice)
        }
        val ok = done.await(25, TimeUnit.SECONDS)
        if (cancelFlag()) {
            runCatching { tts.stop() }
            outFile.delete()
            return err(TtsErrorKind.CANCELLED, voice)
        }
        if (!ok || failed.get() || !outFile.isFile) {
            outFile.delete()
            return err(TtsErrorKind.TIMEOUT, voice)
        }
        return try {
            val decoded = readWavPcmFloat(outFile)
            val ms = (System.currentTimeMillis() - t0).coerceAtLeast(0L)
            TtsAudioResult(
                samples = decoded.samples,
                sampleRateHz = decoded.sampleRateHz,
                provider = id,
                model = model,
                voice = lang.toLanguageTag(),
                setupMs = 0L,
                synthesisMs = ms,
                streamingSupported = false,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "wav decode: ${t.javaClass.simpleName}")
            err(TtsErrorKind.DECODING, voice)
        } finally {
            outFile.delete()
        }
    }

    private fun err(kind: TtsErrorKind, voice: TtsVoiceConfig) = TtsAudioResult(
        samples = FloatArray(0),
        sampleRateHz = 22_050,
        provider = id,
        model = model,
        voice = voice.voice,
        errorKind = kind,
    )

    private data class Decoded(val samples: FloatArray, val sampleRateHz: Int)

    private fun readWavPcmFloat(file: File): Decoded {
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(4).also { raf.readFully(it) }
            require(String(riff) == "RIFF") { "not riff" }
            raf.skipBytes(4)
            val wave = ByteArray(4).also { raf.readFully(it) }
            require(String(wave) == "WAVE") { "not wave" }
            var sampleRate = 22050
            var bits = 16
            var channels = 1
            var dataSize = 0
            var dataOffset = 0L
            while (raf.filePointer < raf.length()) {
                val id = ByteArray(4).also { raf.readFully(it) }
                val size = Integer.reverseBytes(raf.readInt())
                val name = String(id)
                when (name) {
                    "fmt " -> {
                        val audioFormat = java.lang.Short.reverseBytes(raf.readShort()).toInt()
                        channels = java.lang.Short.reverseBytes(raf.readShort()).toInt()
                        sampleRate = Integer.reverseBytes(raf.readInt())
                        raf.skipBytes(6)
                        bits = java.lang.Short.reverseBytes(raf.readShort()).toInt()
                        if (size > 16) raf.skipBytes(size - 16)
                        require(audioFormat == 1) { "not pcm" }
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = size
                        raf.skipBytes(size)
                    }
                    else -> raf.skipBytes(size)
                }
            }
            require(dataSize > 0) { "no data" }
            raf.seek(dataOffset)
            val pcm = ByteArray(dataSize)
            raf.readFully(pcm)
            val samples = when (bits) {
                16 -> {
                    val n = pcm.size / 2 / channels
                    val out = FloatArray(n)
                    var i = 0
                    var o = 0
                    while (o < n) {
                        val lo = pcm[i].toInt() and 0xff
                        val hi = pcm[i + 1].toInt()
                        val v = ((hi shl 8) or lo).toShort()
                        out[o++] = v / 32768f
                        i += 2 * channels
                    }
                    out
                }
                else -> throw IllegalArgumentException("bits=$bits")
            }
            return Decoded(samples, sampleRate)
        }
    }

    companion object {
        private const val TAG = "Jarvis.Tts"
    }
}
