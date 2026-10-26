# Jarvis V2-01 — Architecture Hardening Report

**Date:** 2026-10-01  
**Repository:** `/mnt/D/Jarvis_for_android`  
**Inputs:** `JARVIS_V2_FULL_AUDIT.md`, `Jarvis_Roadmap_V2.md`  
**Status:** COMPLETE — stop before V2-02

---

## 1. Architecture before V2-01

```text
VoiceService → AssistantOrchestrator.process()
  → SkillRetriever / BrainRouter
  → RiskBasedPolicy.decide() → ToolExecutor.execute()   // live skills
  OR DialogueResponder → RoutedChatLlmEngine → AIRouter // chat

Parallel (constructed in BrainGraph, mostly unused on process()):
  DeterministicAIPlanner, AgentExecutionLoop, Memory2,
  VisionRouter, ProactiveAgent, AsrRouter/TtsRouter stubs,
  FilesAgent, BrowserAgent, …
```

Diagnostics reported `planner=on` / `agentLoop=on` meaning **constructed**, not **active on live path**.

---

## 2. Architectural defect (from V2-00)

1. **Dual safety stacks:** live `RiskBasedPolicy → Executor` vs agent `Risk→Policy→Gate→Executor`.  
2. **Feature flags lied:** constructed ≠ active.  
3. **No request-level route/telemetry.**  
4. **Planner/Agent had no live boundary** on user requests.

---

## 3. New runtime path

```text
VoiceService
  → AssistantOrchestrator (runtime owner)
       → RouteDecision: FAST_SKILL | CHAT | PLAN | CLARIFY | TEMPLATE
       → FAST_SKILL: ExecutionOrchestrator.executeAction(...)
       → CHAT/TEMPLATE: DialogueResponder.respondDetailed(...)
       → PLAN: compound → PlanPathCoordinator (dry-run AgentLoop) + sequential skills
       → CLARIFY: clarification reply
  → TTS (unchanged VoiceService ownership)

BrainGraph = composition root only (wiring + truthful FeatureStatusSnapshot)
```

---

## 4. Unified execution / safety path

New `com.jarvis.core.runtime.ExecutionOrchestrator`:

```text
ToolCall + ToolDefinition
  → LiveSkillRiskBridge (RiskBasedPolicy semantics → ProposedAction)
  → AutonomyActionPipeline (RiskClassifier → AutonomyPolicy → SafetyGate)
  → ToolExecutor
  → ResultVerifier (soft boundary)
```

No live skill path calls `ToolExecutor` without going through this entry (except nothing — `runTool` always uses `execution.executeAction`).

---

## 5. What happened to `RiskBasedPolicy`

**Kept** as the UX/risk-level adapter inside `LiveSkillRiskBridge`:

| RiskLevel | Bridge effect |
|-----------|----------------|
| SAFE / LOW | `READ_ONLY` or `LOCAL_DEVICE_SAFE` → policy ALLOW |
| CONFIRM | `MEDIA_CONTROL` + allowlist → NeedsConfirmation |
| SENSITIVE | `COMMUNICATION` + allowlist → NeedsConfirmation |
| BLOCKED | DENY before / via pipeline |

Orchestrator **no longer** does `toolPolicy.decide()` then bare `execute()`.  
`RiskBasedPolicy` remains available for BrainGraph/tests and as bridge input.

---

## 6. What happened to `AutonomyActionPipeline`

**Became the live Gate path** for all skill executions.

Extended with:

- `source: DecisionSource` (USER_UTTERANCE on live skills)
- `allowedArgumentKeys` (tool-call keys; expands catalog)
- `requiredPermissionsOverride` (live Allow uses empty OS-cap declare; Tool layer still enforces Android permissions)

Conversational «да» confirmation stays Orchestrator pending UX; after confirm, kind is remapped to Allow-equivalent so Gate PASS without ephemeral `ActionConfirmation` token binding (documented in code). Agent/proactive paths still use full confirmation tokens.

---

## 7. Request telemetry

`RequestTrace` + `RequestTraceLog` (`Jarvis.Trace`):

```text
requestId, timestamp, inputType, route, routeReason,
provider, model, fallback, skill, tool, planner, agent,
verification, result, totalLatencyMs, stepIds
```

`AssistantResponse` gains optional `route`, `routeReason`, `provider`, `model`, `fallbackUsed`, `verification`.  
`VoiceService` logs `route=` / `reason=` on brain line.

Device sample:

```text
Jarvis.Trace: req=… route=FAST_SKILL reason=skill_call tool=flashlight verify=… result=PROCESSED
Jarvis.Trace: req=… route=TEMPLATE reason=greeting …
Jarvis.VoiceService: brain … route=FAST_SKILL reason=skill:volume …
```

---

## 8. Feature-state telemetry

`FeatureLifecycle`: `CONSTRUCTED | AVAILABLE | ENABLED | ACTIVE_ON_LIVE_PATH | DISABLED`

`BrainGraph.featureStatusSnapshot()` + diagnostics `featureStatuses` / notes — e.g.:

- `executionOrchestrator=active_on_live_path`
- `memory2=constructed(not_on_live_path)`
- `vision=constructed(stub_not_live)`
- `hybridAsr=constructed(stub_voiceengine_owns_asr)`
- `cloudLlm=active…(dialogue)` when key set

No more blanket `planner=on` meaning “product complete”.

---

## 9. Planner / Agent integration boundary

`PlanPathCoordinator`:

```text
goal → DeterministicAIPlanner → ActionPlan → AgentExecutionLoop (default live=false)
```

Activated on **compound** commands (`RouteDecision.PLAN`): dry-run loop for telemetry + existing sequential skill execution (compat). Full semantic PLAN routing deferred to **V2-02**.

---

## 10. Chat integration

```text
RouteDecision.CHAT → DialogueResponder.respondDetailed → LLM
RouteDecision.TEMPLATE → same, Kind.TEMPLATE / FALLBACK_TEMPLATE
```

`RoutedChatLlmEngine` / Nara / local **unchanged**. Templates explicitly logged (`kind=TEMPLATE reason=greeting|time|…`) — not claimed as LLM.

---

## 11. Compatibility results

| Path | Result |
|------|--------|
| Flashlight / volume | PROCESSED, FAST_SKILL, e2e slo1s true (device) |
| Greeting template | PROCESSED, TEMPLATE |
| Confirm → yes | unit tested |
| Blocked risk | DENIED, unit tested |
| Cloud dialogue chain | wiring preserved (RoutedChatLlmEngine) |
| Wake / VoiceService ownership | **unchanged** |

---

## 12. Tests

New: `core/.../runtime/ExecutionOrchestratorTest.kt`  
Updated: `AssistantOrchestratorTest` (route + requestId), `DialogueResponder` detailed API.

Baseline:

```text
./gradlew :core:testDebugUnitTest
./gradlew :brain:testDebugUnitTest --tests 'com.jarvis.brain.aiprovider.*'
./gradlew :app:assembleDebug
→ BUILD SUCCESSFUL
```

---

## 13. Device tests

Device: Xiaomi `23117RA68G` (`dideztgiojzpgapn`). APK reinstalled.

Observed:

| Command | route | notes |
|---------|-------|-------|
| включи фонарик | FAST_SKILL | PROCESSED, e2e ~421ms, slo1s=true; verify normalized on reinstall |
| громче | FAST_SKILL | PROCESSED, e2e ~335ms, slo1s=true |
| привет | TEMPLATE | greeting, e2e ~441ms, slo1s=true |

`Jarvis.Trace` requestIds preserved across Orchestrator → VoiceService logs.

---

## 14. Build

`:app:assembleDebug` **SUCCESS**.

---

## 15. Consciously NOT wired (still constructed / stubs)

- Memory2 on chat/process path  
- ProactiveAgent ↔ CompanionProactive merge  
- Vision live  
- Cloud ASR/TTS  
- StreamSession → TTS streaming  
- Plugins / MCP population  
- Files/Browser live network executors  
- Full semantic PLAN for free-form goals (V2-02)

---

## 16. Remains for V2-02

- Semantic-first understanding / route selection  
- Tune DialogueResponder template interception  
- Richer PLAN activation (not only compound splitter)  
- Unify memory inject  
- Stronger verification with real observers  

---

## 17. New issues found

1. Soft ResultVerifier initially reported FAILURE when tool `data` keys ≠ ExpectedEffects keys — mitigated by soft mode (expected only when observer facts present). Real device observers still needed later.  
2. `ActionConfirmation` token binding needs stable ActionRequest ids across plan() calls for proactive; live conversational confirm uses Orchestrator pending (documented trade-off).  
3. Compound PLAN dry-run does not yet replace sequential skill execution — intentional compat.

---

## Stop rule

**V2-01 complete. Do not auto-start V2-02.**
