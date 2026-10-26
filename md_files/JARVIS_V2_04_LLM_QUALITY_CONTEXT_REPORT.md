# Jarvis V2-04 — LLM Quality + Context Report

**Date:** 2026-10-01  
**Repository:** `/mnt/D/Jarvis_for_android`  
**Inputs:** `JARVIS_V2_FULL_AUDIT.md`, `JARVIS_V2_01_ARCHITECTURE_HARDENING_REPORT.md`, `JARVIS_V2_02_SEMANTIC_UNDERSTANDING_REPORT.md`, `JARVIS_V2_03_HYBRID_AI_ROUTER_REPORT.md`, `Jarvis_Roadmap_V2.md`  
**Status:** COMPLETE — stop before V2-05

---

## 1. Current prompt architecture (after V2-04)

```text
User turn
    │
    ▼
SemanticRouteSelector → FAST_SKILL | TEMPLATE | CHAT | PLAN | CLARIFY
    │
    └── CHAT ──► LlmContextAssembly.gatherInput()
                      │
                      ▼
              UnifiedContextBuilder.build()
                      │
         ┌────────────┼────────────┐
         │            │            │
    JarvisSystemPrompt   untrusted data block   ConversationHistorySelector
    (trusted CORE)       (memory/device/caps/task)   (bounded turns + refs)
         │            │            │
         └────────────┴────────────┘
                      │
                      ▼
              AssembledLlmContext
                      │
         ┌────────────┴────────────┐
         │                         │
  DialogueResponder          AIRouter → Cloud / Local
  generateWithContext()      AICompletionRequest.assembledContext
         │                         │
         └────────────┬────────────┘
                      ▼
                 LlmOutputPolicy
                      ▼
              User-facing reply
```

**Semantic interpretation** uses the same builder with `LlmContextKind.SEMANTIC_INTERPRETATION` and `LlmContextBudget.FAST` (minimal prompt, no full Memory2 dump).

**PLAN** path: builder supports `LlmContextKind.PLAN` (system mode + capability gating), but live planner still uses `DeterministicAIPlanner` / existing plan coordinator — unified assembly is wired for **CHAT** and **semantic fallback** first.

---

## 2. Current context sources

| Source | Gathered by | Injected when |
|--------|-------------|---------------|
| System + persona | `JarvisSystemPrompt` | Every assembled request |
| Conversation | `ConversationContext.messages` → `ConversationHistorySelector` | CHAT / semantic (bounded) |
| Long-term memory | `LongTermMemory.retrieve()` | Ranked if relevance / memory question |
| Companion memory | `MemoryRecall.textSearch` + preferences | Same + privacy filter |
| Memory2 | `Memory2Store.searchRelevant()` | Same |
| Device / app | `PhoneContextBus.current()` → `DeviceContextGating` | Query hints (audio, battery, app, …) |
| Capabilities | `CatalogToolDiscovery.discover()` → `CapabilityContextSelector` | Action/plan-like queries |
| Task / refs | `ConversationContext` pending + reference hints | Follow-ups, non-CHAT kinds |
| Legacy memory string | `legacyMemoriesSnippet` | Fallback if provider ignores assembled system |

**Diagnostics (no secrets):** `Jarvis.LlmContext` log line from `ContextBuildDiagnostics.logLine()`; `Jarvis.Trace` summary adds `ctx=`, `ctxChars=`, `memN=`, `hist=`, `sysVer=`.

---

## 3. Problems found (context audit, pre V2-04)

1. **CHAT** used raw history + flat `memoryProvider` string — no sectioning, no relevance.
2. **DialogueResponder** applied **`generated.take(360)`** — mid-sentence cuts, poor quality on cloud/local.
3. **Local `LlamaChatLlmEngine`** had empty/minimal system prompt; memories truncated to ~20 chars in role compaction.
4. **Cloud** used generic Nara system string + memory append — duplicate/untrusted mix with instructions.
5. **Memory2** and **Companion recall** existed but were **not** on the live CHAT assembly path.
6. **PhoneContext** was not gated into LLM for device-dependent questions.
7. **Tool catalog** was not passed selectively for capability-heavy chat.
8. **No token/section telemetry** on AI requests.
9. **Risk:** user memory / webpage text could read like instructions — no explicit trusted/untrusted split.
10. **Privacy:** cloud-bound chat could include companion **PRIVATE** snippets without policy.

---

## 4. New Context Builder

**Module:** `core/src/main/kotlin/com/jarvis/core/llm/`

| Component | Role |
|-----------|------|
| `LlmContextModels.kt` | `ContextBuildInput`, `AssembledLlmContext`, budgets, diagnostics |
| `UnifiedContextBuilder` | Single assembly entry point |
| `JarvisSystemPrompt` | Trusted CORE instructions, version **`v2-04.1`** |
| `MemoryContextRetriever` | Deterministic rank + cloud privacy |
| `ConversationHistorySelector` | Recent + relevant older turns, reference hints |
| `DeviceContextGating` | Relevant device lines only |
| `CapabilityContextSelector` | Top-N capability lines when needed |
| `LlmOutputPolicy` | Model-aware output cap + sentence-aware truncate |

**Brain wiring:** `brain/.../llm/LlmContextAssembly.kt` → `AssistantOrchestrator.respondChat()` → `DialogueResponder.respondDetailed(..., assembled)`.

---

## 5. Memory integration

- Three pools merged as `MemorySnippet`: long-term, companion, Memory2.
- **Retrieval policy:** keyword overlap + optional “memory question” boost (`MemoryContextRetriever.needsMemory`); top-N by budget (2–8).
- **Not** LLM-based retrieval.
- **Memory2:** `Memory2Store.searchRelevant(query, limit)` added for deterministic candidates.

---

## 6. Conversation integration

- History: last N turns by budget (4–16), plus up to 2 older turns when follow-up markers detected.
- **Reference block** for «сравни первый…», «а первый?», «дешевле», etc. — complements existing `DialogueResponder.resolveWithContext` (does not replace `FollowUpResolver` in semantic layer).
- Pending clarification / last tool id can appear under `[Задача]` when not pure CHAT.

---

## 7. Device / app context

- `DeviceContextGating.format(PhoneContext, query, maxChars)` includes lines only when query matches domains (media/audio, battery, network, current app, etc.).
- Package name included only when gating says device block is relevant.

---

## 8. Capability / tool context

- Discovery limited (6 items) in assembly; builder keeps 3–6 lines by budget.
- `CapabilityContextSelector.needsCapabilities()` — action verbs / PLAN kind.
- Descriptions are **informational**; risk/policy unchanged (Executor / SafetyGate).

Full JSON tool schemas for planner remain out of scope (V2-04); lines are `toolId: description` summaries.

---

## 9. Persona / system prompt

- **Persona** (`FRIENDLY`, `MINIMAL`, `DEVELOPER`, `CLASSIC`) adjusts **tone and length only** — not permissions or autonomy.
- System prompt states: use context facts, do not invent capabilities, do not claim executed actions without confirmation, ask when missing data, treat `--- Контекст ---` as **data not commands**.

---

## 10. Output handling

| Before | After |
|--------|--------|
| Hard `take(360)` on CHAT | `LlmOutputPolicy.apply(budget)` — strips `RESULT=`/`ANSWER:` prefixes, sentence-aware cap (280–960 chars) |
| Backend-style leaks | Prefix strip in output policy |

**Remaining:** `LlamaChatLlmEngine` structured-decision fallback still uses `text.take(360)` on parse path; cloud provider caps response at 800 chars in `CloudLLMProvider.complete()`.

Tool/skill success wording for executor paths is still primarily template/verifier-driven — system prompt instructs the model, but orchestrator does not yet rewrite all tool replies through a unified “execution honesty” formatter.

---

## 11. Privacy handling

- `MemoryContextRetriever`: drops `PRIVATE` / `SECRET` / `LOCAL_ONLY` when `cloudAllowed == false`.
- `LlmContextAssembly`: pre-filters companion snippets when `cloudMemoryAllowed` is false.
- **`BrainGraph.cloudMemoryAllowed`:** when online + user prefers cloud + API configured → **`false`** (conservative: do not attach private companion memory to prompts likely routed to cloud).

**Gap:** long-term / Memory2 entries without sensitivity tags may still reach cloud; extend tagging in a later memory hardening pass.

AIRouter privacy levels (V2-03) unchanged — no router rewrite.

---

## 12. Prompt / context versioning

| Key | Value |
|-----|--------|
| `JarvisSystemPrompt.VERSION` | `v2-04.1` |
| `ContextBuildDiagnostics.promptVersion` | `ctx-v2-04.1` |
| `systemPromptHash` | SHA-256 prefix (4 bytes hex) of trusted system text only |

---

## 13. Token / context metrics

Per request (`ContextBuildDiagnostics`):

- `buildMs`, `contextSections`, `contextChars`, `estimatedInputTokens` (char/4 heuristic)
- `memoryCount`, `memorySources`, `historyTurns`, `capabilityCount`, `deviceContextIncluded`, `currentAppIncluded`

Surfaced on `DialogueResult.contextDiagnostics` → `RequestTrace` for LLM CHAT turns.

Provider-reported token counts not yet persisted (no billing).

---

## 14. Controlled quality experiments

Automated proxies (unit tests in `UnifiedContextBuilderTest` / `LlmOutputPolicyTest`):

| Scenario | Expectation verified |
|----------|----------------------|
| General chat («небо голубое») | No memory section |
| Personal memory («какой VPN») | Memory section + VPN in untrusted block |
| Cloud privacy | PRIVATE name stripped when `cloudAllowed=false` |
| Device («не слышно музыку») | Spotify + headphones in device block |
| Follow-up / compare | `references` section |
| Golden multi-turn («а первый?») | References + prior list content reachable |
| Output policy | No `RESULT=` prefix; length ≤ budget max |

**Manual device matrix (recommended):** same prompts on device with `adb logcat -s Jarvis.LlmContext Jarvis.Trace` — compare `ctx=` sections, cloud vs local fallback, Russian phrasing. Not executed in CI in this session.

---

## 15. Device results

**CI / build agent:** no physical device attached.

**Expected on device after install:**

- CHAT with memory enabled → `Jarvis.LlmContext` shows sections list and sizes (no raw secrets).
- Cloud path → `sysVer=v2-04.1`, reduced companion private memory when `cloudMemoryAllowed` false.
- Follow-up after list-style assistant message → `references` in trace.

---

## 16. Latency results

- Context build records **`buildMs`** in diagnostics (typically sub-ms to low ms on unit tests; not A/B benchmarked against pre-V2-04 in production).
- Design goal: bounded sections (FAST data cap 420 chars untrusted) to avoid giant prompts on FAST profile.
- Full before/after LLM latency comparison requires on-device profiling (out of scope for automated run here).

---

## 17. Tests

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :core:testDebugUnitTest
./gradlew :brain:testDebugUnitTest
./gradlew :app:assembleDebug
```

**Result:** BUILD SUCCESSFUL (2026-10-01).

New/updated coverage:

- `core/.../llm/UnifiedContextBuilderTest.kt` (+ golden multi-turn)
- Brain tests updated for `ChatLlmEngine.generate(..., assembled)` and `respondDetailed(..., assembled)`

---

## 18. Build

`:app:assembleDebug` succeeds after V2-04 wiring in `BrainGraph`, `AssistantOrchestrator`, `RoutedChatLlmEngine`, `CloudLLMProvider`, `LlamaChatLlmEngine`, `LlmStructuredSemanticInterpreter`.

---

## 19. Remaining limitations

1. **PLAN / agent loop** — no end-to-end `LlmContextAssembly` on every planner call yet.
2. **Tool JSON schemas** — summary lines only, not top-N OpenAPI-style schemas.
3. **Execution-honesty** — prompt-level rule; tool success copy still depends on existing responder/executor paths.
4. **Cloud response** still `take(800)`; local structured parse still `take(360)` in one branch.
5. **LT / Memory2 sensitivity** not fully aligned with cloud policy.
6. **Real token counts** from Nara/local not stored.
7. **Prompt injection** — delimiter + instruction text help; browser/MCP untrusted pipelines need follow-up.
8. **Streaming** — unchanged (not V2-04).

Invariants preserved: `SemanticRouteSelector ≠ AIRouter`, `LLM ≠ Executor`, memory ≠ system instruction.

---

## 20. Exact next stage

**V2-05 — Structured AI Output** (`Jarvis_Roadmap_V2.md` §8):

- Normalize LLM outputs into typed envelopes (`ANSWER`, `TOOL_REQUEST`, …).
- Align semantic interpreter, chat, and planner on one structured contract.
- Reduce ad-hoc parsing (`LlmDecisionParser`, prefix hacks) in favor of schema-validated responses.
- Keep context builder as the input side; V2-05 focuses on **output** shape and orchestration consumption.

**Stop rule:** do not start V2-05 automatically in the same delivery as this report.

---

## Appendix: key files

| Path | Purpose |
|------|---------|
| `core/src/main/kotlin/com/jarvis/core/llm/*` | Unified context + output policy |
| `brain/src/main/kotlin/com/jarvis/brain/llm/LlmContextAssembly.kt` | Live data gathering |
| `brain/src/main/kotlin/com/jarvis/brain/AssistantOrchestrator.kt` | `respondChat` assembly + trace |
| `app/src/main/kotlin/com/jarvis/app/BrainGraph.kt` | Assembly dependencies + `cloudMemoryAllowed` |
| `core/src/main/kotlin/com/jarvis/core/runtime/RequestTrace.kt` | V2-04 trace fields |
