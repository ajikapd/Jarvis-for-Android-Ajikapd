# JARVIS --- Новый стратегический Roadmap

## 0. Новая концепция

Jarvis переходит от модели «исключительно offline» к **hybrid
online/offline**.

Главный принцип:

> При наличии интернета Jarvis использует облачные AI-сервисы и внешние
> инструменты для максимального качества, скорости и функциональности.
> При отсутствии интернета он автоматически переходит на локальные
> возможности.

Пользователь не должен вручную переключать режим.

Целевая архитектура:

``` text
User
 ↓
Voice / Text
 ↓
Understanding
 ↓
Context + Memory
 ↓
AI Provider / AI Router
 ↓
AI Planner
 ↓
ActionPlan
 ↓
Tool Discovery
 ↓
Risk Classifier
 ↓
Autonomy Policy
 ↓
Safety Gate
 ↓
Executor
 ↓
Verification
 ↓
Memory / Learning
 ↓
Proactive Agent
```

Источники возможностей:

``` text
Native Android Tools
Plugins
MCP
Cloud Services
Local Services
```

Основной принцип безопасности:

``` text
LLM proposes
Policy decides
Executor executes
Verifier verifies
```

LLM никогда не должна напрямую выполнять Android-действия или
самостоятельно разрешать себе действия.

------------------------------------------------------------------------

# 1. Уже готовый фундамент

Stages 0--8B сохраняются и не переписываются без необходимости.

### 0--3.5

-   PhoneObservation
-   PhoneContext
-   ContextAggregator
-   SystemStateObserver
-   Accessibility observation
-   NotificationListener
-   UsageStats
-   provenance/source
-   confidence
-   timestamps
-   snapshots
-   diagnostics
-   debounce/coalesce

### 4--4.5

-   richer Accessibility observation
-   System Presence
-   единый VoicePipeline / VoiceService
-   cold assist
-   assistant role
-   anti-storm protection

### 5--5.5

-   memory candidates
-   SQLite companion memory
-   MemoryEntry lifecycle
-   ACTIVE / UPDATED / STALE / EXPIRED / DELETED
-   OBSERVED vs USER_STATED
-   SECRET rejection

### 6--6.5

-   BehaviorLearning
-   ObservationWindow
-   PatternDetector
-   RoutineDetector
-   ConfidenceModel
-   Decay
-   BehaviorPolicy
-   feedback
-   cooldown
-   recommend-only semantics

### 7--7.5

-   ProactiveDecisionEngine
-   ContextSnapshot
-   ContextMatchResult
-   RelevanceResult
-   budget
-   cooldown
-   candidate cap
-   ranking
-   NO_OP / SUGGESTION / QUESTION / CONFIRMATION_REQUEST

### 8A

-   AutonomyVerdictType
-   AutonomyLevel
-   DecisionSource
-   ProposedAction
-   bounded arguments

### 8B

-   ActionKind
-   ActionRiskClass
-   ActionRiskReasonCode
-   ActionRiskAssessment
-   deterministic ActionRiskClassifier

Wake pipeline считается стабильным и не переписывается в рамках этого
roadmap без отдельной задачи.

------------------------------------------------------------------------

# 2. Stage 8C --- Autonomy Policy

Создать детерминированный слой:

``` text
ProposedAction
 ↓
ActionRiskAssessment
 ↓
AutonomyPolicy
 ↓
NO_ACTION / REQUIRE_CONFIRMATION / ALLOW / DENY
```

Policy учитывает:

-   ProposedAction
-   ActionRiskAssessment
-   AutonomyLevel
-   DecisionSource
-   существующие policy-данные

Требования:

-   deterministic
-   side-effect free
-   fail closed
-   без LLM
-   без сети
-   без Android execution
-   unit-testable

Цель: отделить Risk Classification от решения о допустимости действия.

------------------------------------------------------------------------

# 3. Stage 8D --- Safety Gate

Отдельный слой между Policy и будущим Executor.

Проверяет:

-   аргументы;
-   ActionKind;
-   risk;
-   permissions;
-   актуальность контекста;
-   confirmation state;
-   срок действия решения;
-   целостность ActionPlan;
-   запрещённые параметры.

Safety Gate не выполняет действие.

------------------------------------------------------------------------

# 4. Stage 8E --- Tool Execution Contracts

Ввести общий контракт:

``` text
ToolDefinition
ToolInput
ToolExecutionRequest
ToolExecutionResult
ToolError
ToolMetadata
```

Инструмент должен описывать:

-   id
-   name
-   description
-   input schema
-   permissions
-   risk metadata
-   availability
-   execution contract

На этом этапе не подключать десятки реальных инструментов.

------------------------------------------------------------------------

# 5. Stage 8F --- Tool Registry

Единый реестр возможностей.

Разделить:

``` text
Tool Definition
Executable Tool
Capability
```

Registry хранит:

-   capability;
-   аргументы;
-   permissions;
-   risk;
-   source;
-   availability;
-   local/remote;
-   Native / Plugin / MCP;
-   metadata.

------------------------------------------------------------------------

# 6. Stage 9 --- Capability / Tool Discovery

Не передавать LLM весь Tool Registry.

``` text
User Request
 ↓
Capability Discovery
 ↓
Relevant Tools
 ↓
Planner
```

Discovery учитывает:

-   семантическую релевантность;
-   permissions;
-   availability;
-   risk;
-   online/offline status;
-   capability requirements.

------------------------------------------------------------------------

# 7. Stage 10 --- AI Provider Layer

Единый интерфейс:

``` text
AIProvider
├── CloudLLMProvider
├── LocalLLMProvider
└── Future providers
```

Provider должен учитывать:

-   availability;
-   capabilities;
-   streaming;
-   context limits;
-   latency;
-   errors;
-   cancellation.

------------------------------------------------------------------------

# 8. Stage 10.5 --- AI Router

Автоматический выбор AI provider.

Пример:

``` text
Internet + complex → Cloud
Internet + simple → configurable Local/Cloud
No Internet → Local
Privacy-restricted → configured provider
```

Cloud не должен быть обязательным.

------------------------------------------------------------------------

# 9. Stage 11 --- AI Planner

Полноценное планирование:

``` text
User Goal
 ↓
Planner
 ↓
ActionPlan
```

ActionPlan поддерживает:

-   последовательность;
-   зависимости;
-   условия;
-   ветвление;
-   ожидание;
-   retry;
-   cancellation;
-   confirmation;
-   результаты предыдущих шагов;
-   bounded parameters.

Planner только создаёт план.

------------------------------------------------------------------------

# 10. Stage 12 --- Agent Execution Loop

``` text
PLAN
 ↓
EXECUTE STEP
 ↓
RESULT
 ↓
ANALYZE
 ↓
CONTINUE / MODIFY / RETRY / STOP
```

Каждый шаг снова проходит:

``` text
RiskClassifier
 → AutonomyPolicy
 → SafetyGate
```

------------------------------------------------------------------------

# 11. Stage 13 --- Result Verification

Проверять не только ответ инструмента, но и фактическое состояние:

``` text
Tool result
 +
Observed state
 ↓
Verification
```

Результаты:

-   success
-   partial success
-   failure
-   unknown
-   retryable
-   irreversible

------------------------------------------------------------------------

# 12. Stage 14 --- Native Android Tools

Постепенно подключить:

-   приложения;
-   flashlight;
-   volume;
-   media;
-   notifications;
-   settings;
-   intents;
-   clipboard;
-   files;
-   system state;
-   безопасные accessibility actions;
-   другие Android APIs.

Все действия проходят общий pipeline.

------------------------------------------------------------------------

# 13. Stage 15 --- Plugin Architecture

Расширяемая система Plugin / ToolProvider.

Plugin:

-   manifest;
-   version;
-   capabilities;
-   permissions;
-   configuration;
-   lifecycle;
-   tools.

Core не должен зависеть от конкретных plugins.

------------------------------------------------------------------------

# 14. Stage 16 --- MCP Integration

MCP становится источником ToolDefinition:

``` text
Tool Registry
├── Native
├── Plugin
└── MCP
```

MCP Tool должен выглядеть для Planner как обычный Tool.

Не создавать отдельную архитектуру исполнения MCP.

------------------------------------------------------------------------

# 15. Stage 17 --- Files Agent

Возможности:

-   search;
-   inspect;
-   read;
-   create;
-   rename;
-   move;
-   organize;
-   delete;
-   metadata.

Опасные операции проходят Risk/Policy.

------------------------------------------------------------------------

# 16. Stage 18 --- Web / Browser Agent

При наличии интернета:

``` text
Search
 ↓
Open
 ↓
Inspect
 ↓
Extract
 ↓
Act
 ↓
Verify
```

Browser actions --- обычные Tools.

------------------------------------------------------------------------

# 17. Stage 19 --- AI-generated Routines

Пользователь может описать сценарий естественным языком.

``` text
Natural language
 ↓
AI-generated ActionPlan
 ↓
Validate
 ↓
Preview
 ↓
User approval
 ↓
Saved Routine
```

BehaviorLearning может предлагать сценарии на основе повторяющихся
действий.

------------------------------------------------------------------------

# 18. Stage 20 --- Visual Workflow Editor

Редактор работает с тем же ActionPlan:

``` text
Trigger
 ↓
Action
 ↓
Condition
 ├── A
 └── B
 ↓
Loop
 ↓
Action
```

AI и визуальный редактор используют единый формат.

------------------------------------------------------------------------

# 19. Stage 21 --- Memory 2.0

Расширить память:

``` text
Memory
Preference
Behavior
Routine
Context
Feedback
Execution history
Tool performance
```

Не превращать все наблюдения в долговременную память.

------------------------------------------------------------------------

# 20. Stage 22 --- Hybrid Voice Stack

``` text
ASR Router
├── Cloud ASR
└── Local ASR

TTS Router
├── Cloud TTS
└── Local TTS
```

Online → качественные cloud providers.

Offline → local fallback.

Wake pipeline не переписывать без отдельной задачи.

------------------------------------------------------------------------

# 21. Stage 23 --- Streaming / Low Latency

Цель:

``` text
Streaming ASR
 ↓
LLM streaming
 ↓
Streaming TTS
```

Добавить:

-   interruption;
-   cancellation;
-   barge-in;
-   timeout;
-   fallback;
-   partial results.

------------------------------------------------------------------------

# 22. Stage 24 --- Vision

``` text
Screenshot / Camera
 ↓
Vision Provider
 ↓
Context
 ↓
Planner
```

Где возможно --- cloud/local routing.

------------------------------------------------------------------------

# 23. Stage 25 --- Proactive Agent

Объединить:

``` text
Context
+
Behavior
+
Routine
+
Memory
+
ProactiveDecisionEngine
```

с Agent layer.

Jarvis может не только отвечать, но и предлагать полезные действия.

Автоматические действия ограничиваются AutonomyPolicy и SafetyGate.

------------------------------------------------------------------------

# 24. Stage 26 --- Multi-device / Remote Tools

После стабильности Android core:

``` text
Android
PC
Web
Other devices
```

через общий Tool/MCP layer.

------------------------------------------------------------------------

# 25. Stage 27 --- Advanced UI

После стабилизации backend:

-   dashboard;
-   execution timeline;
-   current plan;
-   agent state;
-   memory;
-   routines;
-   tools;
-   plugins;
-   model/provider;
-   voice settings;
-   theme;
-   history;
-   diagnostics.

UI показывает состояние системы, но не дублирует core-логику.

------------------------------------------------------------------------

# 26. Приоритет разработки

``` text
8C
 ↓
8D
 ↓
8E
 ↓
8F
 ↓
9
 ↓
10
 ↓
10.5
 ↓
11
 ↓
12
 ↓
13
 ↓
14
 ↓
15
 ↓
16
 ↓
17–21
 ↓
22
 ↓
23
 ↓
24–27
```

Не перескакивать через фундаментальные слои ради демонстрационных
функций.

------------------------------------------------------------------------

# 27. Идеи, перенятые из сторонних проектов

Следующие направления следует использовать как идеи, а не копировать
архитектуру целиком:

-   AI-driven automation;
-   agentic workflows;
-   MCP;
-   plugins;
-   code generation;
-   Files / Browser / PC automation;
-   memory;
-   context awareness;
-   proactive behavior;
-   result verification;
-   AI-generated routines.

Главное отличие нашей архитектуры --- наличие уже реализованных Context,
Memory, BehaviorLearning, ProactiveDecisionEngine, Risk Classification и
Autonomy contracts.

------------------------------------------------------------------------

# 28. Что запрещено архитектурно

Нельзя:

-   превращать LLM в executor;
-   давать LLM прямой Android access;
-   разрешать опасные действия только по confidence;
-   смешивать RiskClassifier и AutonomyPolicy;
-   смешивать Policy и Executor;
-   делать отдельную execution architecture для каждого MCP;
-   передавать LLM весь Tool Registry;
-   делать Cloud единственным AI;
-   удалять Local fallback;
-   переписывать Wake pipeline без причины;
-   ломать готовые stages ради новой архитектуры;
-   добавлять ненужные зависимости;
-   реализовывать все stages одновременно.

------------------------------------------------------------------------

# 29. Definition of Done

Каждый Stage считается завершённым только после:

1.  аудита существующей реализации;
2.  минимально необходимых изменений;
3.  unit tests;
4.  regression checks;
5.  сборки затронутых модулей;
6.  проверки архитектурных границ;
7.  краткого отчёта;
8.  фиксации следующего Stage.

После завершения Stage агент останавливается и ждёт следующую задачу.

------------------------------------------------------------------------

# 30. Текущая точка

**Готово:** Stage 8B–8F … Stage 27 --- Advanced UI (весь New Roadmap 8C→27).

**Следующий этап:** нет — roadmap закрыт. Дальнейшие задачи выдаются отдельно.

Hybrid online/offline, Agent/Planner/Verification, Native/Plugin/MCP, Files/Browser Agents,
Routines, Workflow, Memory 2.0, Voice/Vision routers, Streaming contracts, Proactive Agent,
Remote tools, Diagnostics UI — реализованы.

Wake pipeline не переписывался.
