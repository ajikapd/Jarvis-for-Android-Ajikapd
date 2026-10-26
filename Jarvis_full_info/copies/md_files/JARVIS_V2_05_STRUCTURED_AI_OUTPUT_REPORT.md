# Jarvis V2-05 — Structured AI Output Report

**Date:** 2026-10-01  
**Repository:** `/mnt/D/Jarvis_for_android`  
**Inputs:** V2-00…V2-04 reports, `Jarvis_Roadmap_V2.md`  
**Status:** COMPLETE — stop before V2-06

---

## 1. Existing AI output architecture (pre V2-05)

| Path | Format | Validation |
|------|--------|------------|
| CHAT (`DialogueResponder`) | Free text (+ optional `ANSWER:` prefix) | `LlmOutputPolicy` size only (V2-04) |
| `LlmDecisionParser` | Line `KIND: text` or loose JSON `kind`/`utterance` | No schema, unknown fields ignored |
| `LlmStructuredSemanticInterpreter` | `route=…;goal=…;ambiguity=…` line | Ad-hoc map split |
| Planner | Deterministic `ActionPlan` from discovery | `ActionPlanValidator` (existing) |
| Tool execution | `ProposedAction` → Risk → Policy → Gate → Request | Strong 8E contracts |

No single envelope, no unknown-field reject policy, no tool-arg semantic validation on LLM text.

---

## 2. Problems found

1. Multiple incompatible “structured” dialects (prefix / regex JSON / semicolon line).
2. Malformed LLM text could not be systematically rejected before any tool path.
3. Semantic interpreter had no schema version or field whitelist.
4. No bounded repair; failures either silent-null or legacy free parse.
5. LLM could theoretically emit fake `ALLOW` / permission-like fields with no reject.
6. CHAT free text and machine output were not explicitly separated by task obligation.

---

## 3. Unified structured model

Package: `core/src/main/kotlin/com/jarvis/core/aioutput/`

```text
LLM raw text
    ↓
StructuredAiOutputParser (syntax / MiniJson)
    ↓
StructuredAiSchemaValidator (types, required, enums, unknown fields)
    ↓
StructuredAiSemanticValidator (tools, args, plan integrity)
    ↓
StructuredAiEnvelope (immutable)
    ↓
Adapters → ProposedAction / ActionPlan / SemanticUnderstanding
    ↓
existing Risk → Policy → SafetyGate → Executor (unchanged)
```

**Response types (only these):**

| Type | Role |
|------|------|
| `ANSWER` | Conversational / semantic route hint |
| `CLARIFICATION` | Question + `ClarificationReason` |
| `TOOL_REQUEST` | `toolId` + string args (intent only) |
| `PLAN` | Goal + step drafts → existing `ActionPlan` |
| `NO_ACTION` | Explicit non-action (not failure) |
| `CONFIRMATION_REQUIRED` | Model *suggestion* only — policy decides |

**Tasks that require structured output:** `SEMANTIC_INTERPRETATION`, `TOOL_REQUEST`, `PLAN`.  
**CHAT:** free text allowed via `StructuredAiChatBridge.wrapChatText`.

---

## 4. Schema / version

- `schemaVersion`: **`"1"`** (`StructuredAiLimits.SCHEMA_VERSION`)
- Unsupported version → validation failure → repair/fallback (no execute)
- Prompt instructions versioned separately via `StructuredAiOutputPipeline.jsonInstruction(task)`
- Prompt change does not auto-accept old incompatible JSON without version match

---

## 5. Parser

- `MiniJson` — pure Kotlin JSON (avoids Android `org.json` unit-test stubs)
- `StructuredAiOutputParser.extractJsonObject`:
  - size cap `MAX_JSON_CHARS = 4096`
  - nesting depth ≤ 4
  - isolate fenced ` ```json``` ` or first `{…}`
  - malformed / truncated → `parse=FAIL`

---

## 6. Validators

**Schema:** required fields per type, enum checks, **unknown top-level / step fields → reject**.

**Semantic:**

- ANSWER: non-empty text
- CLARIFICATION: question + reason
- TOOL_REQUEST: tool in `CatalogToolRegistry` (when provided), required/known args vs `ToolDefinition`, forbidden keys (`allow`, `permission`, `autonomy`, `token`, …)
- PLAN: valid `PlanStepKind`, tools exist, then **`ActionPlanValidator`**
- NO_ACTION: no tool/steps payload
- CONFIRMATION_REQUIRED: summary present; still **not** user confirmation / ALLOW

---

## 7. Semantic integration

`LlmStructuredSemanticInterpreter` now:

1. Prompts for schema v1 JSON (`StructuredAiOutputPipeline.jsonInstruction(SEMANTIC_INTERPRETATION)`).
2. Runs `StructuredAiOutputPipeline.processWithRepair`.
3. On Valid → `StructuredAiAdapters.toSemanticUnderstanding`.
4. On Invalid → **legacy** `route=…;` line fallback (compat with V2-02 tests / older models).
5. Exposes `lastStructuredDiagnostics` → `RequestTrace.applyStructured` from orchestrator.

---

## 8. Tool integration

```text
TOOL_REQUEST envelope
 → StructuredAiAdapters.toProposedAction
 → ProposedActionMapper (preferred) / ActionKind.UNKNOWN fallback
 → (caller) RiskClassifier → AutonomyPolicy → SafetyGate → ToolExecutionRequestFactory
```

- Uses existing `ToolDefinition` / `CatalogToolRegistry` — **no second registry**.
- Invalid / unknown tool → **no ProposedAction from Valid path**; Invalid result cannot execute.
- Args bound as immutable `Map` on `ProposedAction` (fingerprint path unchanged).

---

## 9. PLAN integration

```text
PLAN envelope → StructuredAiAdapters.toActionPlanDraft → ActionPlanValidator
```

Reuses existing `ActionPlan` / `PlanStep` / `ActionPlanValidator`.  
Live AgentExecutionLoop not expanded for V2-05; conversion is safe and unit-tested.

---

## 10. CHAT compatibility

- Dialogue path unchanged: free text + V2-04 `LlmOutputPolicy`.
- Optional `StructuredAiChatBridge.wrapChatText` for callers that want a typed ANSWER without forcing JSON generation.
- `LlmDecisionParser` retained for legacy decide APIs (not the security boundary for tools).

---

## 11. Repair / fallback

- `processWithRepair`: **max 1** repair attempt (`MAX_REPAIR_ATTEMPTS = 1`).
- Repair logged: `repairAttempted`, `repairResult=SUCCESS|FAIL|empty`.
- **CHAT:** structured failure → free-text / legacy OK.
- **TOOL_REQUEST / PLAN:** structured failure → **do not execute** (Invalid only).
- Semantic: JSON fail → legacy line fallback (no tool execution from that parse).

---

## 12. Security invariants

| Rule | Enforced |
|------|----------|
| Invalid structured → no execution | Yes (Invalid has no adapter Valid path) |
| Unknown tool → no execution | `INVALID_TOOL` |
| Invalid args → no execution | missing/unknown/forbidden |
| Malformed plan → no execution | ActionPlanValidator |
| LLM `confirmed` / `ALLOW` / `permission` fields | Rejected as unknown or forbidden args |
| LLM “done” ≠ SUCCESS | Prompt + no verifier bypass |
| Structured ≠ permission / autonomy | Explicit; Gate untouched |

---

## 13. Diagnostics

`RequestTrace` + summary line fields:

- `structured`, `schema`, `stype`, `parse`, `sval`, `repair`, `repairRes`, `sreason`

Also `Jarvis.Trace` via existing emit. No secrets / full prompts.

---

## 14. Test matrix

`core/.../aioutput/StructuredAiOutputPipelineTest` covers:

| Category | Cases |
|----------|--------|
| Valid | ANSWER, CLARIFICATION, TOOL_REQUEST, PLAN, NO_ACTION, CONFIRMATION_REQUIRED |
| Invalid syntax | malformed, truncated |
| Invalid schema | unknown type, unknown fields, unsupported schema, empty answer |
| Invalid semantics | unknown tool, missing args, forbidden args, NO_ACTION+tool |
| Security | fake `permission` / `autonomy` / `allow` |
| Repair | one success; one still invalid |

Semantic: legacy line + JSON schema v1 (`LlmStructuredSemanticInterpreterTest`).

---

## 15. Device tests

Not run on physical device in this session. Recommended smoke:

1. CHAT cloud — free text still works; Trace without forced structure.
2. Ambiguous phrase → CLARIFY (fast or structured).
3. Inject invalid JSON on semantic path → no tool execution; legacy or clarify.
4. Offline local structured semantic JSON.
5. Valid TOOL_REQUEST unit path already proven; on-device still goes through SafetyGate.

---

## 16. Performance

- Parse/validate is pure CPU MiniJson; typical `parseMs` ≈ 0–2 ms in unit runs.
- One optional repair = one extra LLM call only when caller supplies `repairFn` (semantic currently `repairFn=null` to avoid silent second cloud call without orchestrator control).

---

## 17. Build

```bash
./gradlew :core:testDebugUnitTest
./gradlew :brain:testDebugUnitTest
./gradlew :app:assembleDebug
```

**Result:** BUILD SUCCESSFUL (2026-10-01).

---

## 18. Known limitations

1. Live planner / agent loop not yet *driven* by LLM PLAN envelopes end-to-end.
2. Semantic repair LLM call not wired in interpreter (hook ready on pipeline).
3. Provider native JSON-schema mode (Nara) not enabled — instruction + post-validation only.
4. `LlmDecisionParser` still exists for older decide APIs.
5. CHAT does not require JSON (by design).
6. Device E2E matrix not executed here.

---

## 19. What remains for V2-06

**V2-06 — Latency Engineering** (`Jarvis_Roadmap_V2.md` §9):

- End-to-end timing: wake → ASR → understanding → routing → context → LLM TTFT → tools → TTS.
- p50/p90/p95/p99; time-to-first-ack / useful content / audio / completion.
- Optimize perceived speed without breaking V2-01…V2-05 contracts.

**Stop rule:** V2-06 not started in this delivery.

---

## Appendix: key files

| Path | Role |
|------|------|
| `core/.../aioutput/StructuredAiModels.kt` | Envelope, types, limits, diagnostics |
| `core/.../aioutput/MiniJson.kt` | Pure JSON parser |
| `core/.../aioutput/StructuredAiOutputParser.kt` | Syntax |
| `core/.../aioutput/StructuredAiSchemaValidator.kt` | Schema |
| `core/.../aioutput/StructuredAiSemanticValidator.kt` | Semantic + adapters |
| `core/.../aioutput/StructuredAiOutputPipeline.kt` | Orchestration + repair |
| `core/.../aioutput/StructuredAiChatBridge.kt` | CHAT free-text wrap |
| `brain/.../semantic/LlmStructuredSemanticInterpreter.kt` | Shared infrastructure |
| `core/.../runtime/RequestTrace.kt` | Structured telemetry |
