# Jarvis V2-07 — Real Streaming Voice Report

**Date:** 2026-10-01  
**Repository:** `/mnt/D/Jarvis_for_android`  
**Inputs:** V2-00…V2-06 reports, `Jarvis_Roadmap_V2.md`  
**Status:** COMPLETE — stop before V2-08

---

## 1. Existing streaming architecture (audit)

| Component | Pre-V2-07 status |
|-----------|------------------|
| `StreamSession` (Stage 23) | Library-only contracts: cancel, barge-in events, phases — **not on live CHAT path** |
| `CloudLLMProvider` | `supportsStreaming=false`; blocking `chatCompletion` |
| `OpenAiCompatibleCloudClient` | Full JSON POST only |
| `LocalLLMProvider` | Non-streaming |
| `AIRouter` | Had `streamingRequired` filter but no live stream API |
| `DialogueResponder` / `RoutedChatLlmEngine` | Wait for full `String` then TTS |
| `JarvisSpeaker` | Full utterance or post-hoc sentence split of **completed** text |
| `VoiceService` | Brain complete → speak full reply |
| V2-06 | Honest `TtftKind.NOT_AVAILABLE` for non-stream; generation gate |

**Where the stream stopped:** after HTTP body fully downloaded — no incremental deltas.

---

## 2. What was already implemented before V2-07

- Cancellation / generation gate (V2-06)
- Latency marks + first-audio telemetry (V2-06)
- AIRouter capability ranking for `streamingRequired` (V2-03)
- `StreamSession` skeleton (Stage 23)
- TTS `playContinuous` for long **finished** text

---

## 3. Streaming audit conclusion

1. **Real:** none on live LLM path.  
2. **Fake risk:** splitting a finished string — **explicitly forbidden**; not used as “streaming”.  
3. **Gap:** OpenAI-compatible SSE not implemented.  
4. **Owner:** `ChatStreamPipeline` + `VoiceStreamSession` (extends Stage 23 concepts) owned by brain; TTS queue owned by `JarvisSpeaker`; cancel via `InteractionGenerationGate` + pipeline `cancelActive`.

---

## 4. AI streaming contract

```text
AIStreamEventType: START | TEXT | METADATA | ERROR | COMPLETE | CANCELLED
AIStreamListener / AIStreamHandle
StreamingAIProvider.stream(request, listener, cancelFlag)
```

Provider-agnostic — not Nara-specific.

---

## 5. Provider streaming implementation

`OpenAiCompatibleCloudClient.chatCompletionStream`:

- Sends `"stream":true`
- Reads SSE `data:` lines incrementally
- Invokes `onDelta` **as each server chunk arrives**
- Parses `choices[0].delta.content`

`CloudLLMProvider`:

- Implements `StreamingAIProvider`
- `supportsStreaming=true` when client configured
- Measures **real TTFT** on first TEXT delta (`TtftKind.REAL`)
- Cancel via shared `AtomicBoolean` during read loop

Local remains `supportsStreaming=false` (no fake stream).

---

## 6. AIRouter integration

- `AiTaskProfile.forChat`: `streamingRequired = preferCloud && online && privacy allows`
- `ChatStreamPipeline` selects with `streamingRequired=true`
- If no streaming provider → **non-stream fallback** (`streamed=false`, `fallbackUsed=true`)
- Fallback after **partial speech** does **not** restart full answer (no duplicate intro)

---

## 7. Stream state machine

`VoiceStreamState`:

`IDLE → STARTING → STREAMING → DRAINING → COMPLETED`  
(+ `FALLBACK`, `CANCELLING → CANCELLED`, `FAILED`)

Impossible transitions rejected. Completion after cancel blocked.

Uses existing `StreamSession` + `CancellationToken` underneath (no StreamSessionV2).

---

## 8. Chunk segmentation

`SentenceSegmenter`:

- Buffers LLM deltas
- Flushes on `.!?…` / soft `,;:` after `softMaxChars` / hard break at `hardMaxChars`
- Avoids one-token TTS spam (`minFlushChars`)
- Russian punctuation supported
- Final flush for trailing text

---

## 9. TTS integration

`JarvisSpeaker`:

- `beginStreamSpeak` / `enqueueSpeak` / `endStreamSpeak`
- Sequential queue of sentence PCM (local TTS per chunk)
- First-audio callback once per stream generation
- Cancel clears queue + stops player

Not claiming audio-native cloud TTS streaming (V2-09).

---

## 10. Audio queue

Ordered `ArrayDeque` of utterances; pump on TTS thread; generation id invalidates stale chunks.

---

## 11. Backpressure

`BoundedTextChunkQueue` (max chunks / chars). On overflow, drops oldest pending enqueue slot before accepting newer (spoken audio not revoked).

---

## 12. Cancellation

- Wake / new request → `generationGate.cancelCurrent()` + `cancelActiveChatStream()` + `stopSpeaking()`
- Stream `cancelFlag` aborts SSE read
- Stale generation skips further `onSentence` / speak

---

## 13. Barge-in

Wake path cancels stream + TTS (same gate as V2-06). User can interrupt mid-stream.

---

## 14. Error / recovery

| Case | Policy |
|------|--------|
| Stream fail before any text | Non-stream `complete()` fallback; TTS once |
| Stream fail after partial TTS | Stop; **no** full restart (no duplicate) |
| Cancel | Partial marked cancelled; no late TTS |
| Local-only / offline | Streaming attempt skipped; classic path |

---

## 15. Fallback

```text
streamRequested → no StreamingAIProvider
  → FALLBACK complete()
  → segment finished text for TTS only
  → streamed=false, streamFallback=true
```

---

## 16. Structured output interaction

Streaming used **only for CHAT text**.  
TOOL_REQUEST / PLAN / SEMANTIC still require complete + V2-05 validation before any execution.  
No partial JSON execution.

---

## 17. Voice UI state

Existing `AssistantState` THINKING → SPEAKING / READY.  
Stream path sets THINKING then speaks chunks (SPEAKING via speak events).  
Technical states live in `VoiceStreamState` / Trace (`streamUsed`, cancel, fallback).

---

## 18. Conversation state

Assistant message committed **once** with full streamed text (or cancelled partial with `…`).  
Not every delta written to history/memory.

---

## 19. Latency before / after

| Metric | Before V2-07 | After (streaming CHAT) |
|--------|--------------|-------------------------|
| User hears start | After full LLM (~4.5–8s cloud) + TTS | After first sentence segment (TTFT + segment + TTS) |
| `ttftKind` | `NOT_AVAILABLE` | `REAL` when stream succeeds |
| `firstAudio` | ≈ completion + TTS | First chunk audio; completion continues in parallel |

Absolute device numbers: collect via matrix §20 (`Jarvis.Latency` / Trace `streamUsed`, `ttft`, `firstSent`, `audio`).

---

## 20. Device benchmark matrix

| ID | Scenario | Record |
|----|----------|--------|
| A | Short cloud CHAT | TTFT, firstSentence, firstAudio, complete |
| B | Long cloud CHAT | same + sentence count |
| C | Multi-sentence | chunk order |
| D | Cancel mid-stream | cancelled, no late audio |
| E | Barge-in (wake) | cancel + new turn |
| F | Network fail mid-stream | partial policy |
| G | No stream capability | fallback, streamed=false |
| H | Offline local | classic path |

---

## 21. Tests

- `SentenceSegmenterTest`, `VoiceStreamStateMachineTest`, `BoundedTextChunkQueue`
- `CloudStreamingTest`: SSE parse, incremental deltas, REAL metadata, fallback, cancel
- Full `:core:testDebugUnitTest`, `:brain:testDebugUnitTest`

**BUILD SUCCESSFUL.**

---

## 22. Build

`:app:assembleDebug` OK.

---

## 23. Known limitations

1. Cloud SSE depends on gateway honoring `stream:true` (OpenAI-compatible; Nara expected — verify on device).
2. Local LLM still non-streaming.
3. TTS is chunk-synth, not sample-level stream.
4. ASR→LLM still waits for final ASR (no partial understanding).
5. Speculative ack (V2-06) may still fire if first sentence delayed.
6. Device matrix numbers not collected in CI.

---

## 24. What remains for V2-08

**V2-08 — Cloud ASR** (`Jarvis_Roadmap_V2.md` §11):

- Real cloud ASR provider(s) behind `AsrRouter`
- Prefer streaming ASR
- Keep local ASR fallback
- Do not weaken V2-07 LLM→TTS streaming

**Stop rule:** V2-08 not started here.

---

## Appendix: key files

| Path | Role |
|------|------|
| `brain/.../AiStreamModels.kt` | Stream contract |
| `brain/.../OpenAiCompatibleCloudClient.kt` | Real SSE |
| `brain/.../CloudLLMProvider.kt` | StreamingAIProvider |
| `brain/.../ChatStreamPipeline.kt` | State + segment + fallback |
| `core/.../streaming/SentenceSegmenter.kt` | Chunker + VoiceStreamSession |
| `brain/.../AssistantOrchestrator.kt` | `processStreamingChat` |
| `app/.../VoiceService.kt` | Live stream → TTS queue |
| `app/.../JarvisSpeaker.kt` | Sentence queue playback |
