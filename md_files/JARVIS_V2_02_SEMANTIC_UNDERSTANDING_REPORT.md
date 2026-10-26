# Jarvis V2-02 — Semantic-First Understanding Report

**Date:** 2026-10-01  
**Repository:** `/mnt/D/Jarvis_for_android`  
**Inputs:** `JARVIS_V2_FULL_AUDIT.md`, `JARVIS_V2_01_ARCHITECTURE_HARDENING_REPORT.md`, `Jarvis_Roadmap_V2.md`  
**Status:** COMPLETE — stop before V2-03

---

## 1. Current understanding architecture (after V2-02)

```text
VoiceService
  → AssistantOrchestrator.process()
       → FollowUpResolver (early, unchanged)
       → understandSemantic()
            → FastSemanticClassifier (local, no LLM)
            → [optional] LlmStructuredSemanticInterpreter (structured only)
       → SemanticRouteSelector.select()
       → RouteDecision
            ├── FAST_SKILL  → rewrite → BrainRouter / ExecutionOrchestrator
            ├── TEMPLATE    → DialogueResponder (short templates)
            ├── CHAT        → DialogueResponder → RoutedChatLlmEngine → AIRouter
            ├── PLAN        → PlanPathCoordinator (+ Planner dry-run)
            └── CLARIFY     → targeted clarification reply
```

V2-01 safety chain is unchanged:

```text
Risk → AutonomyPolicy → SafetyGate → Executor → Verifier
```

Semantic layer has **no** direct tool/execution access. It only produces `SemanticUnderstanding` + `RouteDecision`.

---

## 2. Old routing problems (pre V2-02)

From V2-01 live path:

1. **Skill-first matching** — phrase similarity / SkillRetriever often beat natural language.
2. **Aggressive DialogueResponder templates** — time/greeting/identity/joke stole conversational asks.
3. **Compound splitter as PLAN** — commas became independent skills, not one goal.
4. **No explicit goal/entities/ambiguity model** — confidence thresholds drove too much.
5. **LLM used as chat/execution guess**, not as structured semantic fallback.
6. **Contextual pronouns** (`открой его`) either failed or went into weak chat/LLM paths.

---

## 3. New semantic model

Package: `com.jarvis.core.runtime.semantic`

| Type | Role |
|------|------|
| `SemanticInput` | Minimal relevant context (prev turn, last tool, pending) |
| `SemanticUnderstanding` | goal, intent, entities, args, constraints, refs, confidence, ambiguity, clarity, capabilities, suggestedRoute, clarifyQuestion, candidates, rewrittenCommand |
| `SemanticInterpreter` | Fast or LLM interpreter interface |
| `FastSemanticClassifier` | Local hybrid understanding |
| `SemanticRouteSelector` | Multi-factor route choice |
| `LlmStructuredSemanticInterpreter` | LLM → structured fields only (brain module) |

Example mapping:

| User | Goal / intent | Route |
|------|---------------|-------|
| «сделай здесь потише» | `reduce_audio_volume` / volume | FAST_SKILL |
| «открой браузер и найди … билеты в Берлин на завтра» | `multi_step_goal` + constraints | PLAN |
| «открой его» (no referent) | `resolve_reference` | CLARIFY |
| «привет» / «который час» | greet / tell_time | TEMPLATE |
| «что ты думаешь о космосе?» | conversation / chat | CHAT |

---

## 4. New route-selection logic

`SemanticRouteSelector` uses **all** of:

- suggested route from classifier  
- ambiguity (`HIGH` → CLARIFY)  
- clarity  
- confidence (one factor only)  
- required capabilities / constraints / step hints  
- presence of clarify question / rewrite  

Rules (summary):

| Route | When |
|-------|------|
| **FAST_SKILL** | Clear goal, known capability, params OK, single fast action |
| **CHAT** | Advice / explanation / open conversation; no multi-step device goal |
| **PLAN** | Free-form multi-step / multi-verb+goalish / browser+search goals |
| **CLARIFY** | Material ambiguity, unresolved pronoun, incomplete action |
| **TEMPLATE** | Short obvious phrase, no conversational wrapper |

LLM semantic fallback is used **only** when fast path returns `null` or weak `UNKNOWN` — **not** when local already decided CLARIFY / TEMPLATE / FAST_SKILL / PLAN / CHAT.

---

## 5. Template interception changes

`DialogueResponder` (V2-02):

- Templates only for **short** phrases (`shortEnough` + length caps).
- Conversational wrappers (`как думаешь`, `стоит ли`, …) skip TEMPLATE → LLM CHAT.
- Identity / joke / greeting / time / date / thanks / bye remain for obvious shorts.
- Unit: `conversationalTime_isNotStolenByTemplate`.

Semantic layer mirrors the same “obvious short vs conversational” split before CHAT/TEMPLATE routing.

---

## 6. Context / follow-up handling

- **FollowUpResolver** still runs **first** (no second competing resolver).
- Semantic input includes `previousUserText`, `previousAssistantText`, `lastToolId`, pending phase.
- Pronouns (`открой его`, `включи это`, `сделай нормально`, …):
  - **No resolvable referent** → CLARIFY with targeted question.
  - **Resolvable** (last tool / concrete prior app) → defer to FollowUp / BrainRouter (classifier returns null).
- Bare previous utterance like «привет» does **not** count as a referent.

Full unified memory inject remains V2-03/V2-04 scope (minimal context only).

---

## 7. PLAN activation

Beyond V2-01 compound-only PLAN:

```text
free-form goal
  → FastSemanticClassifier.planGoal (multi_step / browser+search / constraints)
  → RouteDecision.PLAN
  → processSemanticPlan
  → PlanPathCoordinator.planAndRun (dry-run)
  → existing Planner / discovery
```

Important fix: `routeReason == free_form_multi_step` **does not** fall through into blind `processCompound` skill sequences (was causing FAST_SKILL/`ui.click` masking).

Live multi-step Agent execution still dry-run — intentional V2-02 boundary.

---

## 8. LLM semantic fallback

`LlmStructuredSemanticInterpreter`:

- Prompt asks for one structured line: `route=…;goal=…;intent=…;ambiguity=…;clarify=…`
- **Never** executes tools.
- Minimal context: current text + optional prev_user + last_tool.
- Parsed into `SemanticUnderstanding` with `llmInterpretationUsed=true`.
- Then validated by `SemanticRouteSelector` like any other understanding.

CHAT replies still use existing `DialogueResponder → RoutedChatLlmEngine → AIRouter` (unchanged).

---

## 9. Telemetry

`RequestTrace` / `Jarvis.Trace` summary includes:

```text
requestId, route, routeReason, goal, intent, conf, amb,
llmInterp, llmReq, provider, model, fallback,
skill, tool, planner, agent, verify, result, latency
```

Plus fields: `rawInput`, `normalizedInput`, `semanticIntent`, `semanticGoal`, `semanticConfidence`, `ambiguity`, `llmInterpretationUsed`, `llmRequired`.

Secrets are not logged (previews truncated).

---

## 10. Tests

### New / extended

| Suite | Coverage |
|-------|----------|
| `FastSemanticClassifierTest` | volume paraphrases, flashlight, launcher, greeting/time TEMPLATE, conversational time→CHAT, open chat, free-form PLAN, pronoun CLARIFY, incomplete CLARIFY, unrelated prev ≠ referent, prior app defers |
| `SemanticRouteSelectorTest` | HIGH ambiguity forces CLARIFY |
| `LlmStructuredSemanticInterpreterTest` | structured parse |
| `DialogueResponderTest` | conversational time not stolen by template |
| Existing `AssistantOrchestratorTest`, `ExecutionOrchestratorTest` | regression |

### Commands

```bash
./gradlew :core:testDebugUnitTest --tests 'com.jarvis.core.runtime.*' \
  :brain:testDebugUnitTest --tests 'com.jarvis.brain.DialogueResponderTest' \
  --tests 'com.jarvis.brain.AssistantOrchestratorTest' \
  --tests 'com.jarvis.brain.semantic.*'
```

**Result:** BUILD SUCCESSFUL (green).

---

## 11. Device tests

ADB `DEBUG_COMMAND` via VoiceService e2e (2026-10-01):

| Input | Route | Reason / notes | Brain latency |
|-------|-------|----------------|---------------|
| «открой его» | **CLARIFY** | `unresolved_pronoun` / `resolve_reference`, «Что именно открыть?…», `llmInterp=false` | **~50ms** |
| «привет» | **TEMPLATE** | greeting | **48ms** |
| «который час» | **TEMPLATE** | time | **41ms** |
| «включи фонарик» | **FAST_SKILL** | `semantic_flashlight_on`, verify=SUCCESS | **~170–570ms** |
| «что ты думаешь о космосе?» | **CHAT** | local semantic chat → LLM reply | **3470ms** (LLM) |
| «открой браузер и найди дешёвые билеты в Берлин на завтра» | **PLAN** | `free_form_multi_step`, PlanPath dry 8 steps, constraints Berlin/tomorrow/cheapest | **99ms** |
| «сделай громче» | **FAST_SKILL** | volume, verify=SUCCESS | **181ms** |

PLAN is **not** masked as compound FAST_SKILL sequence.

---

## 12. Performance

| Path | Observed brain latency | Notes |
|------|------------------------|-------|
| CLARIFY / TEMPLATE | ~40–50ms | Semantic overhead negligible |
| FAST_SKILL (flashlight/volume) | ~170–180ms | Domains in skill+execution; no LLM |
| PLAN (dry PlanPath) | ~100ms | No cloud |
| CHAT (local Qwen) | ~3.5s | Expected LLM generate cost |

Fast commands do **not** pay LLM semantic cost when classifier is decisive.

---

## 13. Regression results

- V2-01 execution / safety path preserved (`ExecutionOrchestrator`).
- Wake / VoiceService ownership untouched.
- AIRouter not rewritten.
- Memory2 / Vision / MCP / Plugins / cloud ASR-TTS not touched.
- `:app:assembleDebug` **SUCCESS**; device install **Success**.
- Baseline orchestrator / dialogue / core runtime tests green.

---

## 14. Known limitations

1. **PLAN live execution** still dry-run + summary reply; full Agent loop live is later.
2. **LLM semantic fallback** quality depends on tiny local model (structured parse can be noisy) — gated so it cannot override clear local routes.
3. **Paraphrase coverage** is heuristic (RU phrases for volume/flashlight/launcher/brightness/weather/music) — not a full NLU ontology.
4. **Compound comma-split** still exists for compat when semantic did not mark free-form PLAN.
5. Soft ResultVerifier / observers unchanged from V2-01.
6. Trace `provider/model` for CHAT may still show `chat_llm` / `-` until V2-03 router telemetry polish.

---

## 15. Remains for V2-03 / V2-04

### V2-03 (Hybrid AI Router V2)

- Richer provider/model/fallback telemetry end-to-end  
- Better cloud vs local selection for CHAT/PLAN interpretation  
- Stronger structured LLM semantic when fast path is null  

### V2-04+

- Unified memory inject into semantic context  
- Live PLAN / Agent execution with safety gate  
- Broader capability discovery for free-form goals  
- Stronger verification observers  
- Visual workflow / plugins / MCP (roadmap later stages)

---

## Key files

| File | Change |
|------|--------|
| `core/.../semantic/SemanticModels.kt` | Model |
| `core/.../semantic/FastSemanticClassifier.kt` | Local understanding |
| `core/.../semantic/SemanticRouteSelector.kt` | Route selection |
| `brain/.../semantic/LlmStructuredSemanticInterpreter.kt` | LLM structured fallback |
| `brain/.../AssistantOrchestrator.kt` | Semantic-first process + PLAN |
| `brain/.../DialogueResponder.kt` | Safer templates |
| `core/.../runtime/RequestTrace.kt` | Semantic telemetry fields |
| `app/.../BrainGraph.kt` | Wire fast + llm semantic |

---

## Stop rule

**V2-02 complete. Do not auto-start V2-03.**

Main outcome:

> Jarvis first understands the user’s goal (semantic), then chooses FAST_SKILL / CHAT / PLAN / CLARIFY / TEMPLATE — without letting templates or phrase-matching steal natural language, and without giving the semantic layer execution power.
