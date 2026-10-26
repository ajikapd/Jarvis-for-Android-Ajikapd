# Jarvis V2-03 — Hybrid AI Router V2 Report

**Date:** 2026-10-01  
**Repository:** `/mnt/D/Jarvis_for_android`  
**Inputs:** `JARVIS_V2_FULL_AUDIT.md`, `JARVIS_V2_01_ARCHITECTURE_HARDENING_REPORT.md`, `JARVIS_V2_02_SEMANTIC_UNDERSTANDING_REPORT.md`, `Jarvis_Roadmap_V2.md`  
**Status:** COMPLETE — stop before V2-04

---

## 1. Current AIRouter architecture (after V2-03)

```text
SemanticRouteSelector → FAST_SKILL | TEMPLATE | CHAT | PLAN | CLARIFY
                              │
                    (only CHAT / structured / planning AI)
                              ▼
                     AiTaskProfile
                              ▼
                         AIRouter
            ┌─────────────────┼─────────────────┐
            │                 │                 │
     capability/health   latency/quality    privacy/offline
            │                 │                 │
            └────────► Provider + Model ◄───────┘
                              │
                         complete()
                              │
                    fallback (privacy-aware)
                              │
                      Jarvis.AIRouter / Trace
```

Providers remain:

| Provider | ID | Role |
|----------|-----|------|
| Local LLM | `local.llm` | Offline / privacy / structured-fast |
| Cloud (Nara OpenAI-compatible) | `cloud.llm` | Online chat / quality / reasoning |

Nara is **one candidate**, not a hardcoded router dependency.

Semantic route vs AI provider route stay separate (V2-02 vs V2-03).

---

## 2. Problems found (pre V2-03)

1. Routing was mostly `preferCloud + online/offline` — not task-profile aware.
2. Telemetry often showed `provider=chat_llm model=-`.
3. No provider health cooldown / failure memory.
4. No latency / aggregate route metrics.
5. Structured semantic fallback always hit raw `ChatLlmEngine`, not AIRouter policy.
6. Fallback did not systematically record attempts / reasons.
7. Model ID (Nara fast vs quality) was settings-only, not quality-requirement aware.

---

## 3. New task profile

`AiTaskProfile` (`brain/.../aiprovider/AiTaskProfile.kt`):

| Field | Purpose |
|-------|---------|
| `taskType` | CHAT / PLANNING / SEMANTIC_INTERPRETATION / STRUCTURED / GENERAL |
| `routeHint` | Semantic route name for telemetry only |
| `complexity` | SIMPLE / COMPLEX |
| `reasoningRequired` | Prefer stronger models |
| `privacyLevel` | NORMAL / SENSITIVE / LOCAL_REQUIRED |
| `contextSizeTokens` | Soft capability filter |
| `streamingRequired` | Prefer streaming-capable providers |
| `latencySensitivity` | LOW / NORMAL / HIGH |
| `qualityRequirement` | FAST / BALANCED / QUALITY / REASONING |
| `preferCloud` | User preference (not absolute) |
| `isOnline` | Connectivity |
| `structuredOutputRequired` | Classification / structured tasks |
| `fallbackAllowed` | Whether alternate providers allowed |

Helpers: `forChat`, `forSemanticInterpretation`, `forPlanning`, `fromLegacy(AIRouterContext)`.

---

## 4. Provider / model metadata

Extended `AIProviderCapabilities`:

- `defaultModelId`, `qualityTier`, `speedTier`, `privacyClass`
- `toModelDescriptor(...)`

`ModelDescriptor` carries provider, modelId, tiers, context, streaming, structured, onlineOnly, privacy.

| Provider | default model | quality | speed | privacy |
|----------|---------------|---------|-------|---------|
| Local | GGUF filename via `ModelNamed` | FAST | FAST | LOCAL_REQUIRED |
| Cloud (Nara) | `agnes-2.5-flash` | QUALITY | FAST | NORMAL |

Router resolves cloud model:

- FAST/BALANCED → `NaraRouterDefaults.MODEL_FAST` (`agnes-2.5-flash`)
- QUALITY/REASONING → `MODEL_QUALITY` (`mistral-medium-3-5`)

`AICompletionRequest.preferredModelId` / `taskType` pass the choice into providers.  
`AICompletionResult` now includes `modelId`, `ttftMs`, optional token fields.

---

## 5. Routing policy

Deterministic order in `AIRouter.select(profile)`:

1. Explicit preferred provider (privacy can still block cloud)
2. **Privacy LOCAL_REQUIRED / SENSITIVE** → local only
3. **Offline** → local only
4. **Streaming required** → streaming-capable candidate
5. **SEMANTIC_INTERPRETATION / structured** → local fast (unless preferCloud + local missing)
6. **Latency priority** (when `!preferCloud` + HIGH + FAST) → fastest suitable
7. **Reasoning / QUALITY / PLANNING / COMPLEX** → strongest suitable cloud when allowed
8. **preferCloud** → healthy cloud, else local fallback
9. Default local / any available

`preferCloud=true` never overrides offline or LOCAL_REQUIRED privacy.

---

## 6. Health handling

`ProviderHealthTracker`:

| Status | Meaning |
|--------|---------|
| AVAILABLE | Healthy |
| DEGRADED | Recent failures / rate limit |
| UNAVAILABLE | In cooldown after repeated failures / auth |
| UNKNOWN | No samples |

Bounded cooldown (~30s, longer for auth). Cooldown expiry → DEGRADED retry, **not permanent ban**.

Quota enum: `AVAILABLE | NEAR_LIMIT | RATE_LIMITED | EXHAUSTED | UNKNOWN`  
Unknown quota ≠ exhausted.

---

## 7. Fallback

`AIRouter.complete(profile, request)`:

```text
select → execute → on failure record health
  → if fallbackAllowed && privacy allows
       → next candidate (never retried same without reason)
  → max 3 attempts
```

Privacy `LOCAL_REQUIRED` **never** falls through to cloud.

Each attempt logged: `attempt / provider / model / reason / latency / error` (`lastAttempts`).

---

## 8. Latency-aware routing

`ProviderMetricsStore`:

- Ring-buffer recent latencies
- Moving average, failure rate, TTFT average
- `speedScore` for ranking
- Aggregate: cloud/local/fallback %, avg completion, avg TTFT

Latency-sensitive profiles prefer faster measured backends when cloud is not forced.

---

## 9. Quality-aware routing

| Requirement | Typical selection |
|-------------|-------------------|
| FAST | Local or fast cloud |
| BALANCED | preferCloud → cloud fast model; else local |
| QUALITY / REASONING | Cloud quality model when online + allowed |
| PLANNING | Same as reasoning profile |

Does not always pick “premium” — availability, health, privacy, preferCloud still apply.

---

## 10. Structured semantic routing

`LlmStructuredSemanticInterpreter` now uses AIRouter with:

```text
taskType = SEMANTIC_INTERPRETATION
structuredOutputRequired = true
qualityRequirement = FAST
latencySensitivity = HIGH
preferCloud = false (BrainGraph wiring)
```

Device evidence:

```text
Jarvis.AIRouter: task=SEMANTIC_INTERPRETATION provider=local.llm
  model=Qwen3-0.6B-Q4_K_M.gguf reason=STRUCTURED_LOCAL
```

---

## 11. Nara integration state

- Still via `OpenAiCompatibleCloudClient` + `CloudLLMProvider`
- Defaults: base `https://router.bynara.id/v1`, models `agnes-2.5-flash` / `mistral-medium-3-5`
- Streaming: **not claimed** (`supportsStreaming=false`) — honest for V2-03
- Errors mapped to NETWORK / UNAVAILABLE / TIMEOUT (429)
- Architecture ready for additional cloud providers without rewrite

---

## 12. Telemetry

### `Jarvis.AIRouter`

```text
req=… task=CHAT route=CHAT provider=cloud.llm model=agnes-2.5-flash
reason=ONLINE_SIMPLE_CLOUD_PREF fallback=false health=… candidates=…
latency=…ms ttft=… streamReq=false streamUsed=false status=SUCCESS attempt=1
```

### `Jarvis.Trace` (extended)

```text
provider=… model=… sel=… fallback=… aiLat=… ttft=…
```

No API keys / secrets logged.

`RoutedChatLlmEngine` exposes `lastProviderId / lastModelId / lastSelectionReason / lastFallback / lastLatencyMs / lastTtftMs` → `DialogueResult` → Orchestrator trace.

---

## 13. Routing experiments (controlled)

| Test | Path | Provider | Model | Reason | Latency (obs.) |
|------|------|----------|-------|--------|----------------|
| A ordinary chat (preferCloud) | CHAT | cloud.llm | agnes-2.5-flash | ONLINE_SIMPLE_CLOUD_PREF | ~4.5–8s |
| A' preferCloud=false | CHAT | local.llm | Qwen3-0.6B… | LATENCY_PRIORITY | ~5.7s |
| B reasoning profile (unit) | PLANNING | cloud | mistral-medium-3-5 | ONLINE_QUALITY | n/a (unit) |
| C short/simple local | CHAT | local | local GGUF | latency/local | unit + device |
| D structured semantic | SEMANTIC | local.llm | Qwen3-0.6B… | STRUCTURED_LOCAL | ~10–26s local gen |

Quality observation: cloud replies longer/richer; local structured is slower on 0.6B but policy-correct (no expensive cloud for classification).

---

## 14. Device results

| Scenario | Result |
|----------|--------|
| Online CHAT preferCloud | `provider=cloud.llm model=agnes-2.5-flash sel=ONLINE_SIMPLE_CLOUD_PREF` |
| preferCloud=false CHAT | `provider=local.llm model=Qwen3-0.6B-Q4_K_M.gguf` |
| Structured semantic | `STRUCTURED_LOCAL` on local |
| FAST_SKILL flashlight | No AI provider; semantic FAST_SKILL intact |
| TEMPLATE greeting | No AI |
| CLARIFY «открой его» (fresh) | `unresolved_pronoun`, no AI |
| Debug settings | `DEBUG_SETTINGS --es preferCloud true\|false` |

---

## 15. Test results

```bash
./gradlew :core:testDebugUnitTest
./gradlew :brain:testDebugUnitTest --tests 'com.jarvis.brain.aiprovider.*'
./gradlew :app:assembleDebug
```

**All green.** New suite: `AIRouterPolicyTest` (offline, privacy, preferCloud, fallback, semantic local, reasoning cloud, health cooldown, latency rank, streaming capable, aggregates, legacy context).

Legacy `AIRouterTest` / `CloudLLMProviderNaraTest` / `AIProviderTest` updated where reason codes gained `ONLINE_QUALITY`.

---

## 16. Build result

`:app:assembleDebug` — **SUCCESS**  
Device install — **Success**

---

## 17. Remaining limitations

1. Real TTFT for non-streaming ≈ completion latency (honest approximation).
2. Cloud streaming not implemented — capability flagged false (TTS streaming = V2-06/07).
3. Token counts usually unset (API parse not expanded).
4. Only two real providers (Nara + Local) — registry ready for more.
5. Local 0.6B structured semantic can be slow on-device.
6. Quota NEAR_LIMIT/EXHAUSTED depends on future provider signals (429 → RATE_LIMITED today).
7. After some installs `cloudConfigured` may need key re-check in settings.

---

## 18. What moves to V2-04

Per roadmap (Memory / context unification and later stages):

- Richer memory inject into AI context (not router rewrite)
- Stronger live PLAN / agent execution (not V2-03)
- Optional additional cloud providers
- True streaming + TTFT from SSE when enabled
- Cost accounting if providers expose usage

---

## 19. New architectural concerns

1. Keep **SemanticRouteSelector** and **AIRouter** strictly layered — do not merge.
2. Health cooldown must stay bounded — never permanent cloud death after one blip.
3. Structured semantic should stay on FAST/local by default to protect latency and cost.
4. User `preferCloud` is preference only; privacy/offline remain hard constraints.

---

## Key files

| File | Role |
|------|------|
| `aiprovider/AiTaskProfile.kt` | Task profile + helpers |
| `aiprovider/AIRouter.kt` | Policy, complete+fallback, telemetry |
| `aiprovider/ProviderHealthTracker.kt` | Health / cooldown |
| `aiprovider/ProviderMetricsStore.kt` | Latency / aggregates |
| `aiprovider/AIProviderModels.kt` | Capabilities / request / result extensions |
| `aiprovider/RoutedChatLlmEngine.kt` | CHAT via router + last* telemetry |
| `aiprovider/CloudLLMProvider.kt` / `LocalLLMProvider.kt` | Model metadata |
| `semantic/LlmStructuredSemanticInterpreter.kt` | Router-backed structured |
| `core/.../RequestTrace.kt` | sel / aiLat / ttft |
| `app/BrainGraph.kt` | Wiring |
| `app/DebugCommandReceiver.kt` | `DEBUG_SETTINGS` preferCloud |

---

## Stop rule

**V2-03 complete. Do not auto-start V2-04.**

Main outcome:

> Jarvis chooses cloud vs local (and which model) from an explicit task profile — with health, latency, quality, privacy, structured-vs-chat policy, deterministic fallback, and real provider/model telemetry — without collapsing semantic routing into AI provider routing.
