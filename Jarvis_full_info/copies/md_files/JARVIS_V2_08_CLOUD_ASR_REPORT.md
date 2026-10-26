# JARVIS V2-08 — Cloud ASR Report

**Stage:** V2-08 Cloud ASR  
**Date:** 2026-10-01  
**Status:** COMPLETE (stop before V2-09)  
**Build:** `./gradlew :app:assembleDebug` — SUCCESS  
**Unit tests:** `com.jarvis.core.voice.*` — 13/13 PASS

---

## 1. Existing ASR architecture (pre V2-08)

```text
AudioCapture (PCM float @ 16 kHz mono)
   ↓
VoiceEngine
   ├── Wake/KWS: OwwWakeSpotter (unchanged)
   ├── Command (wake): WakeWordEngine → SherpaAsrSession (local streaming)
   └── Continuous/live: SherpaAsrSession.processChunk → partial → endpoint FINAL
        └── optional OfflineMultilingualAsr / BufferedEnAsr refine
```

Parallel Stage-22 stubs (not on mic path):

- `AsrRouter` + `LocalAsrStub` + `CloudAsrStub(configured=false)` in `BrainGraph`
- Sync `transcribe(pcm)` only — no streaming, not wired to `VoiceEngine`

---

## 2. ASR audit findings

| Question | Finding |
|----------|---------|
| Where ASR runs | `VoiceEngine` → `SherpaAsrSession` (`asr-engine`) |
| Stubs | `CloudAsrStub`, batch `AsrRouter` in BrainGraph diagnostics |
| Transcript creation | Sherpa `getResult`; WakeWordEngine finalize; live endpoint |
| Finalization | Sherpa endpoint / WakeWordEngine timeout / incomplete-hold |
| Partial loss | Partials published via `TranscriptUpdated`; FINAL only on endpoint |
| Streaming input | Yes — chunked float PCM from `AudioCapture` |
| Resampling | None on hot path (16 kHz native) |
| Session lifecycle | `VoiceEngine` owns Sherpa; Wake owns command window |

**Constraint honored:** Wake/KWS / `VoiceService` ownership not rewritten.

---

## 3. Selected cloud provider

**Primary (default, no extra API key):**  
`asr.cloud.android_speech` — Android `SpeechRecognizer` (typically Google cloud STT)

**Optional (when Whisper URL + key configured):**  
`asr.cloud.openai_whisper` — OpenAI-compatible `POST /v1/audio/transcriptions` with **rolling interim windows while speaking**

NaraRouter (existing LLM gateway) has **no** ASR endpoint — not used for speech.

---

## 4. Why this fits

| Requirement | Android Speech | Whisper progressive |
|-------------|----------------|---------------------|
| Real API | Yes (`SpeechRecognizer`) | Yes (`/audio/transcriptions`) |
| Russian | Yes (`ru-RU`) | Yes (`language=ru`) |
| Streaming / near-RT partials | Native partial results | Interim HTTP windows ~900 ms during speech |
| Auth | OS / Play Services (no key in repo) | Settings-stored key (never logged) |
| Android client | First-party | `HttpURLConnection` multipart |
| Fallback | Hybrid → local Sherpa | Hybrid → local Sherpa |
| Mic ownership | Own mic → AudioCapture pause/handoff | Shares AudioCapture PCM (no steal) |

One active cloud backend at a time (`CloudAsrFactory`): Whisper if configured, else Android Speech.

---

## 5. Cloud ASR implementation

| File | Role |
|------|------|
| `app/.../asr/AndroidSpeechCloudAsrBackend.kt` | Streaming cloud via SpeechRecognizer |
| `app/.../asr/OpenAiWhisperStreamingAsrBackend.kt` | Rolling interim + final Whisper |
| `app/.../asr/CloudAsrFactory.kt` | Online check + backend selection |
| `app/.../asr/HybridAsrBridge.kt` | VoiceEngine glue |

Whisper is **not** “record-all-then-upload”: audio is sent in overlapping windows during speech; FINAL uploads the utterance buffer on finalize.

---

## 6. Local ASR integration

`LocalStreamingAsrBackend` wraps the **resident** `SherpaAsrSession` (no second model load).  
`AsrModelSpec.RU_STREAMING` unchanged. Wake still uses the same Sherpa instance for command when mode ≠ `CLOUD_PREFERRED`.

---

## 7. AsrRouter

New **`StreamingAsrRouter`** (domain ≠ `AIRouter`):

```text
StreamingAsrRouter
 ├── local  (Sherpa)
 ├── cloud  (Android Speech | Whisper)
 └── reasons: OFFLINE | PRIVACY | LOCAL_ONLY | USER_PREFERENCE |
              CLOUD_UNAVAILABLE | LOCAL_UNAVAILABLE | ONLINE_LATENCY | ONLINE_QUALITY
```

Policy:

| Mode | Behavior |
|------|----------|
| `LOCAL_ONLY` / privacy | Always local |
| Offline | Local immediately (no cloud timeout wait) |
| `AUTO` | Local when both healthy (`ONLINE_LATENCY`) |
| `CLOUD_PREFERRED` | Cloud when available; else local |

Legacy batch `AsrRouter` kept for Stage-22 tests / diagnostics snapshot.

---

## 8. Session / state model

```text
AsrSessionPhase: START → PARTIAL* → FINAL → COMPLETE
                 ↘ CANCEL | ERROR → (fallback) → FINAL
```

`HybridAsrController` owns one session identity, bounded PCM ring, single FINAL emission.

---

## 9. Partial / final transcript behavior

- PARTIAL: UI / diagnostics only — **never** semantic execution  
- FINAL: single emission per turn → `UserRequest` → existing semantic / V2-07 stream path  
- Incomplete RU commands still use `AsrPhraseNormalizer` hold on local live path  

---

## 10. Fallback

```text
cloud ERROR
 → preserve BoundedPcmRing
 → switch to local (same sessionId/requestId)
 → replay buffer + finalize
 → asrFallback=true, reason=FALLBACK
 → clear cloud partial text (no concatenation)
```

Offline: router selects local **before** opening cloud (no network wait).

---

## 11. Cancellation

`AsrCancelReason`: USER_INTERRUPTION, REQUEST_CANCELLED, SERVICE_DESTROY, NEW_INTERACTION, TIMEOUT, PROVIDER_FAILURE, BARGE_IN  

`VoiceEngine.stop()` / new turn → `HybridAsrBridge.cancelTurn()`.

---

## 12. Barge-in integration

Reuses V2-07 `InteractionGenerationGate` in `VoiceService` — no second cancel bus.  
New ASR turn cancels prior hybrid session (`NEW_INTERACTION`). TTS mute window (~220 ms) unchanged.

---

## 13. Audio format / buffering

| Param | Value |
|-------|-------|
| Sample rate | 16_000 Hz |
| Channels | 1 |
| Local format | float PCM |
| Cloud Whisper | PCM16 LE → WAV |
| Max buffer | 30 s (`BoundedPcmRing`) |
| Backpressure | drop oldest samples |

No extra resample stages on the hot path.

---

## 14. Privacy

| Setting | Effect |
|---------|--------|
| `LOCAL_ONLY` | Cloud never selected; no cloud transfer |
| `privacyRestricted` / `privacyLocalRequired` | Same |
| Diagnostics | Provider/model/latency only — **no full transcript**, **no API keys** |

---

## 15. Settings

Dashboard → Models:

- Spinner: **Auto / Cloud preferred / Local only** (`asr_mode`)
- Optional Whisper base URL + API key (SharedPreferences; empty → Android Speech)
- Hint shows live `provider/model` via `CloudAsrFactory.cloudConfiguredLabel`

---

## 16. Telemetry

`AsrTelemetry` + `RequestTrace` fields:

```text
asrProvider, asrModel, asrRoute, asrRouteReason
asrSetupMs, asrFirstPartialMs, asrFinalizeMs, asrTotalMs
asrFallback, asrFallbackReason
```

Logged under `Jarvis.Asr` / `Jarvis.Trace` (no secrets).

Example:

```text
selected=local provider=asr.local.sherpa model=… reason=ONLINE_LATENCY
selected=cloud provider=asr.cloud.android_speech model=android.speech.SpeechRecognizer reason=USER_PREFERENCE
selected=local reason=OFFLINE
selected=local reason=PRIVACY
```

---

## 17. Latency benchmarks

| Path | Expected (device-dependent) | Measured in CI |
|------|----------------------------|----------------|
| Local warm first partial | tens of ms after speech energy | Unit: session marks only |
| Local final (endpoint) | ~1.6 s silence rule + decode | Not device-run this session |
| Android Speech first partial | typically 300–800 ms after speech | Requires device |
| Whisper interim window | ≥900 ms cadence + RTT | Requires Whisper host |

**Honest note:** Full on-device matrix (local vs cloud firstPartial/final/CPU/RAM/battery) needs a connected device; unit tests validate routing/session/fallback/dedupe, not wall-clock ASR quality.

---

## 18. Quality benchmarks

| Scenario | Status |
|----------|--------|
| Russian local baseline | Unchanged Sherpa model/path for AUTO / wake default |
| Cloud RU | Android Speech `ru-RU`; Whisper `language=ru` |
| App-name hints | Optional vocabulary list (Telegram, WhatsApp, YouTube, …) |
| Mixed RU/EN | Local refine path retained for `asrRoute=local` |

---

## 19. Device matrix

| Case | Plan | This session |
|------|------|--------------|
| Local RU short/long/mixed | Device | APK built; adb matrix pending if device absent |
| Cloud short/long/names | `CLOUD_PREFERRED` | Ready |
| Offline fallback | Airplane mode | Router unit-tested |
| Barge-in | Speak over TTS | Gate reused |
| Background / screen | Existing Wake lifecycle | Not regressed by design |

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`  
Observe: `adb logcat -s Jarvis.Asr Jarvis.Trace Jarvis.Latency`

---

## 20. Tests

`core/src/test/kotlin/com/jarvis/core/voice/StreamingAsrRouterTest.kt`:

- Router: online AUTO→local latency, offline, privacy, local-only, cloud unavailable, cloud preferred, health  
- Session: START / PARTIAL / FINAL / COMPLETE / CANCEL  
- Fallback: cloud fail → local, single FINAL, no duplicate concat  
- Audio: bounded ring, WAV header  
- Legacy Stage-22 `AsrRouter` compat  

**13/13 passed.**

---

## 21. Build

```text
./gradlew :core:testDebugUnitTest --tests 'com.jarvis.core.voice.*' :app:assembleDebug
→ BUILD SUCCESSFUL
```

---

## 22. Known limitations

1. **Android Speech** steals the mic → AudioCapture pause/resume; brief KWS gap during cloud command.  
2. **Whisper progressive** is near-real-time (windowed HTTP), not WebSocket Realtime deltas.  
3. **Nara chat key alone** does not enable Whisper; set a Whisper-capable base URL or use Android Speech.  
4. **AUTO** keeps local for latency; user must choose **Cloud preferred** for cloud.  
5. Full device latency/quality/battery matrix not executed in this agent session if no device.  
6. Confidence remains `UNKNOWN` unless a future provider supplies it (never invented).  
7. Early semantic on PARTIAL not enabled (safety: no execution from partials).

---

## 23. What remains for V2-09 Cloud TTS

- Replace `CloudTtsStub` with a real cloud TTS backend  
- `TtsRouter` local + cloud + fallback (mirror ASR patterns, keep separate from ASR/AIRouter)  
- Streaming TTS if provider supports it; integrate with V2-07 sentence queue  
- Voice profiles (legal), settings, telemetry (`ttsProvider` / first audio)  
- **Do not** start V2-09 from this report automatically  

---

## Architecture (delivered)

```text
Microphone
   ↓
Wake / KWS          ← unchanged
   ↓
VAD / endpoint      ← Sherpa endpoint / SpeechRecognizer / Whisper finalize
   ↓
StreamingAsrRouter
   ├── Cloud (Android Speech | Whisper)
   ├── Local (Sherpa)
   └── HybridAsrController fallback
   ↓
PARTIAL / FINAL
   ↓
Semantic (FINAL only) → RouteDecision → V2-07 stream → TTS
```

**V2-08 stop rule:** report complete — **do not proceed to V2-09.**
