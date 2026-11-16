package com.jarvis.app.tts

import android.util.Log
import com.jarvis.core.voice.StreamingTtsBackend
import com.jarvis.core.voice.TtsAudioResult
import com.jarvis.core.voice.TtsErrorKind
import com.jarvis.core.voice.TtsVoiceConfig
import com.jarvis.core.voice.VoiceProviderAvailability
import com.jarvis.core.voice.VoiceProviderKind
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject

/**
 * V2-09: OpenAI-compatible `POST /v1/audio/speech`.
 * Requests PCM16 LE @ 24 kHz (OpenAI tts-1 pcm), converts once to float for TtsPlayer.
 * Secrets never logged.
 */
class OpenAiSpeechTtsBackend(
    private val baseUrlProvider: () -> String,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String = { DEFAULT_MODEL },
    private val voiceProvider: () -> String = { DEFAULT_VOICE },
    private val online: () -> Boolean = { true },
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 30_000,
) : StreamingTtsBackend {
    override val id: String = "tts.cloud.openai_speech"
    override val kind: VoiceProviderKind = VoiceProviderKind.CLOUD
    override val model: String get() = modelProvider().ifBlank { DEFAULT_MODEL }
    override val defaultVoice: String get() = voiceProvider().ifBlank { DEFAULT_VOICE }
    override val supportsStreamingAudio: Boolean = false

    override fun availability(): VoiceProviderAvailability {
        if (!online()) return VoiceProviderAvailability.UNAVAILABLE
        if (apiKeyProvider().isBlank() || baseUrlProvider().isBlank()) {
            return VoiceProviderAvailability.UNAVAILABLE
        }
        return VoiceProviderAvailability.AVAILABLE
    }

    override fun synthesize(
        text: String,
        voice: TtsVoiceConfig,
        cancelFlag: () -> Boolean,
    ): TtsAudioResult {
        if (cancelFlag()) return err(TtsErrorKind.CANCELLED, voice)
        val key = apiKeyProvider()
        val base = baseUrlProvider().trim().trimEnd('/')
        if (key.isBlank() || base.isBlank()) return err(TtsErrorKind.AUTH, voice)

        val modelId = voice.model.ifBlank { model }
        val voiceId = voice.voice.ifBlank { defaultVoice }.let {
            if (it == "default" || it == "dmitri") defaultVoice else it
        }
        val body = JSONObject()
            .put("model", modelId)
            .put("input", text.take(4096))
            .put("voice", voiceId)
            .put("response_format", "pcm")
            .toString()

        val url = "$base/audio/speech"
        val t0 = System.currentTimeMillis()
        return try {
            if (cancelFlag()) return err(TtsErrorKind.CANCELLED, voice)
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $key")
            }
            try {
                conn.outputStream.use { os ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                if (cancelFlag()) return err(TtsErrorKind.CANCELLED, voice)
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val bytes = stream?.use { readAll(it) } ?: ByteArray(0)
                val setupMs = (System.currentTimeMillis() - t0).coerceAtLeast(0L)
                if (code !in 200..299) {
                    Log.w(TAG, "speech HTTP $code (key redacted)")
                    return err(mapHttp(code), voice, setupMs)
                }
                if (bytes.isEmpty()) return err(TtsErrorKind.DECODING, voice, setupMs)
                if (cancelFlag()) return err(TtsErrorKind.CANCELLED, voice, setupMs)
                val samples = pcm16leToFloat(bytes)
                val synthMs = (System.currentTimeMillis() - t0).coerceAtLeast(0L)
                TtsAudioResult(
                    samples = samples,
                    sampleRateHz = OPENAI_PCM_RATE,
                    provider = id,
                    model = modelId,
                    voice = voiceId,
                    setupMs = setupMs,
                    synthesisMs = synthMs,
                    streamingSupported = false,
                )
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "speech transport: ${t.javaClass.simpleName}")
            err(TtsErrorKind.NETWORK, voice)
        }
    }

    private fun mapHttp(code: Int): TtsErrorKind = when (code) {
        401, 403 -> TtsErrorKind.AUTH
        429 -> TtsErrorKind.RATE_LIMIT
        404, 501 -> TtsErrorKind.UNSUPPORTED_VOICE
        in 500..599 -> TtsErrorKind.PROVIDER_UNAVAILABLE
        else -> TtsErrorKind.NETWORK
    }

    private fun err(
        kind: TtsErrorKind,
        voice: TtsVoiceConfig,
        setupMs: Long = 0L,
    ) = TtsAudioResult(
        samples = FloatArray(0),
        sampleRateHz = OPENAI_PCM_RATE,
        provider = id,
        model = model,
        voice = voice.voice.ifBlank { defaultVoice },
        setupMs = setupMs,
        errorKind = kind,
        streamingSupported = false,
    )

    private fun readAll(input: java.io.InputStream): ByteArray {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8_192)
        while (true) {
            val n = input.read(tmp)
            if (n <= 0) break
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    private fun pcm16leToFloat(pcm: ByteArray): FloatArray {
        val n = pcm.size / 2
        val out = FloatArray(n)
        var i = 0
        var o = 0
        while (o < n) {
            val lo = pcm[i].toInt() and 0xff
            val hi = pcm[i + 1].toInt()
            val v = ((hi shl 8) or lo).toShort()
            out[o++] = v / 32768f
            i += 2
        }
        return out
    }

    companion object {
        private const val TAG = "Jarvis.Tts"
        const val DEFAULT_MODEL = "tts-1"
        const val DEFAULT_VOICE = "alloy"
        const val OPENAI_PCM_RATE = 24_000
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
