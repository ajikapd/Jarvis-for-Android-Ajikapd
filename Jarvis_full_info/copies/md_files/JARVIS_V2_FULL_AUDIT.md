# Jarvis V2 — Full System Audit (V2-00)

**Date:** 2026-10-01  
**Repository:** `/mnt/D/Jarvis_for_android`  
**Package:** `com.jarvis.assistant`  
**Roadmap:** `md_files/Jarvis_Roadmap_V2.md` Stage V2-00  
**Baseline closed:** New Roadmap Stages 8C–27 (`ROADMAP_FULL_COMPLETION_REPORT.md`)  
**Principle:** code/tests existence ≠ end-to-end product readiness

---

## 1. Executive summary

Jarvis is a **working Android voice assistant** with a solid offline voice pipeline (Wake → ASR → VoiceService → skills/tools → TTS), a **real hybrid chat LLM path** (NaraRouter cloud + local GGUF fallback), and a **rich library of Stage 8C–27 components** that pass unit tests and are constructed in `BrainGraph`.

The critical audit finding: **the Stage 11–27 agent stack is mostly not on the live user request path.**

| Reality | Evidence |
|---------|----------|
| User utterance → `VoiceService` → `AssistantOrchestrator.process()` → `SkillRetriever` + `BrainRouter` → `RiskBasedPolicy` → `ToolExecutor` **or** `DialogueResponder` → TTS | `VoiceService.kt`, `AssistantOrchestrator.kt`, `BrainGraph.kt` |
| `DeterministicAIPlanner`, `AgentExecutionLoop`, `Memory2Store`, `AsrRouter`/`TtsRouter` (stubs), `VisionRouter`, `ProactiveAgent`, `FilesAgent`, `BrowserAgent`, plugins/MCP | Created in `BrainGraph.build()`, exposed via accessors / diagnostics; **not invoked from `AssistantOrchestrator.process()`** |
| Cloud dialogue | Works on device when `prefer_cloud_llm` + API key (`NARA_CLOUD_DEVICE_TEST.md`) |
| Deterministic / template interception | Many phrases never reach LLM (`DialogueResponder` time/greetings/jokes/identity) |
| Dual safety / dual proactive | Live tools: `RiskBasedPolicy`. Agent loop: `AutonomyActionPipeline`. Live companion: `CompanionProactive` ≠ `ProactiveAgent` |

**Verdict:** architecturally rich, productively usable for commands + chat, **not yet** a unified companion that plans, discovers tools, verifies, remembers (Memory 2.0), and acts proactively through one pipeline. V2 should **wire and harden**, not blindly rewrite Wake/8A–8F.

**Baseline regression (this audit):** `:core:testDebugUnitTest` + `:brain:testDebugUnitTest` + `:app:assembleDebug` → **BUILD SUCCESSFUL** (2026-10-01). Historical closure: **415/415** unit (387 core + 28 brain aiprovider). Prior device suite: **19/19** DEBUG_COMMAND + **2** autonomy dry-runs (`STAGE19_27_DEVICE_TEST_RESULTS.md`). Device `23117RA68G` was attached during audit; fresh smoke blocked by intermittent execution environment — use prior device tables as baseline evidence.

---

## 2. Current architecture

### Modules (`settings.gradle.kts`)

```text
:app  → composition root, VoiceService, UI, settings
:brain → AssistantOrchestrator, DialogueResponder, AI providers/router, LlmBrain
:core  → NLP, autonomy, planner, agent loop, catalog, memory, routines, streaming, vision, remote, diagnostics
:tools → Flashlight, Volume, Weather, Launcher, UI tools, …
:asr-engine / :tts-engine / :wakeword-engine / :audio-engine → real voice
:screen-control → Accessibility
:background-service / :overlay-ui / :connectivity-router / :permissions-onboarding
```

### Who owns what

| Concern | Owner | Notes |
|---------|-------|-------|
| Mic / Wake / ASR / TTS lifecycle | `VoiceService` + `VoiceEngine` + `VoiceController` | Single pipeline owner |
| Request orchestration | `AssistantOrchestrator` | Skills-first, then chat |
| DI / feature registry | `BrainGraph` object | Wiring + caches; **not** a full runtime orchestrator for planner/agent |
| Cloud/local chat | `RoutedChatLlmEngine` → `AIRouter` | Only on dialogue path |
| Tool policy (live) | `RiskBasedPolicy` | RiskLevel → Allow/Confirm/Deny |
| Tool policy (agent loop) | `AutonomyActionPipeline` | RiskClassifier → AutonomyPolicy → SafetyGate |
| Companion proactive UI | `CompanionProactive` | Dashboard tick; dry-run executor |
| Stage 25 proactive API | `ProactiveAgent` | Wired in BrainGraph; not used by Companion UI |

`BrainGraph` is a **composition root / feature registry**, not the semantic understanding layer. Runtime truth is `AssistantOrchestrator`.

---

## 3. Runtime pipeline

### Actual user path (voice / DEBUG_COMMAND / TestConsole)

```text
User
  → VoiceEngine (Wake/VAD/ASR)  OR  DebugCommandReceiver inject
  → VoiceController.notifyRequest
  → VoiceService.coreListener
  → BrainGraph.build().process(UserRequest)
  → AssistantOrchestrator:
       pending confirm/clarify
       FollowUpResolver
       incomplete-command check
       CompoundCommandSplitter
       processSingle:
         SkillRetriever + CapabilityDiscovery (log only)
         BrainRouter.route → SkillCall | Clarify | Chat
         SkillCall → RiskBasedPolicy → toolExecutor.execute
         Chat → DialogueResponder (templates → RoutedChatLlmEngine)
  → VoiceController.speak(reply)
  → JarvisSpeaker / TTS first-audio (slo1s log)
```

### Documented New Roadmap path (partially aspirational)

```text
… → AI Router → Planner → Discovery → Agent Loop → Risk → Policy → Gate → Executor → Verifier → …
```

This path exists as **libraries + tests + BrainGraph fields**. It is exercised by unit tests and `DEBUG_AUTONOMY` dry-run helpers, **not** by ordinary `process()`.

---

## 4. Routing matrix

| Request type | Deterministic | Local NLP/skills | Local LLM | Cloud LLM | Planner/Agent | Clarification |
|--------------|---------------|------------------|-----------|-----------|---------------|---------------|
| «включи фонарик» | — | **Skill → tool** | — | — | no | if low confidence |
| «громче» / follow-up «тише» | FollowUpResolver | tool | — | — | no | — |
| «который час» / «привет» / «кто ты» / jokes | **Dialogue templates** | — | — | **blocked** | no | — |
| Open chat («космос», «квантовая физика») | — | may try knowledge tool first | fallback | **yes if preferCloud+key+online** | no | — |
| knowledge tool fail | — | — | / cloud via chat fallback | yes | no | — |
| Multi-step comma list | Compound splitter | sequential skills | — | — | no AgentLoop | mid-chain confirm |
| Autonomy DEBUG | — | — | — | — | **dry-run loop** | — |
| Offline | skills + templates + local GGUF | yes | yes | unavailable → local | no | — |

**Cloud share of “normal conversation”:** only utterances that survive template + skill thresholds and reach `DialogueResponder` with LLM ready. Greetings/time/identity/jokes are **100% deterministic**. Device evidence: cloud used for long open questions (~3.5–6.5 s cloud latency).

**Router rules (`AIRouter`):** privacy → local; offline → local; online+complex → cloud; online+simple → local unless `preferCloudWhenOnline`. Device often sets preferCloud=true → chat prefers cloud.

---

## 5. LLM quality findings

| Factor | Status | Finding |
|--------|--------|---------|
| Persona | WORKING | Passed into `generate(..., persona)`; templates vary by persona |
| History | WORKING_BUT_IMPERFECT | Last ~4 messages; memory string capped ~180 chars |
| Memory injection | PARTIAL | `LongTermMemory.retrieve` only; Memory2 unused on chat path |
| Device context | PARTIAL | Companion observation exists; not systematically in LLM prompt |
| Tool descriptions | PARTIAL | Capability discovery logged, not fed into chat LLM |
| Output length | WORKING_BUT_IMPERFECT | Hard truncate `.take(360)` in DialogueResponder |
| Streaming tokens | STUB | Sync `generate()` only; Stage 23 `StreamSession` unused in dialogue |
| Accidental local fallback | WORKING | Cloud miss → local; logged `Jarvis.CloudLLM` |
| Accidental deterministic intercept | WORKING_BUT_IMPERFECT | Strong fast-path; can feel “dumb” vs cloud for edge phrases |
| Model selection | WORKING | BALANCED→`agnes-2.5-flash`; QUALITY→stronger model (Nara rejected `mistral-large` historically) |

**Controlled comparison (device, prior):**

| Path | Quality | Latency (approx) |
|------|---------|------------------|
| Direct cloud via Jarvis route | Good RU prose | brain 4–6 s, e2e 6–7 s |
| Local GGUF fallback | Weaker / slower cold | variable |
| Deterministic templates | Instant, fixed | brain &lt;100 ms typical |

Root cause of “cloud works but e2e slow”: **full LLM completion before TTS**, ASR/brain handoff, TTS startup — not only provider RTT.

---

## 6. Full latency breakdown

Instrumentable today (logs):

| Stage | Log / signal |
|-------|----------------|
| brain total | `Jarvis.VoiceService` `brain Xms` |
| TTS first audio | `JarvisSpeaker` `tts first-audio` |
| e2e first audio | `VoiceService` `e2e first-audio … slo1s=` |
| Cloud complete | `Jarvis.CloudLLM` `latency=…ms` |
| Dialogue LLM | `Jarvis.Dialogue` `llm-generate …ms` |

**Missing for V2:** requestId, route reason, token counts, TTFT, planner/tool/verify spans on the live path.

**Device baseline (STAGE19_27):**

| Class | brain ms | e2e ms | slo1s |
|-------|----------|--------|-------|
| Fast tools (flashlight/volume) | 16–175 | 227–476 | often true |
| Templates (привет/час) | 41–100 | 304–842 | often true |
| Weather / settings / joke | 2.6–4.7k | 5–5.3k | false |
| Cloud dialogue | 4.1–6.3k | 7.2–7.3k | false |
| Overall slo1s | — | — | **11/19** |

**Bottlenecks:** (1) wait-for-full-LLM before speak; (2) network tools; (3) cold TTS/model; (4) no token streaming to TTS.

---

## 7. Voice findings

| Component | Classification | Notes |
|-----------|----------------|-------|
| Wake / KWS | WORKING | Owned by VoiceEngine; **do not rewrite** (V2 rule) |
| VAD / streaming ASR | WORKING | Sherpa streaming RU (+ bilingual refine) |
| NLP normalize | WORKING | `AsrPhraseNormalizer`, compound split |
| Hybrid AsrRouter/TtsRouter | STUB | `LocalAsrStub` / `CloudAsrStub(configured=false)` in BrainGraph — **not** VoiceEngine |
| Cloud ASR/TTS | STUB | configured=false |
| TTS (JarvisSpeaker) | WORKING | first-audio logging; `stopSpeaking` exists |
| Barge-in / Stage 23 StreamSession | PARTIAL / STUB | StreamSession unit-tested; **not** wired to VoiceService dialogue |
| Interruption | PARTIAL | stopSpeaking available; no full barge-in UX on LLM stream |
| Audio queue / busy flag | WORKING | `setProcessingBusy` pauses ASR during TTS |

---

## 8. Planner / Agent findings

| Item | Classification | Reality |
|------|----------------|---------|
| `DeterministicAIPlanner` | WORKING (library) | Unit-tested; builds ActionPlan; **not called from process()** |
| `ActionPlanValidator` | WORKING | Used by planner/routines tests |
| `AgentExecutionLoop` | WORKING (library) | Re-enters Risk→Policy→Gate per TOOL step; dry-run default |
| Goal completion for user NL | PARTIAL | Users get skill/tools or chat — **not** plan→execute→verify loop |
| Clarification | WORKING | Orchestrator thresholds + pending actions |
| Retry / replan | PARTIAL | In loop design; unused on live path |
| Cancellation / timeout | PARTIAL | Loop supports cancel in tests; VoiceService uses coroutine without unified cancel token to agent |

**Main question answered:** Jarvis can complete **many phone goals via skills**, but **cannot** generally “understand goal → plan → discover → execute → verify” as one product flow yet.

---

## 9. Tool findings

| Area | Classification | Notes |
|------|----------------|-------|
| Legacy ToolRegistry + tools module | WORKING | Flashlight, volume, weather, launcher, UI, … on device |
| CatalogToolRegistry + Discovery | WORKING (wired) | Import in BrainGraph; discovery logged in orchestrator |
| Live path SafetyGate | OUTDATED / dual | Live uses `RiskBasedPolicy` only, not `DefaultActionSafetyGate` |
| NativeAndroidToolCatalog | WORKING | 24 defs; live-safe subset |
| FilesAgent / BrowserAgent | PARTIAL | Planning helpers; no network browser client; not in process() |
| Plugins / MCP | STUB | Empty registries on device |
| Verifier | WORKING (library) | Used in tests / some tool logs; not systematic in Orchestrator |
| Device-proven tools | WORKING | flashlight, volume, weather, launcher (verified logs) |
| Music stop | PARTIAL | clarification on device |
| Remote tools | STUB | registry size 0 |

**No evidence of LLM→Android side-effect bypass** on the live path: skills still go through `toolPolicy.decide` then `toolExecutor.execute`. Agent-loop path also re-gates. Keep this invariant.

---

## 10. Memory findings

| Store | Classification | Persistence | On live path? |
|-------|----------------|-------------|---------------|
| `LongTermMemory` | WORKING | Yes (app memory) | Yes (retrieve → history/mem string) |
| Companion SQLite (`SqliteMemoryRepository`, candidates) | WORKING | Yes | Companion UI / pipeline; partial brain inject |
| `Memory2Store` | STUB / PARTIAL | **In-memory only** (`LinkedHashMap`) | Constructed; **not** orchestrator |
| Behavior learning | WORKING | Companion | Suggest-only; separate from Memory2 |
| Routines `RoutineStore` | PARTIAL | Need verify persistence class | Generator/store wired; not voice-driven save UX complete |

**Duplicate responsibility:** Companion memory vs LongTermMemory vs Memory2 kinds — three concepts, weak unification.

---

## 11. Proactivity findings

| System | Classification | Notes |
|--------|----------------|-------|
| Stage 7 ProactiveDecisionEngine | WORKING | Tests + Companion |
| `CompanionProactive` | WORKING_BUT_IMPERFECT | Dashboard tick; attachSafeExecutor live=false; UI suggestions |
| `ProactiveAgent` (Stage 25) | PARTIAL | Wired in BrainGraph; **Companion does not call it** |
| Silent dangerous exec | SOLID (policy) | Denied by design; dry-run only without user path |

---

## 12. UI audit

| Surface | Classification | Notes |
|---------|----------------|-------|
| Dashboard | WORKING | Main hub |
| Companion panel (ЕЩЁ → Companion) | WORKING | Observation + proactive + diagnostics snapshot |
| Settings / cloud prefs | WORKING | Key on device prefs only (not in repo) |
| Model / chat profile | WORKING | BALANCED vs QUALITY matters for Nara models |
| Diagnostics (Stage 27) | WORKING | `SystemDiagnosticsSnapshot` / formatter |
| Memory / routines / workflow GUI | PARTIAL | Workflow = graph converter, **not** drag-drop editor |
| Tools / permissions screens | WORKING_BUT_IMPERFECT | Onboarding + settings; MIUI overlays interfere automation |
| Empty/error/loading states | PARTIAL | Inconsistent across companion vs voice |

---

## 13. Device / lifecycle audit

| Topic | Classification | Notes |
|-------|----------------|-------|
| Manifest VoiceService FGS mic | WORKING | START_STICKY |
| VoiceInteraction / Recognition stubs | PARTIAL | Forward to VoiceService; not full system assistant takeover |
| BootReceiver | WORKING | background-service |
| Accessibility | WORKING | screen-control; recycle deprecations noise |
| Notification listener / usage stats | WORKING | Companion observation |
| Screen-off / lock / MIUI battery | WORKING_BUT_IMPERFECT | REQUEST_IGNORE_BATTERY_OPTIMIZATIONS present; OEM variance |
| Reboot | PARTIAL | Boot path exists; OEM may still kill |
| Permissions breadth | WORKING_BUT_IMPERFECT | Large surface (SMS, call, contacts, …) — product/security review later |

---

## 14. Security audit

| Control | Status |
|---------|--------|
| No LLM direct tool exec on live path | **OK** |
| Confirmation for SENSITIVE/CONFIRM tools | **OK** (`RiskBasedPolicy`) |
| Autonomy dry-run default | **OK** |
| Cloud API key | Device prefs only; not committed (**OK**) |
| allowBackup=false | **OK** |
| Dual policy stacks | Risk: future code may call wrong stack — **harden in V2-01** |
| Plugin/MCP empty | Reduces attack surface for now |
| Accessibility / overlay powers | High privilege — keep gated |

---

## 15. Performance / battery findings

- Foreground mic service + wake models = continuous cost; low-power mode skips tool LLM warm.
- Cloud chat adds multi-second radio use.
- `setProcessingBusy` reduces ASR/TTS CPU fight — good.
- No full battery profiling in this audit — mark P2 measurement.

---

## 16. Stub / partial inventory

| Item | Class |
|------|-------|
| CloudAsrStub / CloudTtsStub | STUB |
| LocalAsrStub / LocalTtsStub (vs real engines) | STUB adapters |
| LocalVisionStub / CloudVisionStub | STUB |
| StreamSession (dialogue/TTS) | STUB wiring |
| PluginRegistry / McpRegistry (device) | STUB (empty) |
| Browser network client | STUB |
| Memory2 durability | PARTIAL (RAM only) |
| Visual workflow editor UI | PARTIAL / OUTDATED claim vs “editor” |
| Planner/Agent on process() | PARTIAL (disconnected) |
| ProactiveAgent vs CompanionProactive | REDUNDANT dual |
| RiskBasedPolicy vs AutonomyActionPipeline | REDUNDANT dual |
| JarvisRecognitionService | PARTIAL stub |

---

## 17. Rewrite candidates

Only if proven necessary (do **not** auto-rewrite):

| Candidate | Why | Priority |
|-----------|-----|----------|
| Unify live tool policy with AutonomyActionPipeline | Two safety stacks | P0 design |
| Single proactive entry | CompanionProactive vs ProactiveAgent | P1 |
| Memory model merge | LTM + Companion + Memory2 | P1 |
| DialogueResponder fast-path policy | Too aggressive vs semantic companion | P1 (tune, not full rewrite) |
| Hybrid voice routers | Or delete until real cloud ASR/TTS | P2 |

**Do not rewrite:** Wake pipeline, 8A–8F contracts as such, working VoiceService ownership.

---

## 18. Refactor candidates

- Inject planner/agent (or a thin GoalRouter) into Orchestrator behind flags.
- Feed discovery subset into LLM / planner context.
- Connect Memory2 persistence or fold into Companion SQLite.
- Wire StreamSession → TTFT → TTS chunking.
- Diagnostics: log route/provider/reason/requestId on every turn.
- Remove or clearly mark unused BrainGraph “on” flags that only mean “constructed”.

---

## 19. Keep-as-is components

- VoiceService / VoiceEngine ownership model  
- Skill manifests + FollowUpResolver + CompoundCommandSplitter  
- RoutedChatLlmEngine + AIRouter + OpenAiCompatibleCloudClient  
- CatalogToolRegistry / Discovery design  
- Autonomy contracts (8A–8F) as libraries  
- Device-proven native tools (flashlight, volume, …)  
- DebugCommandReceiver e2e harness  

---

## 20. P0 / P1 / P2 / P3 matrix

### P0 — correctness / safety / truth

1. Document + enforce **one execution path** (no accidental dual policy).  
2. Stop treating BrainGraph feature flags as “user-facing complete”.  
3. Add runtime route logging (provider, reason, fallback).  
4. Preserve no LLM→exec bypass.

### P1 — companion usefulness

1. Semantic-first routing (V2-02): reduce harmful deterministic interception.  
2. Wire planner/agent for multi-step goals that skills miss.  
3. Unify memory inject into dialogue.  
4. Latency: stream or progressive TTS for cloud replies.  
5. Align CompanionProactive with ProactiveAgent.

### P2 — capability expansion

1. Real vision / cloud ASR-TTS only after product need.  
2. Browser/Files live executors.  
3. Plugins/MCP with sandbox.  
4. Workflow UI if still desired.  
5. Battery/MIUI hardening.

### P3 — polish

1. UI empty/error states.  
2. Token accounting.  
3. Remote devices.  
4. Deprecation cleanup (Accessibility recycle warnings).

---

## 21. Dependencies between fixes

```text
V2-00 (this audit)
  → V2-01 Architecture Hardening (single path, dual-stack cleanup, logging)
       → V2-02 Brain/Understanding (semantic-first)
       → V2-03 Hybrid AI Router V2 (needs accurate route metrics from V2-01)
       → V2-04 LLM Quality (prompt/context; needs real route %)
       → V2-05 Latency/Streaming (needs dialogue path ownership clear)
       → later: Memory/Routines/Proactive/Vision/Tools depth
```

Cannot honestly optimize “% cloud vs local” until V2-01 instrumentation exists.

---

## 22. Recommended order

1. **V2-01** — Architecture Hardening (source of truth, one safety chain on live path, truthful diagnostics).  
2. **V2-02** — Understanding V2 (NL → goal; skills as fast path).  
3. **V2-05 / streaming slice** — TTFT + speak-while-generate (big UX win once routing clear).  
4. **V2-03 / V2-04** — Router + quality with measured data.  
5. Wire Memory2 / routines / proactive into the **same** path.  
6. Only then expand vision/browser/plugins.

---

## 23. Proposed target architecture

```text
User (voice/text)
  → VoicePipeline (unchanged Wake ownership)
  → Normalize + ConversationContext
  → Semantic Understanding (goal / constraints / follow-up)
  → Route decision:
        FAST_SKILL | CHAT | PLAN | CLARIFY
  → If CHAT: AIRouter → Cloud|Local (streaming) → TTS
  → If PLAN: Deterministic|LLM Planner → Discovery subset
        → AgentExecutionLoop
        → RiskClassifier → AutonomyPolicy → SafetyGate
        → Executor → Verifier
  → Memory write (unified policy)
  → Proactive suggestions (same gate; never silent dangerous)
  → UI / TTS
```

Single **ToolPolicy/Safety** stack on all exec. BrainGraph remains DI; Orchestrator (or successor) owns runtime.

---

## 24. Exact next implementation stage

**Next stage: V2-01 — Architecture Hardening**

Do **not** start large features (Vision cloud, Marvel TTS, new providers) before V2-01.

V2-01 should deliver:

- one live execution/safety path (or explicit adapter from RiskBasedPolicy → Autonomy pipeline);  
- truthful diagnostics (constructed ≠ active-on-request);  
- request route telemetry;  
- removal/quarantine of dead dual entry points where safe;  
- regression: unit suite + device smoke (tools + cloud dialogue + clarify).

---

## Appendix A — Subsystem classification table

| Subsystem | Status |
|-----------|--------|
| Modules / Gradle graph | SOLID |
| VoiceService ownership | SOLID |
| Wake/ASR/TTS offline | WORKING |
| AssistantOrchestrator skills path | WORKING |
| Dialogue + Nara cloud | WORKING |
| AIProvider / AIRouter | WORKING |
| RiskBasedPolicy live tools | WORKING |
| Autonomy 8A–8F libraries | WORKING |
| Catalog + Discovery | WORKING_BUT_IMPERFECT (log, limited use) |
| Planner / AgentLoop live | PARTIAL |
| ResultVerifier live | PARTIAL |
| Files/Browser agents | PARTIAL |
| Plugins/MCP | STUB |
| Hybrid voice routers | STUB |
| Vision | STUB |
| Streaming session product | STUB |
| Memory2 | PARTIAL (RAM) |
| Companion memory | WORKING |
| Routines | PARTIAL |
| CompanionProactive | WORKING_BUT_IMPERFECT |
| ProactiveAgent | PARTIAL |
| Remote | STUB |
| Diagnostics UI | WORKING |
| Workflow “editor” | PARTIAL |
| Dual safety stacks | ARCHITECTURALLY_WRONG (duplication) |
| Roadmap pipeline claim vs process() | OUTDATED (docs ahead of wiring) |

---

## Appendix B — Evidence index

| Evidence | Location |
|----------|----------|
| V2 roadmap / V2-00 prompt | `md_files/Jarvis_Roadmap_V2.md` |
| New roadmap closure | `md_files/ROADMAP_FULL_COMPLETION_REPORT.md` |
| Device 19–27 | `md_files/STAGE19_27_DEVICE_TEST_RESULTS.md` |
| Nara cloud | `md_files/NARA_CLOUD_DEVICE_TEST.md` |
| Companion status | `md_files/COMPANION_STATUS.md` |
| Wiring | `app/.../BrainGraph.kt` |
| Live process | `brain/.../AssistantOrchestrator.kt` |
| Chat route | `brain/.../RoutedChatLlmEngine.kt`, `DialogueResponder.kt` |
| Voice e2e | `app/.../VoiceService.kt` |
| Voice stubs | `core/.../voice/HybridVoiceStack.kt` |
| Unit regression this audit | Gradle BUILD SUCCESSFUL `:core` + `:brain` tests + `:app:assembleDebug` |

---

## Appendix C — Stop rule

Stage **V2-00 complete** with this report.  
**Do not auto-start V2-01** until explicitly requested.
