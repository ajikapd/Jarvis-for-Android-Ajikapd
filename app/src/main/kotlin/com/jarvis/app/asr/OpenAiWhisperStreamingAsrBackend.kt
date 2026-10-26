package com.jarvis.app.asr

import android.util.Log
import com.jarvis.core.voice.AsrAudioFormat
import com.jarvis.core.voice.AsrCancelReason
import com.jarvis.core.voice.AsrConfidence
import com.jarvis.core.voice.AsrErrorKind
import com.jarvis.core.voice.AsrResult
import com.jarvis.core.voice.AsrSessionConfig
import com.jarvis.core.voice.AsrSessionListener
import com.jarvis.core.voice.AsrSessionPhase
import com.jarvis.core.voice.BoundedPcmRing
import com.jarvis.core.voice.StreamingAsrBackend
import com.jarvis.core.voice.StreamingAsrSession
import com.jarvis.core.voice.VoiceProviderAvailability
import com.jarvis.core.voice.VoiceProviderKind
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * V2-08: OpenAI-compatible `/v1/audio/transcriptions` with rolling interim windows.
 *
 * Audio chunks are uploaded **while the user is still speaking** (not after full recording).
 * Partials come from interim window requests; FINAL from the full utterance on finalize.
 *
 * Credentials via [apiKeyProvider] — never logged.
 * Works with OpenAI Whisper and compatible gateways that expose the transcriptions endpoint.
 * NaraRouter chat-only gateways leave this UNAVAILABLE until a Whisper-capable base URL is set.
 */
class OpenAiWhisperStreamingAsrBackend(
    private val baseUrlProvider: () -> String,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String = { DEFAULT_MODEL },
    private val online: () -> Boolean = { true },
    private val interimIntervalMs: Long = 900L,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 20_000,
) : StreamingAsrBackend {
    override val id: String = "asr.cloud.openai_whisper"
    override val kind: VoiceProviderKind = VoiceProviderKind.CLOUD
    override val model: String get() = modelProvider().ifBlank { DEFAULT_MODEL }
    override val supportsStreamingPartials: Boolean = true

    override fun availability(): VoiceProviderAvailability {
        if (!online()) return VoiceProviderAvailability.UNAVAILABLE
        if (apiKeyProvider().isBlank()) return VoiceProviderAvailability.UNAVAILABLE
        if (baseUrlProvider().isBlank()) return VoiceProviderAvailability.UNAVAILABLE
        return VoiceProviderAvailability.AVAILABLE
    }

    override fun openSession(config: AsrSessionConfig, listener: AsrSessionListener): StreamingAsrSession =
        WhisperStreamingSession(
            config = config,
            listener = listener,
            backendId = id,
            modelId = model,
            baseUrl = baseUrlProvider().trim().trimEnd('/'),
            apiKey = apiKeyProvider(),
            interimIntervalMs = interimIntervalMs,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )

    companion object {
        const val DEFAULT_MODEL = "whisper-1"
        /** OpenAI default; override via settings for other Whisper hosts. */
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}

private class WhisperStreamingSession(
    private val config: AsrSessionConfig,
    private val listener: AsrSessionListener,
    private val backendId: String,
    private val modelId: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val interimIntervalMs: Long,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) : StreamingAsrSession {
    override val sessionId: String = config.sessionId
    override val providerId: String = backendId

    private val active = AtomicBoolean(true)
    private val ring = BoundedPcmRing(config.maxBufferedMs, config.sampleRateHz)
    private val executor = Executors.newSingleThreadExecutor()
    private val lastInterimAt = AtomicLong(0L)
    private val inFlight = AtomicBoolean(false)
    private var lastPartial = ""
    private var pending: Future<*>? = null

    override fun feedFloatPcm(samples: FloatArray, sampleRateHz: Int) {
        if (!active.get()) return
        ring.appendFloat(samples, sampleRateHz)
        maybeInterim()
    }

    override fun feedPcm16le(bytes: ByteArray, sampleRateHz: Int) {
        val samples = FloatArray(bytes.size / 2)
        var i = 0
        var s = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xff
            val hi = bytes[i + 1].toInt()
            val v = ((hi shl 8) or lo).toShort()
            samples[s++] = v / 32768f
            i += 2
        }
        feedFloatPcm(samples.copyOf(s), sampleRateHz)
    }

    override fun finalizeUtterance() {
        if (!active.get()) return
        pending = executor.submit {
            val snap = ring.snapshotFloat()
            if (snap.isEmpty()) {
                emitFinal(lastPartial)
                return@submit
            }
            val text = transcribe(snap, isFinal = true)
            if (text != null) {
                emitFinal(text)
            } else if (lastPartial.isNotBlank()) {
                emitFinal(lastPartial)
            } else {
                emitError(AsrErrorKind.NETWORK)
            }
        }
    }

    override fun cancel(reason: AsrCancelReason) {
        if (!active.compareAndSet(true, false)) return
        pending?.cancel(true)
        executor.shutdownNow()
        listener.onEvent(
            AsrResult(
                requestId = config.requestId,
                text = lastPartial,
                isFinal = false,
                provider = backendId,
                model = modelId,
                sessionId = config.sessionId,
                phase = AsrSessionPhase.CANCEL,
                errorKind = AsrErrorKind.CANCELLED,
            ),
        )
    }

    override fun isActive(): Boolean = active.get()

    private fun maybeInterim() {
        val now = System.currentTimeMillis()
        if (now - lastInterimAt.get() < interimIntervalMs) return
        if (!inFlight.compareAndSet(false, true)) return
        lastInterimAt.set(now)
        pending = executor.submit {
            try {
                if (!active.get()) return@submit
                val snap = ring.snapshotFloat()
                // Need ~0.6s of audio before useful interim
                if (snap.size < config.sampleRateHz * 0.6) return@submit
                val text = transcribe(snap, isFinal = false) ?: return@submit
                if (text.isBlank() || text == lastPartial) return@submit
                lastPartial = text
                listener.onEvent(
                    AsrResult(
                        requestId = config.requestId,
                        text = text,
                        isFinal = false,
                        confidence = AsrConfidence.Unknown,
                        language = config.language,
                        provider = backendId,
                        model = modelId,
                        sessionId = config.sessionId,
                        phase = AsrSessionPhase.PARTIAL,
                    ),
                )
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun transcribe(samples: FloatArray, isFinal: Boolean): String? {
        if (apiKey.isBlank()) return null
        val pcm = AsrAudioFormat.floatToPcm16le(samples)
        val wav = AsrAudioFormat.pcm16leToWav(pcm, config.sampleRateHz)
        val url = "$baseUrl/audio/transcriptions"
        val boundary = "----JarvisAsr${UUID.randomUUID().toString().replace("-", "")}"
        return try {
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                // Never log Authorization
            }
            try {
                conn.outputStream.use { os ->
                    writeMultipart(os, boundary, wav, isFinal)
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    Log.w(TAG, "whisper HTTP $code (key redacted)")
                    return null
                }
                parseTranscript(body)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "whisper transport failed: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun writeMultipart(os: OutputStream, boundary: String, wav: ByteArray, isFinal: Boolean) {
        val w = OutputStreamWriter(os, Charsets.UTF_8)
        fun field(name: String, value: String) {
            w.append("--").append(boundary).append("\r\n")
            w.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
            w.append(value).append("\r\n")
        }
        field("model", modelId)
        field("language", config.language.take(2).ifBlank { "ru" })
        field("response_format", "json")
        if (config.vocabularyHints.isNotEmpty()) {
            field("prompt", config.vocabularyHints.take(12).joinToString(", "))
        }
        w.append("--").append(boundary).append("\r\n")
        w.append("Content-Disposition: form-data; name=\"file\"; filename=\"utt.wav\"\r\n")
        w.append("Content-Type: audio/wav\r\n\r\n")
        w.flush()
        os.write(wav)
        os.flush()
        w.append("\r\n--").append(boundary).append("--\r\n")
        w.flush()
    }

    private fun parseTranscript(body: String): String? {
        return try {
            JSONObject(body).optString("text").trim().ifBlank { null }
        } catch (_: Throwable) {
            body.trim().ifBlank { null }
        }
    }

    private fun emitFinal(text: String) {
        if (!active.compareAndSet(true, false)) return
        executor.shutdownNow()
        listener.onEvent(
            AsrResult(
                requestId = config.requestId,
                text = text.trim(),
                isFinal = true,
                confidence = AsrConfidence.Unknown,
                language = config.language,
                provider = backendId,
                model = modelId,
                sessionId = config.sessionId,
                phase = AsrSessionPhase.FINAL,
            ),
        )
    }

    private fun emitError(kind: AsrErrorKind) {
        if (!active.compareAndSet(true, false)) return
        executor.shutdownNow()
        listener.onEvent(
            AsrResult(
                requestId = config.requestId,
                text = lastPartial,
                isFinal = false,
                provider = backendId,
                model = modelId,
                sessionId = config.sessionId,
                phase = AsrSessionPhase.ERROR,
                errorKind = kind,
            ),
        )
    }

    companion object {
        private const val TAG = "Jarvis.Asr"
    }
}
