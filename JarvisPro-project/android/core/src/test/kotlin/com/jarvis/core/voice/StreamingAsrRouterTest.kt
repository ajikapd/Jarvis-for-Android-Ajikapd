package com.jarvis.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FakeStreamingBackend(
    override val id: String,
    override val kind: VoiceProviderKind,
    override val model: String = "fake-model",
    override val supportsStreamingPartials: Boolean = true,
    private var avail: VoiceProviderAvailability = VoiceProviderAvailability.AVAILABLE,
    private val onOpen: (AsrSessionConfig, AsrSessionListener) -> StreamingAsrSession,
) : StreamingAsrBackend {
    fun setAvail(a: VoiceProviderAvailability) {
        avail = a
    }

    override fun availability() = avail

    override fun openSession(config: AsrSessionConfig, listener: AsrSessionListener): StreamingAsrSession =
        onOpen(config, listener)
}

class RecordingSession(
    override val sessionId: String,
    override val providerId: String,
    private val listener: AsrSessionListener,
    private val requestId: String,
    private val model: String,
    private val failOnFeed: Boolean = false,
) : StreamingAsrSession {
    val fed = AtomicInteger(0)
    private var active = true
    var lastText = ""

    override fun feedFloatPcm(samples: FloatArray, sampleRateHz: Int) {
        if (!active) return
        if (failOnFeed) {
            active = false
            listener.onEvent(
                AsrResult(
                    requestId = requestId,
                    text = lastText,
                    isFinal = false,
                    provider = providerId,
                    model = model,
                    sessionId = sessionId,
                    phase = AsrSessionPhase.ERROR,
                    errorKind = AsrErrorKind.NETWORK,
                ),
            )
            return
        }
        fed.incrementAndGet()
        lastText = "partial-${fed.get()}"
        listener.onEvent(
            AsrResult(
                requestId = requestId,
                text = lastText,
                isFinal = false,
                provider = providerId,
                model = model,
                sessionId = sessionId,
                phase = AsrSessionPhase.PARTIAL,
            ),
        )
    }

    override fun feedPcm16le(bytes: ByteArray, sampleRateHz: Int) = Unit

    override fun finalizeUtterance() {
        if (!active) return
        active = false
        listener.onEvent(
            AsrResult(
                requestId = requestId,
                text = lastText.ifBlank { "final" },
                isFinal = true,
                provider = providerId,
                model = model,
                sessionId = sessionId,
                phase = AsrSessionPhase.FINAL,
            ),
        )
    }

    override fun cancel(reason: AsrCancelReason) {
        active = false
    }

    override fun isActive() = active
}

class StreamingAsrRouterTest {
    private fun local() = FakeStreamingBackend("asr.local", VoiceProviderKind.LOCAL) { c, l ->
        RecordingSession(c.sessionId, "asr.local", l, c.requestId, "local")
    }

    private fun cloud(avail: VoiceProviderAvailability = VoiceProviderAvailability.AVAILABLE) =
        FakeStreamingBackend("asr.cloud", VoiceProviderKind.CLOUD) { c, l ->
            RecordingSession(c.sessionId, "asr.cloud", l, c.requestId, "cloud")
        }.also { it.setAvail(avail) }

    @Test
    fun onlineAutoPrefersLocalLatency() {
        val r = StreamingAsrRouter(local(), cloud())
        val d = r.decide(AsrRouteContext(isOnline = true, preference = AsrPreference.AUTO))
        assertEquals("asr.local", d.providerId)
        assertEquals(AsrRouteReason.ONLINE_LATENCY, d.reason)
    }

    @Test
    fun offlineForcesLocal() {
        val r = StreamingAsrRouter(local(), cloud())
        val d = r.decide(AsrRouteContext(isOnline = false, preference = AsrPreference.CLOUD_PREFERRED))
        assertEquals("asr.local", d.providerId)
        assertEquals(AsrRouteReason.OFFLINE, d.reason)
    }

    @Test
    fun privacyForcesLocal() {
        val r = StreamingAsrRouter(local(), cloud())
        val d = r.decide(
            AsrRouteContext(
                isOnline = true,
                preference = AsrPreference.CLOUD_PREFERRED,
                privacyRestricted = true,
            ),
        )
        assertEquals(AsrRouteReason.PRIVACY, d.reason)
        assertEquals("asr.local", d.providerId)
    }

    @Test
    fun localOnly() {
        val r = StreamingAsrRouter(local(), cloud())
        val d = r.decide(AsrRouteContext(isOnline = true, preference = AsrPreference.LOCAL_ONLY))
        assertEquals(AsrRouteReason.LOCAL_ONLY, d.reason)
    }

    @Test
    fun cloudUnavailableFallsLocal() {
        val r = StreamingAsrRouter(local(), cloud(VoiceProviderAvailability.UNAVAILABLE))
        val d = r.decide(AsrRouteContext(isOnline = true, preference = AsrPreference.CLOUD_PREFERRED))
        assertEquals(AsrRouteReason.CLOUD_UNAVAILABLE, d.reason)
    }

    @Test
    fun cloudPreferredSelectsCloud() {
        val r = StreamingAsrRouter(local(), cloud())
        val d = r.decide(AsrRouteContext(isOnline = true, preference = AsrPreference.CLOUD_PREFERRED))
        assertEquals("asr.cloud", d.providerId)
        assertEquals(AsrRouteReason.USER_PREFERENCE, d.reason)
    }

    @Test
    fun providerHealthBlocksCloud() {
        val r = StreamingAsrRouter(local(), cloud())
        val d = r.decide(
            AsrRouteContext(
                isOnline = true,
                preference = AsrPreference.CLOUD_PREFERRED,
                cloudHealthy = false,
            ),
        )
        assertEquals(AsrRouteReason.CLOUD_UNAVAILABLE, d.reason)
    }
}

class HybridAsrControllerTest {
    @Test
    fun sessionStartPartialFinalComplete() {
        val events = CopyOnWriteArrayList<AsrResult>()
        val local = FakeStreamingBackend("asr.local", VoiceProviderKind.LOCAL) { c, l ->
            RecordingSession(c.sessionId, "asr.local", l, c.requestId, "m")
        }
        val router = StreamingAsrRouter(local, null)
        val ctrl = HybridAsrController(router, local, null) { 1000L }
        ctrl.start(
            AsrSessionConfig("r1", "s1"),
            AsrRouteContext(isOnline = false),
            AsrSessionListener { events += it },
        )
        ctrl.feedFloatPcm(FloatArray(160))
        ctrl.finalizeUtterance()
        assertTrue(events.any { it.phase == AsrSessionPhase.START })
        assertTrue(events.any { it.phase == AsrSessionPhase.PARTIAL })
        assertTrue(events.any { it.phase == AsrSessionPhase.FINAL && it.isFinal })
        assertTrue(events.any { it.phase == AsrSessionPhase.COMPLETE })
        assertEquals(1, events.count { it.phase == AsrSessionPhase.FINAL })
    }

    @Test
    fun cloudFailureFallsBackWithoutDuplicateFinal() {
        val events = CopyOnWriteArrayList<AsrResult>()
        val local = FakeStreamingBackend("asr.local", VoiceProviderKind.LOCAL) { c, l ->
            RecordingSession(c.sessionId, "asr.local", l, c.requestId, "local-m")
        }
        val cloud = FakeStreamingBackend("asr.cloud", VoiceProviderKind.CLOUD) { c, l ->
            RecordingSession(c.sessionId, "asr.cloud", l, c.requestId, "cloud-m", failOnFeed = true)
        }
        val router = StreamingAsrRouter(local, cloud)
        val ctrl = HybridAsrController(router, local, cloud) { 2000L }
        ctrl.start(
            AsrSessionConfig("r2", "s2"),
            AsrRouteContext(isOnline = true, preference = AsrPreference.CLOUD_PREFERRED),
            AsrSessionListener { events += it },
        )
        ctrl.feedFloatPcm(FloatArray(320))
        // Give fallback finalize a tick
        Thread.sleep(50)
        assertTrue(
            "expected fallback flag, tel=${ctrl.currentTelemetry()}",
            ctrl.currentTelemetry()?.asrFallback == true ||
                events.any { it.fallback },
        )
        assertEquals(1, events.count { it.phase == AsrSessionPhase.FINAL })
        val finalText = events.first { it.phase == AsrSessionPhase.FINAL }.text
        assertFalse(finalText.contains("открой Telegram открой Telegram"))
    }

    @Test
    fun cancelStopsSession() {
        val events = CopyOnWriteArrayList<AsrResult>()
        val local = FakeStreamingBackend("asr.local", VoiceProviderKind.LOCAL) { c, l ->
            RecordingSession(c.sessionId, "asr.local", l, c.requestId, "m")
        }
        val ctrl = HybridAsrController(StreamingAsrRouter(local, null), local, null)
        ctrl.start(
            AsrSessionConfig("r3", "s3"),
            AsrRouteContext(isOnline = false),
            AsrSessionListener { events += it },
        )
        ctrl.cancel(AsrCancelReason.BARGE_IN)
        assertTrue(events.any { it.phase == AsrSessionPhase.CANCEL })
        assertFalse(ctrl.isActive())
    }

    @Test
    fun boundedPcmRingDropsOldest() {
        val ring = BoundedPcmRing(maxBufferedMs = 1000, sampleRateHz = 10) // capacity 10
        ring.appendFloat(FloatArray(8) { 1f }, 10)
        ring.appendFloat(FloatArray(8) { 2f }, 10)
        val snap = ring.snapshotFloat()
        assertEquals(10, snap.size)
        // Newest samples dominate after wrap
        assertTrue(snap.count { it == 2f } >= 5)
    }

    @Test
    fun audioFormatWavHeader() {
        val pcm = AsrAudioFormat.floatToPcm16le(FloatArray(16) { 0.1f })
        val wav = AsrAudioFormat.pcm16leToWav(pcm, 16_000)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertTrue(wav.size > 44)
    }
}

class LegacyAsrRouterStillWorks {
    @Test
    fun stage22Compat() {
        val asr = AsrRouter(LocalAsrStub(), CloudAsrStub(configured = true))
        assertEquals(
            "asr.cloud",
            asr.select(VoiceRouteContext(isOnline = true, preferCloud = true)).id,
        )
    }
}
