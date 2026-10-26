package com.jarvis.core.voice

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * V2-08: single ASR session identity with cloud→local fallback and duplicate-final prevention.
 *
 * Does not create a second mic subsystem — callers feed PCM from the existing AudioCapture path.
 */
class HybridAsrController(
    private val router: StreamingAsrRouter,
    private val local: StreamingAsrBackend,
    private val cloud: StreamingAsrBackend? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var active: ActiveSession? = null

    fun start(
        config: AsrSessionConfig,
        routeCtx: AsrRouteContext,
        listener: AsrSessionListener,
    ): AsrTelemetry {
        cancel(AsrCancelReason.NEW_INTERACTION)
        val decision = router.decide(
            routeCtx.copy(privacyRestricted = routeCtx.privacyRestricted || config.privacyLocalRequired),
        )
        val telemetry = AsrTelemetry(
            asrProvider = decision.providerId,
            asrModel = decision.model,
            asrRoute = decision.kind.name.lowercase(),
            asrRouteReason = decision.reason.name,
            asrSessionStartAt = clockMs(),
        )
        val primary = if (decision.kind == VoiceProviderKind.CLOUD && cloud != null) cloud else local
        val session = ActiveSession(
            config = config,
            decision = decision,
            telemetry = telemetry,
            primaryBackend = primary,
            localBackend = local,
            listener = listener,
            clockMs = clockMs,
            allowCloudFallback = primary.kind == VoiceProviderKind.CLOUD &&
                local.availability() == VoiceProviderAvailability.AVAILABLE,
        )
        synchronized(lock) { active = session }
        session.open()
        return telemetry
    }

    fun feedFloatPcm(samples: FloatArray, sampleRateHz: Int = 16_000) {
        synchronized(lock) { active }?.feedFloatPcm(samples, sampleRateHz)
    }

    fun finalizeUtterance() {
        synchronized(lock) { active }?.finalizeUtterance()
    }

    fun cancel(reason: AsrCancelReason) {
        val s = synchronized(lock) {
            val cur = active
            active = null
            cur
        }
        s?.cancel(reason)
    }

    fun isActive(): Boolean = synchronized(lock) { active?.isActive() == true }

    fun currentTelemetry(): AsrTelemetry? = synchronized(lock) { active?.telemetry }

    private class ActiveSession(
        val config: AsrSessionConfig,
        val decision: AsrRouteDecision,
        val telemetry: AsrTelemetry,
        private val primaryBackend: StreamingAsrBackend,
        private val localBackend: StreamingAsrBackend,
        private val listener: AsrSessionListener,
        private val clockMs: () -> Long,
        private val allowCloudFallback: Boolean,
    ) {
        private val closed = AtomicBoolean(false)
        private val finalEmitted = AtomicBoolean(false)
        private val fallbackStarted = AtomicBoolean(false)
        private val lastPartial = AtomicReference("")
        private val pcmRing = BoundedPcmRing(config.maxBufferedMs, config.sampleRateHz)
        private var session: StreamingAsrSession? = null
        private var usingFallback = false
        private val startedAt = clockMs()

        fun open() {
            val setupStart = clockMs()
            session = primaryBackend.openSession(config, forwardingListener(primary = true))
            telemetry.asrSetupMs = (clockMs() - setupStart).coerceAtLeast(0L)
            emitPhase(AsrSessionPhase.START, "")
        }

        fun feedFloatPcm(samples: FloatArray, sampleRateHz: Int) {
            if (closed.get()) return
            pcmRing.appendFloat(samples, sampleRateHz)
            session?.feedFloatPcm(samples, sampleRateHz)
        }

        fun finalizeUtterance() {
            if (closed.get()) return
            session?.finalizeUtterance()
        }

        fun cancel(reason: AsrCancelReason) {
            if (!closed.compareAndSet(false, true)) return
            session?.cancel(reason)
            if (!finalEmitted.get()) {
                emit(
                    AsrResult(
                        requestId = config.requestId,
                        text = lastPartial.get(),
                        isFinal = false,
                        provider = telemetry.asrProvider ?: primaryBackend.id,
                        model = telemetry.asrModel ?: primaryBackend.model,
                        sessionId = config.sessionId,
                        phase = AsrSessionPhase.CANCEL,
                        errorKind = AsrErrorKind.CANCELLED,
                        fallback = usingFallback,
                        fallbackReason = telemetry.asrFallbackReason?.let {
                            runCatching { AsrRouteReason.valueOf(it) }.getOrNull()
                        },
                    ),
                )
            }
        }

        fun isActive(): Boolean = !closed.get()

        private fun forwardingListener(primary: Boolean) = object : AsrSessionListener {
            override fun onEvent(result: AsrResult) {
                handleEvent(result, fromPrimary = primary)
            }
        }

        private fun handleEvent(result: AsrResult, fromPrimary: Boolean) {
            if (closed.get()) return
            when (result.phase) {
                AsrSessionPhase.PARTIAL -> {
                    if (result.text.isBlank()) return
                    lastPartial.set(result.text)
                    markPartial(result.text)
                    emit(result.copy(
                        requestId = config.requestId,
                        sessionId = config.sessionId,
                        fallback = usingFallback,
                    ))
                }
                AsrSessionPhase.FINAL, AsrSessionPhase.COMPLETE -> {
                    emitFinalOnce(result)
                }
                AsrSessionPhase.ERROR -> {
                    if (fromPrimary && allowCloudFallback && !fallbackStarted.get()) {
                        startLocalFallback(result.errorKind ?: AsrErrorKind.INTERNAL)
                    } else if (!finalEmitted.get()) {
                        emitFinalOnce(
                            result.copy(
                                text = lastPartial.get(),
                                isFinal = true,
                                phase = AsrSessionPhase.FINAL,
                            ),
                        )
                    }
                }
                AsrSessionPhase.CANCEL -> {
                    cancel(AsrCancelReason.PROVIDER_FAILURE)
                }
                AsrSessionPhase.START -> Unit
            }
        }

        private fun startLocalFallback(errorKind: AsrErrorKind) {
            if (!fallbackStarted.compareAndSet(false, true)) return
            usingFallback = true
            telemetry.asrFallback = true
            telemetry.asrFallbackReason = AsrRouteReason.FALLBACK.name
            telemetry.asrProvider = localBackend.id
            telemetry.asrModel = localBackend.model
            telemetry.asrRoute = VoiceProviderKind.LOCAL.name.lowercase()
            telemetry.asrRouteReason = AsrRouteReason.FALLBACK.name
            runCatching { session?.cancel(AsrCancelReason.PROVIDER_FAILURE) }
            val buffered = pcmRing.snapshotFloat()
            // Clear lastPartial so local final replaces cloud partial (no concatenation).
            lastPartial.set("")
            session = localBackend.openSession(config, forwardingListener(primary = false))
            if (buffered.isNotEmpty()) {
                session?.feedFloatPcm(buffered, config.sampleRateHz)
            }
            session?.finalizeUtterance()
            // Keep errorKind in diagnostics via telemetry only; UX stays transparent.
            runCatching {
                android.util.Log.i(
                    "Jarvis.Asr",
                    "fallback local after cloud error=$errorKind session=${config.sessionId}",
                )
            }
        }

        private fun emitFinalOnce(result: AsrResult) {
            if (!finalEmitted.compareAndSet(false, true)) return
            closed.set(true)
            val now = clockMs()
            telemetry.asrFinalAt = now
            telemetry.asrFinalizeMs = telemetry.asrSessionStartAt?.let { (now - it).coerceAtLeast(0L) }
            telemetry.asrTotalMs = (now - startedAt).coerceAtLeast(0L)
            val text = result.text.ifBlank { lastPartial.get() }
            emit(
                result.copy(
                    requestId = config.requestId,
                    sessionId = config.sessionId,
                    text = text,
                    isFinal = true,
                    phase = AsrSessionPhase.FINAL,
                    provider = telemetry.asrProvider ?: result.provider,
                    model = telemetry.asrModel ?: result.model,
                    fallback = usingFallback || result.fallback,
                    fallbackReason = if (usingFallback) AsrRouteReason.FALLBACK else result.fallbackReason,
                    setupMs = telemetry.asrSetupMs,
                    firstPartialMs = telemetry.asrFirstPartialMs,
                    finalizeMs = telemetry.asrFinalizeMs,
                    totalMs = telemetry.asrTotalMs,
                ),
            )
            emit(
                result.copy(
                    requestId = config.requestId,
                    sessionId = config.sessionId,
                    text = text,
                    isFinal = true,
                    phase = AsrSessionPhase.COMPLETE,
                    provider = telemetry.asrProvider ?: result.provider,
                    model = telemetry.asrModel ?: result.model,
                    fallback = usingFallback,
                ),
            )
        }

        private fun markPartial(text: String) {
            val now = clockMs()
            if (telemetry.asrFirstPartialAt == null) {
                telemetry.asrFirstPartialAt = now
                telemetry.asrFirstPartialMs =
                    telemetry.asrSessionStartAt?.let { (now - it).coerceAtLeast(0L) }
            }
            if (text.length >= 4 && telemetry.asrFirstStablePartialAt == null) {
                telemetry.asrFirstStablePartialAt = now
            }
        }

        private fun emitPhase(phase: AsrSessionPhase, text: String) {
            emit(
                AsrResult(
                    requestId = config.requestId,
                    text = text,
                    isFinal = false,
                    provider = telemetry.asrProvider ?: primaryBackend.id,
                    model = telemetry.asrModel ?: primaryBackend.model,
                    sessionId = config.sessionId,
                    phase = phase,
                ),
            )
        }

        private fun emit(result: AsrResult) {
            listener.onEvent(result)
        }
    }
}

/**
 * Bounded PCM ring — prevents unbounded growth under cloud backpressure.
 * Drops oldest samples when capacity exceeded (keeps newest speech).
 */
class BoundedPcmRing(
    maxBufferedMs: Int,
    sampleRateHz: Int,
) {
    private val capacity = ((maxBufferedMs / 1000.0) * sampleRateHz).toInt().coerceAtLeast(sampleRateHz)
    private val buf = FloatArray(capacity)
    private var size = 0
    private var write = 0

    @Synchronized
    fun appendFloat(samples: FloatArray, sampleRateHz: Int) {
        // Assume caller sample rate matches; no silent resample here.
        for (s in samples) {
            buf[write] = s
            write = (write + 1) % capacity
            if (size < capacity) size++
        }
    }

    @Synchronized
    fun snapshotFloat(): FloatArray {
        if (size == 0) return FloatArray(0)
        val out = FloatArray(size)
        val start = if (size == capacity) write else 0
        for (i in 0 until size) {
            out[i] = buf[(start + i) % capacity]
        }
        return out
    }

    @Synchronized
    fun clear() {
        size = 0
        write = 0
    }
}

/** Float PCM ↔ PCM16 LE helpers for cloud transports. */
object AsrAudioFormat {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNELS = 1
    /** Typical feed chunk ~20–40 ms at 16 kHz. */
    const val PREFERRED_CHUNK_SAMPLES = 640

    fun floatToPcm16le(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var o = 0
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            out[o++] = (v.toInt() and 0xff).toByte()
            out[o++] = ((v.toInt() shr 8) and 0xff).toByte()
        }
        return out
    }

    fun pcm16leToWav(pcm16le: ByteArray, sampleRateHz: Int = SAMPLE_RATE_HZ): ByteArray {
        val dataSize = pcm16le.size
        val out = ByteArray(44 + dataSize)
        fun w(s: String, at: Int) {
            for (i in s.indices) out[at + i] = s[i].code.toByte()
        }
        fun i32(v: Int, at: Int) {
            out[at] = (v and 0xff).toByte()
            out[at + 1] = ((v shr 8) and 0xff).toByte()
            out[at + 2] = ((v shr 16) and 0xff).toByte()
            out[at + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun i16(v: Int, at: Int) {
            out[at] = (v and 0xff).toByte()
            out[at + 1] = ((v shr 8) and 0xff).toByte()
        }
        w("RIFF", 0); i32(36 + dataSize, 4); w("WAVE", 8)
        w("fmt ", 12); i32(16, 16); i16(1, 20); i16(1, 22)
        i32(sampleRateHz, 24); i32(sampleRateHz * 2, 28); i16(2, 32); i16(16, 34)
        w("data", 36); i32(dataSize, 40)
        System.arraycopy(pcm16le, 0, out, 44, dataSize)
        return out
    }
}
