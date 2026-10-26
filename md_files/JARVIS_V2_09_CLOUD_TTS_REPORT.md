# JARVIS V2-09 — Cloud TTS Report

**Stage:** V2-09 Cloud TTS  
**Date:** 2026-10-01  
**Status:** `V2-09 COMPLETE`  
**Build:** `./gradlew :app:assembleDebug` — SUCCESS  
**Unit tests:** `com.jarvis.core.voice.*` (ASR + TTS) — PASS  

**Stop rule:** V2-10 not started.

---

## 1. Architecture before V2-09

```text
ChatStreamPipeline (V2-07)
  → SentenceSegmenter
  → VoiceService sink
  → JarvisSpeaker.enqueueSpeak(generation)
  → JarvisTts.synthesize (local Piper only)
  → TtsPlayer.play / playContinuous
```

- **Playback owner:** `JarvisSpeaker` → `TtsPlayer` (sole)
- **TTS session:** local `JarvisTts` (VITS Piper `dmitri`) inside `JarvisSpeaker`
- **TtsRouter:** Stage-22 stub (`LocalTtsStub` / `CloudTtsStub(configured=false)`) in BrainGraph — not on live path
- **Cancel:** `InteractionGenerationGate` + `speakGeneration` + queue clear + `player.stop()`
- **V2-08 ASR:** unchanged (StreamingAsrRouter / HybridAsrController)

---

## 2. What changed

| Area | Change |
|------|--------|
| Contracts | `TtsContracts.kt`: preference, route reasons, errors, voice config, `StreamingTtsBackend`, `StreamingTtsRouter`, `HybridTtsSynthesizer` |
| Local | `LocalPiperTtsBackend` wraps resident `JarvisTts` |
| Cloud | `OpenAiSpeechTtsBackend` (`/v1/audio/speech` PCM) **or** `AndroidSystemTtsBackend` (TextToSpeech→WAV→float) |
| Live path | `JarvisSpeaker` synthesizes via `HybridTtsBridge` per sentence chunk; **same** `TtsPlayer` |
| Settings | `tts_mode` AUTO / CLOUD_PREFERRED / LOCAL_ONLY + optional speech URL/key |
| Telemetry | `RequestTrace` TTS fields + `Jarvis.Tts` logs |
| Queue | Bounded depth (12) with drop-oldest backpressure |

Wake/KWS, V2-07 stream, V2-08 ASR **not rewritten**.

---

## 3. Cloud / local backends (real)

| Backend | ID | When used | Streaming audio |
|---------|-----|-----------|-----------------|
| Local Piper | `tts.local.piper` | Always available fallback; AUTO default | `false` (utterance PCM) |
| OpenAI-compatible Speech | `tts.cloud.openai_speech` | TTS (or ASR) base URL + key set | `false` (honest — full PCM response) |
| Android TextToSpeech | `tts.cloud.android_tts` | Default cloud when no OpenAI speech URL/key | `false` |

Nara chat gateway has no TTS endpoint — not used.

**Fake streaming:** not claimed. `ttsStreaming=false` for all current backends. Sentence-level queue from V2-07 remains the early-speech mechanism.

---

## 4. Routing policy

`StreamingTtsRouter` (≠ AIRouter, ≠ AsrRouter):

| Condition | Result |
|-----------|--------|
| `LOCAL_ONLY` / privacy | local |
| Offline | local immediately |
| Cloud UNAVAILABLE / unhealthy / cooldown | local |
| `CLOUD_PREFERRED` + cloud AVAILABLE | cloud |
| `AUTO` + both healthy | local (`ONLINE_LATENCY`) |
| Cloud empty/error **before** audio | local fallback (`FALLBACK`) |
| `CANCELLED` | no fallback |
| `AUTH` / `RATE_LIMIT` | cooldown then prefer local |

API key alone ≠ availability (availability checks online + configured/ready).

---

## 5. Cancellation / barge-in

Unchanged ownership chain:

`wake/new request → InteractionGenerationGate.cancel → cancelActiveChatStream → VoiceController.stopSpeaking → speakGeneration++ → queue clear → player.stop`

Hybrid synth checks `cancelFlag` (`gen != speakGeneration`) before/after network work. Stale generation chunks skipped in queue pump. Cloud bytes arriving after cancel are discarded (empty / not played).

---

## 6. Streaming path (V2-07 + V2-09)

```text
LLM SSE deltas
  → SentenceSegmenter (minFlush / punctuation / final flush)
  → enqueueSpeak(sentence, generation)
  → HybridTtsSynthesizer (cloud|local per chunk)
  → TtsPlayer.play(PCM)
  → next sentence…
```

Still **does not** wait for full LLM completion before first TTS.  
Cloud TTS is per-sentence (not one mega-request).

---

## 7. Fallback / no double playback

`HybridTtsSynthesizer`:

1. Try primary (per route).
2. If **non-empty PCM** → return (playback once by Speaker).
3. If empty **and** cloud **and** not CANCELLED → local once.
4. Never starts local after cloud samples already returned.

Playback remains single-owner (`TtsPlayer`); no parallel speak paths.

---

## 8. Telemetry

`TtsTelemetry` / `RequestTrace.applyTts`:

`ttsProvider`, `ttsModel`, `ttsVoice`, `ttsRoute`, `ttsRouteReason`, `ttsFallback`, `ttsStreaming`, `ttsSessionId`, `ttsSetupMs`, `ttsFirstAudioMs`, `ttsFirstPlaybackMs`, `ttsSynthesisMs`, `ttsPlaybackMs`, `ttsTotalMs`, `ttsQueueDepth`, `ttsCancelled`, `ttsErrorCategory`

Secrets and full utterance text are not logged.

---

## 9. Tests

`StreamingTtsRouterTest` / `HybridTtsSynthesizerTest`:

- offline → local  
- cloud preferred → cloud  
- local only → local  
- cloud unavailable → local  
- cloud failure before audio → local fallback  
- cancel / empty text  
- AUTH cooldown  
- legacy Stage-22 `TtsRouter` compat  

Plus existing ASR voice tests still green.

---

## 10. Device benchmark

| Item | Status |
|------|--------|
| `assembleDebug` | SUCCESS |
| Unit matrix | PASS |
| On-device first-audio / CPU / battery / barge-in | **Pending device** — install APK and measure with `adb logcat -s Jarvis.Tts Jarvis.Speaker Jarvis.Latency` |

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Suggested checks: AUTO local; CLOUD_PREFERRED Android TTS; optional OpenAI speech URL; airplane mode; barge-in mid-reply; long streamed CHAT answer.

---

## 11. Latency before / after

| Path | Before | After |
|------|--------|-------|
| Sentence → first audio (AUTO) | Local Piper only | Same local (AUTO) |
| CLOUD_PREFERRED | N/A (stub) | Android TTS or OpenAI speech per sentence |
| Offline | Local | Local, no cloud wait |
| LLM→first sentence→TTS | V2-07 queue | Same queue + hybrid synth |

Honest TTFA = time to first PCM ready / first `TtsPlayer` write — not total synthesis of full reply. `ttsStreaming=false` for cloud audio bytes.

---

## 12. Known limitations

1. No provider-native **audio** streaming (SSE/WebSocket PCM chunks) — sentence queue is the early-speech path.  
2. OpenAI speech needs a Whisper/Speech-capable host (not Nara chat).  
3. Android TTS uses file synthesize + WAV decode (extra latency vs Piper warm).  
4. Cloud voice `alloy` etc. are English-oriented; RU quality varies — local Piper remains RU baseline.  
5. Full device latency/quality/battery matrix not executed in this agent session without a connected device.  
6. `playContinuous` multi-rate mixing if cloud/local rates differ mid-utterance is avoided by per-chunk `play` in stream queue.

---

## 13. Not implemented (deferred)

- Native cloud audio streaming (`streamingSupported=true`)  
- Multiple cloud TTS vendors  
- User-facing voice catalog UI beyond mode + optional URL/key  
- Automatic RU cloud voice discovery  
- V2-10 OS Presence  

---

## 14. Lifecycle truth

| Component | Status |
|-----------|--------|
| `JarvisSpeaker` + `HybridTtsBridge` + `TtsPlayer` | **ACTIVE_ON_LIVE_PATH** |
| `StreamingTtsRouter` / `HybridTtsSynthesizer` | **ACTIVE_ON_LIVE_PATH** (via Speaker) |
| Local Piper | **ACTIVE_ON_LIVE_PATH** |
| Android / OpenAI cloud TTS | **ACTIVE_ON_LIVE_PATH** when mode/route selects them |
| Legacy batch `TtsRouter` stubs in BrainGraph | **CONSTRUCTED** (diagnostics) |
| ChatStreamPipeline → sentence enqueue | **ACTIVE_ON_LIVE_PATH** (unchanged V2-07) |

---

## Status

**`V2-09 COMPLETE`**

Criteria met: unified TTS abstraction + router, real cloud backends, local fallback, AUTO/CLOUD_PREFERRED/LOCAL_ONLY, offline without cloud wait, cancel/barge-in via existing gate, no dual playback owner, V2-07 sentence path wired, telemetry with real provider/model/voice, tests + assembleDebug green. Device wall-clock matrix remains operator follow-up.

**Do not start V2-10 from this report.**
