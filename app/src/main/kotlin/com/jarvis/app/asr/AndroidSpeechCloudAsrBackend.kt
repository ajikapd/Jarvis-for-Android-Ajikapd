package com.jarvis.app.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.jarvis.core.voice.AsrCancelReason
import com.jarvis.core.voice.AsrConfidence
import com.jarvis.core.voice.AsrErrorKind
import com.jarvis.core.voice.AsrResult
import com.jarvis.core.voice.AsrSessionConfig
import com.jarvis.core.voice.AsrSessionListener
import com.jarvis.core.voice.AsrSessionPhase
import com.jarvis.core.voice.StreamingAsrBackend
import com.jarvis.core.voice.StreamingAsrSession
import com.jarvis.core.voice.VoiceProviderAvailability
import com.jarvis.core.voice.VoiceProviderKind
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2-08 cloud ASR via Android [SpeechRecognizer] (typically Google on-device/cloud STT).
 *
 * Real streaming partials. Does not store API keys.
 * Mic ownership: this backend starts its own recognition audio path — callers must
 * pause [com.jarvis.audio.AudioCapture] for the session duration to avoid mic contention.
 */
class AndroidSpeechCloudAsrBackend(
    private val appContext: Context,
    private val online: () -> Boolean = { true },
) : StreamingAsrBackend {
    override val id: String = "asr.cloud.android_speech"
    override val kind: VoiceProviderKind = VoiceProviderKind.CLOUD
    override val model: String = "android.speech.SpeechRecognizer"
    override val supportsStreamingPartials: Boolean = true

    override fun availability(): VoiceProviderAvailability {
        if (!online()) return VoiceProviderAvailability.UNAVAILABLE
        return if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            VoiceProviderAvailability.AVAILABLE
        } else {
            VoiceProviderAvailability.UNAVAILABLE
        }
    }

    override fun openSession(config: AsrSessionConfig, listener: AsrSessionListener): StreamingAsrSession =
        AndroidSpeechSession(appContext, config, listener, id, model)
}

private class AndroidSpeechSession(
    private val appContext: Context,
    private val config: AsrSessionConfig,
    private val listener: AsrSessionListener,
    private val backendId: String,
    private val modelId: String,
) : StreamingAsrSession {
    override val sessionId: String = config.sessionId
    override val providerId: String = backendId

    private val active = AtomicBoolean(true)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var lastPartial = ""
    private var started = false

    init {
        main.post { startRecognition() }
    }

    private fun startRecognition() {
        if (!active.get()) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            emitError(AsrErrorKind.UNAVAILABLE, "SpeechRecognizer unavailable")
            return
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i(TAG, "ready session=${config.sessionId}")
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = firstResult(partialResults) ?: return
                if (text.isBlank() || text == lastPartial) return
                lastPartial = text
                emit(
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
            }

            override fun onResults(results: Bundle?) {
                val text = firstResult(results) ?: lastPartial
                emitFinal(text)
            }

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                val kind = mapError(error)
                Log.w(TAG, "error=$error kind=$kind session=${config.sessionId}")
                if (lastPartial.isNotBlank() &&
                    (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_NO_MATCH)
                ) {
                    emitFinal(lastPartial)
                } else {
                    emitError(kind, "speech_error_$error")
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            if (config.vocabularyHints.isNotEmpty()) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
        }
        started = true
        sr.startListening(intent)
    }

    override fun feedFloatPcm(samples: FloatArray, sampleRateHz: Int) {
        // SpeechRecognizer owns the mic — PCM from AudioCapture is ignored by design.
    }

    override fun feedPcm16le(bytes: ByteArray, sampleRateHz: Int) = Unit

    override fun finalizeUtterance() {
        main.post {
            if (!active.get()) return@post
            recognizer?.stopListening()
        }
    }

    override fun cancel(reason: AsrCancelReason) {
        if (!active.compareAndSet(true, false)) return
        main.post {
            runCatching {
                recognizer?.cancel()
                recognizer?.destroy()
            }
            recognizer = null
        }
        emit(
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

    private fun emitFinal(text: String) {
        if (!active.compareAndSet(true, false)) return
        main.post {
            runCatching {
                recognizer?.destroy()
            }
            recognizer = null
        }
        emit(
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

    private fun emitError(kind: AsrErrorKind, detail: String) {
        if (!active.compareAndSet(true, false)) return
        main.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        Log.w(TAG, "asr error $detail")
        emit(
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

    private fun emit(result: AsrResult) = listener.onEvent(result)

    private fun firstResult(bundle: Bundle?): String? {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return null
        return list.firstOrNull()?.trim()
    }

    private fun mapError(error: Int): AsrErrorKind = when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> AsrErrorKind.NETWORK
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> AsrErrorKind.AUTH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> AsrErrorKind.TIMEOUT
        SpeechRecognizer.ERROR_AUDIO,
        SpeechRecognizer.ERROR_CLIENT,
        -> AsrErrorKind.INVALID_AUDIO
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_SERVER,
        -> AsrErrorKind.UNAVAILABLE
        SpeechRecognizer.ERROR_NO_MATCH -> AsrErrorKind.UNSUPPORTED
        else -> AsrErrorKind.INTERNAL
    }

    companion object {
        private const val TAG = "Jarvis.Asr"
    }
}
