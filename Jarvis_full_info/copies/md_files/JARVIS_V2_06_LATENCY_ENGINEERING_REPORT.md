# Jarvis V2-06 — Latency Engineering Report

**Date:** 2026-10-01  
**Repository:** `/mnt/D/Jarvis_for_android`  
**Inputs:** V2-00…V2-05 reports, `Jarvis_Roadmap_V2.md`  
**Status:** COMPLETE — stop before V2-07

---

## 1. Baseline latency (from prior evidence)

| Path | Prior observation | Source |
|------|-------------------|--------|
| FAST_SKILL / TEMPLATE | Hundreds of ms (no LLM) | V2-02 / audit |
| Cloud CHAT | ~4.5–8 s completion | V2-03 |
| Local CHAT | ~5.7 s | V2-03 |
| Local semantic fallback (Qwen 0.6B) | ~10–26 s | V2-03 |
| Non-streaming “TTFT” | ≈ completion (misleading) | V2-03 CloudLLMProvider |
| Voice first-audio | Logged in VoiceService as `e2e first-audio` | Pre-existing |

**Baseline rule applied:** measure → classify → change → re-measure. Device matrix slots are instrumented for on-device collection; CI validates instrumentation correctness, not absolute ms targets.

---

## 2. Instrumentation

New / extended:

| Component | Role |
|-----------|------|
| `InteractionLatency` | Per-request mark bag (ASR…TTS) |
| `TtftKind` | `REAL` / `ESTIMATED` / `NOT_AVAILABLE` |
| `LatencyClock` | Device `elapsedRealtime` with JVM fallback |
| `LatencyStatsStore` | In-memory p50/p90/p95 by route |
| `RequestTrace` V2-06 fields | User-visible + stage ms |
| `Jarvis.Latency` log | Dedicated summary line |
| Diagnostics notes | Last interaction + percentiles |

---

## 3. Full timing pipeline

```text
wakeDetected → asrStart → asrPartial → asrFinal
 → normalize → semantic → route
 → context → aiRoute → llmRequest → (firstByte/token if streaming) → llmComplete
 → structuredParse → planner → tool → verification
 → ttsStart → ttsFirstAudio → ttsComplete → interactionComplete
```

User-visible aggregates:

| Metric | Meaning |
|--------|---------|
| `firstAckMs` | Speculative “Секунду…” (optional) |
| `firstUsefulMs` | Brain reply ready |
| `firstAudioMs` | First TTS sample heard |
| `completeMs` / `e2eMs` | Interaction end / best e2e proxy |

---

## 4. Route-specific latency (instrumentation focus)

| Route | Spans expected dominant |
|-------|-------------------------|
| FAST_SKILL | ASR + tool + verify + TTS |
| TEMPLATE | ASR + template + TTS |
| CHAT | Context + **LLM completion** + TTS |
| PLAN | Plan + tools + verify + TTS |
| CLARIFY | Semantic + short TTS |

Semantic LLM fallback remains possible only when FastSemantic is weak (`needsLlmSemanticFallback`); clear FAST_SKILL/TEMPLATE/CLARIFY/PLAN do **not** start LLM interpretation (V2-02 invariant preserved).

---

## 5. ASR findings

- ASR continues to own mic in `VoiceEngine`; Wake/KWS unchanged.
- `setProcessingBusy(true)` pauses ASR during brain/TTS CPU contention (pre-existing).
- V2-06: busy clears on **first audio**, not end of utterance (kept).
- Partial→early semantic: **not** enabled (correctness risk); deferred to V2-07 with streaming contracts.

---

## 6. Semantic findings

- Fast path marks `semanticMs` / `routeMs` on every turn.
- Expensive local semantic LLM (10–26 s) is still a **policy/product** issue: count/frequency must be measured on device via `llmRequired` / `llmInterp` flags before changing V2-03 privacy routing.
- Instrumentation now makes that path visible without changing selector policy.

---

## 7. Context findings

- `respondChat` marks `contextStart/End`; prefers `UnifiedContextBuilder` `buildMs` when present.
- No context quality removed; filtering remains V2-04 relevance layer.
- SQLite/memory cost appears in `contextMs` + `Jarvis.LlmContext` (V2-04).

---

## 8. AIRouter findings

- Router selection remains deterministic; no rewrite.
- Provider completion latency recorded as `aiCompletionMs` / `aiLatencyMs`.
- Fake TTFT removed (see §11).

---

## 9. Network findings

- Cloud path is non-streaming HTTP completion.
- Available metrics: request→complete wall time.
- DNS/TLS/TTFB breakdown **not** exposed by current OpenAI-compatible client → marked unavailable (honest).

---

## 10. Local LLM findings

- Non-streaming; `ttftKind=NOT_AVAILABLE`.
- Cold vs warm: warm via existing `BrainGraph.warmChatLlm` / `warmLlmBrain`; `WarmState` tagged when provider is local.
- Keep cold/warm separate in device matrix (do not average).

---

## 11. Cloud LLM findings

**Bug fixed:** `CloudLLMProvider` previously set `ttftMs = latency` (completion masquerading as TTFT).

**After V2-06:**

```text
streamed=false → ttftMs=null, ttftKind=NOT_AVAILABLE
aiCompletionMs = full generation latency
```

Same for `LocalLLMProvider`. Real TTFT reserved for future streaming (V2-07).

---

## 12. TTS findings

- `JarvisSpeaker` already reports `tts first-audio` via `onFirstAudio`.
- VoiceService writes `ttsStartupMs`, `firstAudioMs`, `e2e` into trace after speak.
- No full TTS rewrite; first-audio remains the primary voice UX metric.

---

## 13. Audio findings

- Duplicate conversion / AudioTrack internals not changed (no evidence of P0 regression beyond existing design).
- Question “text-ready → first audible” = `ttsStartupMs` (+ optional queue).

---

## 14. Cancellation / concurrency findings

| Mechanism | Behavior |
|-----------|----------|
| `InteractionGenerationGate` | New request / wake increments generation |
| Stale brain result | Skips speak / state updates |
| `VoiceController.stopSpeaking()` | Cancels in-flight TTS |
| Wake | Cancels generation + stops TTS + cancels ack job |
| AI `cancelRequested` | Already supported on providers (pre-existing) |

Prevents old CHAT from talking over a new turn.

---

## 15. Cold / warm comparison

| Component | Warm strategy |
|-----------|---------------|
| ASR | Engine start (service lifecycle) |
| Local LLM | `warmChatLlm` / `warmLlmBrain` on voice start |
| TTS | Synth warm-up “ок” in `JarvisSpeaker` init |
| Semantic | Fast classifier always “hot” (no model) |

Device matrix must split cold vs warm samples; `coldStart` / `warmState` fields on trace.

---

## 16. Bottlenecks (evidence-ranked)

### P0 (fixed / gated)

1. **Fake TTFT** — fixed.
2. **Stale request TTS race** — generation gate + stopSpeaking.
3. **Missing structured latency breakdown** — instrumented.

### P1 (measured; optimize carefully)

1. **Non-streaming cloud/local LLM wait** before any audio (dominant for CHAT).
2. **Local semantic LLM fallback** 10–26 s when triggered.
3. **TTS startup** after reply ready.
4. **Context build** (usually small vs LLM; watch SQLite).

### P2 / P3

- Allocations, logging — not primary.

**Streaming is deferred to V2-07** (no fake token drip).

---

## 17. Changes made

1. `InteractionLatency`, `TtftKind`, `LatencyClock`, `LatencyStatsStore`, `ImmediateAckPolicy`, `InteractionGenerationGate`, `StreamReadiness`.
2. `RequestTrace` + `Jarvis.Trace` / `Jarvis.Latency` fields.
3. Honest TTFT on Cloud/Local providers + RoutedChatLlmEngine / DialogueResult.
4. Orchestrator timing marks (normalize, semantic, context, LLM).
5. VoiceService: cancel/stale gate, speculative ack (“Секунду…” after 700 ms for heavy CHAT only), first-audio into trace.
6. Diagnostics latency summary lines.
7. Unit tests for TTFT honesty, gate, ack policy, percentiles.

---

## 18. Before / after measurements

| Item | Before | After |
|------|--------|-------|
| Cloud TTFT telemetry | = completion | `NOT_AVAILABLE` + separate `aiCompletionMs` |
| Trace latency | mostly `totalLatencyMs` | ack/useful/audio/stage spans |
| Overlapping voice turns | possible stale speak | cancelled / skipped |
| Slow CHAT silence | full wait mute | optional early ack |
| Absolute CHAT ms | unchanged without streaming | same order; UX ack only |

Device before/after absolute numbers: collect via `LatencyStatsStore` / logcat on hardware (see §19).

---

## 19. Device benchmark matrix

Repeatable prompts (record p50/p90, min/max, first audio, completion; **cold vs warm separate**):

| ID | Prompt | Route |
|----|--------|-------|
| A | «включи фонарик» | FAST_SKILL |
| B | «привет» | TEMPLATE |
| C | «что ты думаешь о космосе?» | CHAT |
| D | complex reasoning | CHAT |
| E | «открой его» | CLARIFY |
| F | multi-step goal | PLAN |
| G | offline CHAT | CHAT local |
| H | ambiguous → LLM semantic | semantic fallback |

Log tags: `Jarvis.Trace`, `Jarvis.Latency`, `Jarvis.VoiceService`.

---

## 20. Quality regression check

- SemanticRouteSelector ≠ AIRouter — unchanged.
- LLM ≠ Executor — unchanged.
- Structured invalid → no execution — V2-05 tests green.
- Context relevance — V2-04 untouched in behavior.
- Ack never claims success.
- FAST_SKILL / TEMPLATE not given speculative ack.

---

## 21. Tests

```bash
./gradlew :core:testDebugUnitTest
./gradlew :brain:testDebugUnitTest
./gradlew :app:assembleDebug
```

New: `core/.../runtime/InteractionLatencyTest.kt`  
**Result:** BUILD SUCCESSFUL.

No flaky absolute-ms asserts.

---

## 22. Build

`:app:assembleDebug` OK (2026-10-01).

---

## 23. Remaining limitations

1. No real LLM/TTS streaming (by design → V2-07).
2. Network stage split (DNS/TLS/TTFB) unavailable from client.
3. ASR mark injection from VoiceEngine partial/final not fully plumbed into every request’s `InteractionLatency` (brain-anchored e2e from VoiceService `t0` covers first-audio; wake/ASR absolute marks optional follow-up).
4. Device matrix numbers not collected in CI.
5. Speculative ack may briefly speak then be interrupted by final reply (acceptable trade-off).
6. Semantic LLM 10–26 s path not auto-disabled (needs frequency evidence + privacy-safe alternative).

---

## 24. Exact requirements carried into V2-07

**V2-07 — Real Streaming Voice** must:

1. Provide **real** LLM token stream → `TtftKind.REAL` + `llmFirstTokenAt`.
2. Sentence/chunk segmentation → early TTS without fake streaming.
3. Wire ASR partial events into interaction latency marks.
4. Full barge-in / backpressure using existing `StreamSession` contracts.
5. Keep V2-06 metrics (`firstAudio`, `aiCompletion`, honest TTFT) as regression gates.
6. Not weaken SafetyGate / structured validation.

**Stop rule:** V2-07 not started in this delivery.

---

## Appendix: key files

| Path | Role |
|------|------|
| `core/.../runtime/InteractionLatency.kt` | Marks + TTFT honesty |
| `core/.../runtime/LatencyStatsStore.kt` | p50/p90 store |
| `core/.../runtime/LatencyUx.kt` | Ack + generation gate |
| `core/.../runtime/LatencyClock.kt` | Test-safe clock |
| `core/.../runtime/RequestTrace.kt` | Trace fields |
| `brain/.../CloudLLMProvider.kt` / `LocalLLMProvider.kt` | No fake TTFT |
| `brain/.../AssistantOrchestrator.kt` | Stage marks |
| `app/.../VoiceService.kt` | Cancel + ack + first audio |
| `app/.../VoiceEngine.kt` | `stopSpeaking` |
