# 02 — Архитектура (текущая)

## Gradle-модули (`settings.gradle.kts`)

```
:app
:core
:wakeword-engine
:audio-engine
:asr-engine
:tts-engine
:brain
:tools
:screen-control
:overlay-ui
:background-service
:connectivity-router
:permissions-onboarding
```

### Роли модулей

| Модуль | Назначение |
|--------|------------|
| **app** | UI, VoiceService/Engine/Controller, BrainGraph wiring, ModelManager, settings, companion observers, cloud ASR/TTS bridges |
| **brain** | `AssistantOrchestrator`, `DialogueResponder`, AI providers/router, LiteRT/LLM контекст, semantic interpreters |
| **core** | События, NLP, autonomy/planner/agent loop, memory, routines, streaming contracts, vision/remote stubs, diagnostics |
| **tools** | Нативные Android tools (flashlight, volume, launcher, sms, ui_*, …) |
| **asr-engine** | Sherpa-ONNX streaming ASR |
| **tts-engine** | Piper/Sherpa offline TTS + TtsPlayer |
| **wakeword-engine** | Wake / KWS (openWakeWord ONNX) |
| **audio-engine** | AudioCapture PCM |
| **screen-control** | Accessibility service / UI automation hooks |
| **background-service** | JarvisService FGS + BootReceiver |
| **permissions-onboarding** | Helpers разрешений HyperOS/Android |
| **overlay-ui** | Overlay orb |
| **connectivity-router** | Connectivity helpers |

## Владельцы ответственности

| Concern | Owner |
|---------|-------|
| Микрофон / Wake / ASR / TTS lifecycle | `VoiceService` + `VoiceEngine` + `VoiceController` |
| Оркестрация запроса | `AssistantOrchestrator` |
| Composition / feature registry | `BrainGraph` |
| Cloud/local chat | `RoutedChatLlmEngine` → `AIRouter` |
| Tool policy (live) | `RiskBasedPolicy` |
| Tool policy (agent loop library) | `AutonomyActionPipeline` |
| Companion proactive UI | `CompanionProactive` |
| Stage 25 ProactiveAgent | В BrainGraph; не основной UI-путь Companion |

## Живой runtime pipeline (голос / DEBUG / UI text)

```
User
  → VoiceEngine (Wake/VAD/ASR)  OR  DebugCommandReceiver  OR  UI notifyRequest
  → VoiceController
  → VoiceService
  → BrainGraph.build().process(UserRequest)
  → AssistantOrchestrator:
       pending confirm/clarify
       FollowUpResolver
       incomplete-command check
       CompoundCommandSplitter
       processSingle:
         SkillRetriever (+ CapabilityDiscovery logging)
         BrainRouter → SkillCall | Clarify | Chat
         SkillCall → RiskBasedPolicy → ToolExecutor
         Chat → DialogueResponder (templates → RoutedChatLlmEngine)
  → VoiceController.speak / Hybrid TTS
```

## Документированный «полный» agent path (библиотека)

В коде и тестах существуют: DeterministicAIPlanner, AgentExecutionLoop, Memory2, AsrRouter/TtsRouter stubs (Stage 22), VisionRouter, ProactiveAgent, Files/Browser agents, plugins/MCP.

**Факт аудита V2-00:** этот стек в основном **не вызывается** из обычного `AssistantOrchestrator.process()`; он создаётся в `BrainGraph`, покрыт unit-тестами и dry-run DEBUG helpers.

## События и состояние

- `JarvisEvents` (SharedFlow): `AssistantState`, `AssistantEvent` (Transcript, Request, Response, Intent, Tool, Wake, …).  
- `AssistantState`: OFFLINE, STARTING, IDLE/READY, LISTENING, THINKING, EXECUTING, SPEAKING, ERROR.

## Routing матрица (аудит)

| Тип запроса | Путь |
|-------------|------|
| «включи фонарик» | Skill → tool |
| Follow-up «тише» | FollowUpResolver → tool |
| «привет» / «который час» / «кто ты» / шутки | Dialogue templates (LLM не нужен) |
| Открытый чат | DialogueResponder → cloud и/или local GGUF |
| Список через запятую | CompoundCommandSplitter → sequential skills |
| Offline | skills + templates + local GGUF |

## AI Router (чат)

Правила: privacy → local; offline → local; online+complex → cloud; online+simple → local unless `preferCloudWhenOnline`.  
На устройстве часто `prefer_cloud_llm=true` → чат предпочитает облако.

## Безопасность tools

- Live: `RiskBasedPolicy` (Allow / Confirm / Deny по RiskLevel).  
- Настройка `confirm_sensitive` (по умолчанию true).  
- Agent-loop: отдельный pipeline RiskClassifier → AutonomyPolicy → ActionSafetyGate (library path).

## Тесты (из аудита / reports)

- Исторический baseline closure New Roadmap: **415/415** unit (387 core + 28 brain aiprovider).  
- Device suite STAGE19–27: **19/19** DEBUG_COMMAND + autonomy dry-runs.  
- V2-08 voice unit: 13/13; V2-09 voice tests PASS; `assembleDebug` SUCCESS на отчётных датах.
