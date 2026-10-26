package com.jarvis.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FakeTtsBackend(
    override val id: String,
    override val kind: VoiceProviderKind,
    override val model: String = "m",
    override val defaultVoice: String = "v",
    override val supportsStreamingAudio: Boolean = false,
    private var avail: VoiceProviderAvailability = VoiceProviderAvailability.AVAILABLE,
    private val failWith: TtsErrorKind? = null,
    private val produceSamples: Boolean = true,
    private val onSynth: (() -> Unit)? = null,
) : StreamingTtsBackend {
    val calls = AtomicInteger(0)

    fun setAvail(a: VoiceProviderAvailability) {
        avail = a
    }

    override fun availability() = avail

    override fun synthesize(
        text: String,
        voice: TtsVoiceConfig,
        cancelFlag: () -> Boolean,
    ): TtsAudioResult {
        calls.incrementAndGet()
        onSynth?.invoke()
        if (cancelFlag()) {
            return TtsAudioResult(
                FloatArray(0), 22050, id, model, defaultVoice,
                errorKind = TtsErrorKind.CANCELLED,
            )
        }
        if (failWith != null) {
            return TtsAudioResult(
                FloatArray(0), 22050, id, model, defaultVoice,
                errorKind = failWith,
            )
        }
        val samples = if (produceSamples) FloatArray(16) { 0.1f } else FloatArray(0)
        return TtsAudioResult(
            samples = samples,
            sampleRateHz = 22050,
            provider = id,
            model = model,
            voice = defaultVoice,
            synthesisMs = 5,
        )
    }
}

class StreamingTtsRouterTest {
    private fun local(ready: Boolean = true) = FakeTtsBackend(
        "tts.local", VoiceProviderKind.LOCAL,
        avail = if (ready) VoiceProviderAvailability.AVAILABLE else VoiceProviderAvailability.UNAVAILABLE,
    )

    private fun cloud(
        avail: VoiceProviderAvailability = VoiceProviderAvailability.AVAILABLE,
        fail: TtsErrorKind? = null,
    ) = FakeTtsBackend(
        "tts.cloud", VoiceProviderKind.CLOUD,
        avail = avail,
        failWith = fail,
    )

    @Test
    fun offlineForcesLocal() {
        val r = StreamingTtsRouter(local(), cloud())
        val d = r.decide(TtsRouteContext(isOnline = false, preference = TtsPreference.CLOUD_PREFERRED))
        assertEquals(TtsRouteReason.OFFLINE, d.reason)
        assertEquals("tts.local", d.providerId)
    }

    @Test
    fun localOnly() {
        val r = StreamingTtsRouter(local(), cloud())
        assertEquals(
            TtsRouteReason.LOCAL_ONLY,
            r.decide(TtsRouteContext(isOnline = true, preference = TtsPreference.LOCAL_ONLY)).reason,
        )
    }

    @Test
    fun cloudPreferredSelectsCloud() {
        val r = StreamingTtsRouter(local(), cloud())
        val d = r.decide(TtsRouteContext(isOnline = true, preference = TtsPreference.CLOUD_PREFERRED))
        assertEquals("tts.cloud", d.providerId)
        assertEquals(TtsRouteReason.USER_PREFERENCE, d.reason)
    }

    @Test
    fun autoPrefersLocalLatency() {
        val r = StreamingTtsRouter(local(), cloud())
        val d = r.decide(TtsRouteContext(isOnline = true, preference = TtsPreference.AUTO))
        assertEquals("tts.local", d.providerId)
        assertEquals(TtsRouteReason.ONLINE_LATENCY, d.reason)
    }

    @Test
    fun cloudUnavailableFallsLocal() {
        val r = StreamingTtsRouter(local(), cloud(VoiceProviderAvailability.UNAVAILABLE))
        val d = r.decide(TtsRouteContext(isOnline = true, preference = TtsPreference.CLOUD_PREFERRED))
        assertEquals(TtsRouteReason.CLOUD_UNAVAILABLE, d.reason)
    }

    @Test
    fun privacyForcesLocal() {
        val r = StreamingTtsRouter(local(), cloud())
        val d = r.decide(
            TtsRouteContext(
                isOnline = true,
                preference = TtsPreference.CLOUD_PREFERRED,
                privacyRestricted = true,
            ),
        )
        assertEquals(TtsRouteReason.PRIVACY, d.reason)
    }
}

class HybridTtsSynthesizerTest {
    @Test
    fun cloudFailureBeforeAudioFallsBackLocal() {
        val local = FakeTtsBackend("tts.local", VoiceProviderKind.LOCAL)
        val cloud = FakeTtsBackend(
            "tts.cloud", VoiceProviderKind.CLOUD,
            failWith = TtsErrorKind.NETWORK,
        )
        val synth = HybridTtsSynthesizer(StreamingTtsRouter(local, cloud), local, cloud)
        val tel = TtsTelemetry()
        val r = synth.synthesizeChunk(
            "привет",
            TtsRouteContext(isOnline = true, preference = TtsPreference.CLOUD_PREFERRED),
            TtsVoiceConfig(),
            cancelFlag = { false },
            telemetry = tel,
        )
        assertFalse(r.isEmpty)
        assertTrue(r.fallback)
        assertEquals("tts.local", r.provider)
        assertTrue(tel.ttsFallback)
        assertEquals(1, cloud.calls.get())
        assertEquals(1, local.calls.get())
    }

    @Test
    fun cloudSuccessNoLocalCall() {
        val local = FakeTtsBackend("tts.local", VoiceProviderKind.LOCAL)
        val cloud = FakeTtsBackend("tts.cloud", VoiceProviderKind.CLOUD)
        val synth = HybridTtsSynthesizer(StreamingTtsRouter(local, cloud), local, cloud)
        val r = synth.synthesizeChunk(
            "ок",
            TtsRouteContext(isOnline = true, preference = TtsPreference.CLOUD_PREFERRED),
            TtsVoiceConfig(),
            cancelFlag = { false },
            telemetry = TtsTelemetry(),
        )
        assertEquals("tts.cloud", r.provider)
        assertEquals(0, local.calls.get())
        assertFalse(r.fallback)
    }

    @Test
    fun cancelDoesNotFallback() {
        val local = FakeTtsBackend("tts.local", VoiceProviderKind.LOCAL)
        val cloud = FakeTtsBackend(
            "tts.cloud", VoiceProviderKind.CLOUD,
            failWith = TtsErrorKind.CANCELLED,
        )
        val synth = HybridTtsSynthesizer(StreamingTtsRouter(local, cloud), local, cloud)
        val tel = TtsTelemetry()
        val r = synth.synthesizeChunk(
            "x",
            TtsRouteContext(isOnline = true, preference = TtsPreference.CLOUD_PREFERRED),
            TtsVoiceConfig(),
            cancelFlag = { false },
            telemetry = tel,
        )
        assertTrue(r.isEmpty)
        assertEquals(0, local.calls.get())
        assertTrue(tel.ttsCancelled || tel.ttsErrorCategory == TtsErrorKind.CANCELLED.name)
    }

    @Test
    fun emptyText() {
        val local = FakeTtsBackend("tts.local", VoiceProviderKind.LOCAL)
        val synth = HybridTtsSynthesizer(StreamingTtsRouter(local, null), local, null)
        val r = synth.synthesizeChunk(
            "  ",
            TtsRouteContext(isOnline = false),
            TtsVoiceConfig(),
            cancelFlag = { false },
            telemetry = TtsTelemetry(),
        )
        assertTrue(r.isEmpty)
        assertEquals(TtsErrorKind.EMPTY_TEXT, r.errorKind)
    }

    @Test
    fun generationCancelMidSynth() {
        val cancel = AtomicBoolean(false)
        val local = FakeTtsBackend(
            "tts.local", VoiceProviderKind.LOCAL,
            onSynth = { cancel.set(true) },
        )
        // cancelFlag checked after synthesize in Hybrid — simulate pre-cancel
        cancel.set(true)
        val synth = HybridTtsSynthesizer(StreamingTtsRouter(local, null), local, null)
        val tel = TtsTelemetry()
        val r = synth.synthesizeChunk(
            "hello",
            TtsRouteContext(isOnline = false),
            TtsVoiceConfig(),
            cancelFlag = { cancel.get() },
            telemetry = tel,
        )
        assertTrue(tel.ttsCancelled)
        assertTrue(r.isEmpty)
    }

    @Test
    fun authCooldownBlocksCloud() {
        val local = FakeTtsBackend("tts.local", VoiceProviderKind.LOCAL)
        val cloud = FakeTtsBackend(
            "tts.cloud", VoiceProviderKind.CLOUD,
            failWith = TtsErrorKind.AUTH,
        )
        val clock = AtomicInteger(1000)
        val synth = HybridTtsSynthesizer(
            StreamingTtsRouter(local, cloud), local, cloud,
            clockMs = { clock.get().toLong() },
        )
        synth.synthesizeChunk(
            "a",
            TtsRouteContext(isOnline = true, preference = TtsPreference.CLOUD_PREFERRED),
            TtsVoiceConfig(),
            cancelFlag = { false },
            telemetry = TtsTelemetry(),
        )
        // After AUTH, cloud should be treated unhealthy via cooldown
        clock.set(2000)
        val tel2 = TtsTelemetry()
        val r2 = synth.synthesizeChunk(
            "b",
            TtsRouteContext(isOnline = true, preference = TtsPreference.CLOUD_PREFERRED),
            TtsVoiceConfig(),
            cancelFlag = { false },
            telemetry = tel2,
        )
        // Second call should skip cloud (cooldown) → local directly
        assertEquals("tts.local", r2.provider)
        assertTrue(cloud.calls.get() <= 1)
    }

    @Test
    fun legacyTtsRouterCompat() {
        val tts = TtsRouter(LocalTtsStub(), CloudTtsStub(configured = true))
        assertEquals(
            "tts.cloud",
            tts.select(VoiceRouteContext(isOnline = true, preferCloud = true)).id,
        )
    }
}
