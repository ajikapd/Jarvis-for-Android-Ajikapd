# Jarvis Roadmap V2 — Independent Android Companion

**Версия:** 2.0  
**Дата:** 2026-10-01  
**Статус:** рабочий roadmap после закрытия New Roadmap Stages 8C–27

---

# 0. Назначение Roadmap V2

## Главная цель

Превратить существующий Android-проект Jarvis из архитектурно богатого ассистента в **цельного, быстрого, надёжного и максимально независимого AI-компаньона для Android**.

Jarvis должен быть не просто голосовой системой команд, а постоянным интеллектуальным слоем над устройством, который:

- естественно понимает речь и текст;
- поддерживает полноценный диалог с контекстом;
- помогает советом, объяснениями, поиском и рассуждением;
- использует cloud AI при наличии сети;
- автоматически переходит на local AI при отсутствии сети, квоте или сбое;
- понимает состояние устройства, текущего приложения и окружающего контекста;
- умеет работать с экраном, изображениями и камерой в рамках разрешённых Android-возможностей;
- использует долговременную память;
- понимает привычки и routines;
- может быть проактивным;
- может строить многошаговые планы;
- умеет выполнять действия на телефоне;
- может работать с приложениями, файлами, браузером, удалёнными устройствами, plugins и MCP;
- проверяет результат собственных действий;
- умеет исправляться после ошибок;
- не требует от пользователя запоминать фиксированные команды;
- быстро начинает отвечать;
- поддерживает streaming;
- не теряет работоспособность без облака;
- остаётся безопасным, объяснимым и контролируемым.

Целевой результат:

> Пользователь должен воспринимать Jarvis не как отдельное приложение, а как своего постоянного персонального помощника, который может понять задачу, подобрать нужные возможности, выполнить её в допустимых пределах, проверить результат и продолжить диалог с учётом контекста.

---

# 1. Baseline после закрытия New Roadmap 8C–27

Предыдущий roadmap считается закрытым.

Подтверждены:

- 415/415 unit tests;
- device smoke;
- AI Provider Registry;
- AI Router;
- Deterministic AI Planner;
- Agent Execution Loop;
- RiskClassifier;
- AutonomyPolicy;
- ActionSafetyGate;
- Tool Execution Contracts;
- Tool Registry;
- Capability Discovery;
- Native Android Tools;
- Plugin architecture;
- MCP architecture;
- Files Agent;
- Browser Agent;
- AI-generated routines;
- Memory 2.0;
- Hybrid Voice Stack;
- Streaming;
- Vision;
- ProactiveAgent;
- Remote layer;
- Diagnostics UI.

Текущая логическая pipeline:

```text
User (voice / DEBUG_COMMAND / UI)
        ↓
VoicePipeline / VoiceService
        ↓
Understanding
        ↓
Context + Memory
        ↓
AI Provider Registry + AI Router
        ↓
AI Planner
        ↓
Tool Discovery
        ↓
Agent Execution Loop
        ↓
RiskClassifier
        ↓
AutonomyPolicy
        ↓
ActionSafetyGate
        ↓
Tool Executor
        ↓
Result Verifier
        ↓
Memory / Routines / Proactivity
        ↓
TTS + UI
```

Важно: наличие этих компонентов не означает автоматически production-quality. V2 начинается с аудита реального runtime поведения.

---

# 2. Основные принципы V2

## 2.1. Android Jarvis — главный продукт

Android implementation является source of truth.

Другие проекты и эксперименты используются как исследовательская среда:

- LocalJarvis — для локальных LLM/ASR/TTS, latency и offline-экспериментов;
- OpenClaw/Termux-ветка — как источник идей по агентности, инструментам и внешним интеграциям.

Не переносить Linux-specific архитектуру в Android без необходимости.

---

## 2.2. Hybrid-first

Cloud AI и local AI должны быть равноправными провайдерами.

```text
Cloud Provider A
Cloud Provider B
NaraRouter
Other OpenAI-compatible
Local LLM
        ↓
Provider Layer
        ↓
AI Router
        ↓
Jarvis Brain
```

Cloud не является обязательным.

Offline mode должен сохранять базовую полезность Jarvis.

---

## 2.3. Separation of responsibilities

Сохранять строгие границы:

```text
RiskClassifier
    ↓
ActionRiskAssessment

AutonomyPolicy
    ↓
AutonomyVerdict

ActionSafetyGate
    ↓
PASS / BLOCK

Executor
    ↓
actual execution

Verifier
    ↓
actual result
```

Не объединять эти уровни ради удобства.

---

## 2.4. Никакого прямого LLM → Android execution

LLM только предлагает structured intent / action / plan.

```text
LLM
 ↓
validated structured output
 ↓
Planner
 ↓
Policy
 ↓
Safety
 ↓
Executor
```

---

## 2.5. End-to-end важнее наличия классов

Нельзя считать функцию готовой только потому, что:

- существует класс;
- есть интерфейс;
- есть stub;
- есть unit test;
- включён feature flag;
- Planner умеет построить соответствующий объект.

Нужно проверять полный сценарий:

```text
User says X
    ↓
Jarvis understands X
    ↓
selects right capability
    ↓
validates request
    ↓
executes X
    ↓
observes actual state
    ↓
verifies result
    ↓
responds naturally
```

---

# 3. V2-00 — Full System Audit

Это обязательная первая стадия V2.

До крупных новых feature-работ провести полный технический и продуктовый аудит.

## Проверить

### Architecture

- модули и зависимости;
- ownership каждого state;
- lifecycle;
- concurrency;
- threading;
- cancellation;
- timeout propagation;
- error propagation;
- persistence;
- duplicate responsibilities;
- duplicated models;
- duplicated validators;
- god classes;
- hidden side effects;
- feature flags;
- deprecated compatibility layers;
- dead code;
- redundant services;
- race conditions.

### Android

- Manifest;
- permissions;
- services;
- receivers;
- VoiceService;
- VoiceInteraction mechanisms;
- Accessibility;
- Notification Listener;
- background behavior;
- lifecycle;
- screen-off behavior;
- lock-screen behavior;
- reboot behavior;
- battery restrictions;
- MIUI-specific behavior.

### AI

- AI Provider;
- AI Router;
- BrainGraph;
- Planner;
- Agent loop;
- context assembly;
- memory injection;
- output parsing;
- fallback logic;
- provider selection;
- token handling;
- streaming.

### Tools

- Registry;
- Discovery;
- native tools;
- Browser Agent;
- Files Agent;
- Plugins;
- MCP;
- Remote;
- Executor;
- Verifier.

### Companion

- Context;
- Memory;
- Behavior Learning;
- Routines;
- ProactiveAgent.

### Voice

- Wake;
- VAD;
- ASR;
- NLP/understanding;
- LLM;
- TTS;
- audio queue;
- interruption;
- barge-in.

### UI

- Dashboard;
- Companion panel;
- settings;
- model settings;
- cloud settings;
- diagnostics;
- memory;
- routines;
- workflows;
- tools;
- permissions;
- states;
- errors;
- loading;
- empty states.

---

## Classification

Каждый subsystem получить один статус:

```text
SOLID
WORKING
WORKING_BUT_IMPERFECT
PARTIAL
STUB
BROKEN
REDUNDANT
OUTDATED
ARCHITECTURALLY_WRONG
REQUIRES_REWRITE
```

---

## Обязательный audit report

Создать:

`md_files/JARVIS_V2_FULL_AUDIT.md`

Отчёт должен содержать:

1. Executive summary.
2. Current architecture.
3. Runtime pipeline.
4. Routing matrix.
5. LLM quality findings.
6. Full latency breakdown.
7. Voice findings.
8. Planner/Agent findings.
9. Tool findings.
10. Memory findings.
11. Proactivity findings.
12. UI audit.
13. Device/lifecycle audit.
14. Security audit.
15. Performance/battery findings.
16. Stub/partial inventory.
17. Rewrite candidates.
18. Refactor candidates.
19. Keep-as-is components.
20. P0/P1/P2/P3 matrix.
21. Dependencies between fixes.
22. Recommended order.
23. Proposed target architecture.
24. Exact next implementation stage.

---

# 4. V2-01 — Architecture Hardening

После аудита исправить подтверждённые архитектурные проблемы.

## Цели

- один source of truth для state;
- один execution path;
- один routing layer;
- единый context model;
- единый error/cancellation model;
- отсутствие скрытого обхода safety chain.

## Удалить/сократить

- dead code;
- устаревшие compatibility layers;
- ненужные feature flags;
- duplicated validators;
- duplicated models;
- redundant adapters.

## Важно

8A–8F не переписывать автоматически.

Rewrite допустим только при доказанной реальной проблеме.

---

# 5. V2-02 — Brain / Understanding V2

Перевести Jarvis от command-oriented processing к semantic-first processing.

Целевая схема:

```text
User Input
   ↓
Normalize
   ↓
Conversation Context
   ↓
Semantic Understanding
   ↓
Intent / Goal / Constraints
   ↓
Capability Selection
   ↓
Answer / Tool / Plan / Clarification
```

## Jarvis должен понимать

- разговорную речь;
- неполные фразы;
- местоимения;
- исправления;
- follow-up вопросы;
- контекст;
- скрытую цель;
- естественные просьбы.

Старая phrase/skill similarity система может остаться быстрым механизмом прямых команд, но не должна быть главным ограничением естественного языка.

---

# 6. V2-03 — Hybrid AI Router V2

Перепроверить и при необходимости переработать AIRouter.

Router должен учитывать:

- online/offline;
- provider health;
- quota;
- latency;
- model availability;
- task complexity;
- privacy;
- context size;
- quality requirement;
- streaming support;
- cost;
- recent failures.

Пример:

```text
simple command
    → deterministic/local

normal conversation
    → fastest suitable cloud

complex reasoning
    → strongest suitable cloud

privacy-sensitive
    → local

cloud failure
    → alternate cloud/local

offline
    → local
```

NaraRouter не должен становиться hardcoded dependency.

---

# 7. V2-04 — LLM Quality V2

Цель — улучшить реальные ответы Jarvis.

## Проверить

- system prompt;
- persona;
- conversation history;
- memory context;
- device context;
- tool descriptions;
- planner prompt;
- model selection;
- context size;
- token budget;
- output format;
- unnecessary context;
- duplicate context;
- fallback;
- accidental deterministic interception.

## Создать единый Context Builder

```text
System
+
User profile
+
Relevant memory
+
Conversation
+
Current device state
+
Current app
+
Relevant capabilities
+
Relevant tool schemas
+
Task
```

Передавать модели только релевантный контекст.

---

# 8. V2-05 — Structured AI Output

LLM output должен быть структурированным.

Возможные типы:

```text
ANSWER
TOOL_REQUEST
PLAN
CLARIFICATION
CONFIRMATION_REQUIRED
NO_ACTION
```

Невалидный output:

```text
LLM
 ↓
parse
 ↓
validation failed
 ↓
repair/retry/fallback
```

Никогда:

```text
LLM text
 ↓
raw Android action
```

---

# 9. V2-06 — Latency Engineering

Одна из главных задач V2.

Для каждого interaction собирать:

```text
wake
asr_start
asr_partial
asr_final
understanding
routing
context_build
llm_request
ttft
llm_complete
planner
tool
verification
tts_start
first_audio
total
```

Считать:

- p50;
- p90;
- p95;
- p99.

Разделить:

```text
time to first acknowledgement
time to first useful content
time to first audio
time to completion
```

Цель — сделать Jarvis субъективно быстрым, а не только уменьшить total latency.

---

# 10. V2-07 — Real Streaming Voice

Уже существующие streaming contracts довести до реального пользовательского streaming.

Цепочка:

```text
ASR stream
 ↓
understanding
 ↓
LLM token stream
 ↓
sentence/chunk segmentation
 ↓
TTS stream
 ↓
audio playback
```

Не ждать полного ответа LLM.

Поддержать:

- barge-in;
- cancellation;
- interruption;
- speaking state;
- audio buffering;
- backpressure;
- chunk boundaries;
- errors;
- recovery.

---

# 11. V2-08 — Cloud ASR

Заменить CloudAsrStub реальным cloud provider / provider set.

Целевая архитектура:

```text
AsrRouter
 ├── local
 ├── cloud fast
 └── fallback
```

Streaming ASR предпочтителен.

Local ASR обязателен как fallback.

---

# 12. V2-09 — Cloud TTS

Заменить CloudTtsStub реальным cloud provider / provider set.

Цель:

```text
TtsRouter
 ├── local
 ├── cloud fast
 └── fallback
```

Добавить несколько легальных voice profiles.

Не использовать точную нелицензированную копию голоса Marvel/актёра.

---

# 13. V2-10 — Android OS Presence V2

Проверить системную интеграцию и довести Jarvis до поведения системного assistant.

Изучить и использовать подходящие Android mechanisms:

- ROLE_ASSISTANT;
- VoiceInteractionService;
- VoiceInteractionSessionService;
- ACTION_ASSIST;
- notifications;
- widgets;
- supported background mechanisms;
- Accessibility;
- AppFunctions;
- intents;
- app-to-app APIs.

Accessibility не должна быть единственным фундаментом при наличии более подходящих системных API.

---

# 14. V2-11 — Global Voice Experience

Voice UX должен работать согласованно:

```text
wake
 → speak
 → understand
 → answer/action
 → immediate response
```

Проверить:

- foreground;
- background;
- lock screen;
- screen off;
- headphones;
- Bluetooth;
- media;
- notifications;
- calls;
- reboot;
- low power mode;
- permission revocation;
- OEM/MIUI restrictions.

Wake pipeline не переписывать автоматически.

Сначала измерить качество и latency.

---

# 15. V2-12 — Capability Layer V2

Цель — максимально широкий набор реальных возможностей.

Основные категории:

```text
communication
media
system
apps
files
browser
camera
vision
location
calendar
clock
reminders
notifications
contacts
maps
navigation
sharing
clipboard
downloads
documents
settings
device state
productivity
information
automation
remote
```

Каждая capability по возможности должна быть:

```text
discoverable
typed
permission-aware
risk-aware
executable
verifiable
cancelable
observable
```

---

# 16. V2-13 — Native Android Tools V2

Расширить реальный native tool catalog.

Приоритет:

- launcher;
- flashlight;
- volume;
- brightness;
- media;
- notifications;
- alarms;
- timers;
- calendar;
- contacts;
- calls;
- messaging;
- settings;
- clipboard;
- screen capture;
- camera;
- share;
- files;
- location;
- navigation.

Каждый tool проходит:

```text
Definition
→ Validation
→ Permissions
→ Risk
→ Policy
→ Safety
→ Execution
→ Verification
```

---

# 17. V2-14 — App Integration

Jarvis должен работать с приложениями, а не только с Android framework.

Приоритет интеграции:

1. официальные intents/API;
2. AppFunctions;
3. app-provided integration;
4. deep links;
5. supported APIs;
6. Accessibility fallback.

Version-aware integration обязательна.

---

# 18. V2-15 — Browser Agent V2

Перевести Browser Agent от преимущественно planning-oriented поведения к реальной, проверяемой работе.

Целевой набор:

```text
search
open
read
extract
compare
navigate
fill
submit
verify
```

Destructive/sensitive operations проходят существующую autonomy/safety chain и при необходимости требуют confirmation.

---

# 19. V2-16 — Files Agent V2

Поддержать:

```text
find
read
summarize
organize
rename
copy
move
compress
delete
restore where possible
```

Для mutating operations:

```text
preview
→ explain
→ confirmation
→ execution
→ verification
```

---

# 20. V2-17 — Vision V2

Vision должна стать реальным input channel.

Источники:

```text
camera
screenshot
screen content
shared image
document/photo
```

Модальности:

```text
voice
text
screen
image
camera
device state
```

Brain должен уметь объединять их.

---

# 21. V2-18 — Memory V3

Сначала провести audit Memory 2.0.

Не переписывать автоматически.

Целевая логическая модель:

```text
Facts
Preferences
Relationships
Habits
Routines
Tasks
Projects
Conversation memories
Temporary state
Sensitive data
```

Для памяти учитывать:

```text
source
confidence
timestamp
last_used
importance
privacy
expiry
user_editability
```

Jarvis должен уметь:

- исправлять memory;
- забывать;
- объединять записи;
- объяснять происхождение;
- контролировать privacy;
- не хранить всё подряд.

---

# 22. V2-19 — Personal Context Model

Создать/довести единый context model:

```text
Who
Where
When
What doing
What was doing
What wants
What usually does
What is waiting for
What is important now
```

Это основной источник персонализации.

---

# 23. V2-20 — Routines V2

AI-generated routines превратить в полноценный automation layer.

Примеры:

```text
Morning
Study
Work
Travel
Night
Headphones
Charging
Location
```

Поддержать:

- trigger;
- conditions;
- actions;
- loops;
- delays;
- branching;
- retries;
- verification;
- cancellation.

---

# 24. V2-21 — Proactive Companion V2

Jarvis должен быть проактивным, но контролируемым.

Цепочка:

```text
Observe
→ Understand
→ Relevance
→ Suggest
→ Ask
→ Confirm
→ Act
```

Не:

```text
Observe
→ Act without control
```

Учитывать:

- frequency;
- confidence;
- context;
- user preference;
- previous feedback;
- current activity;
- interruption cost;
- quiet hours.

---

# 25. V2-22 — Long-running Agent

Цель — полноценное выполнение пользовательской цели:

```text
Goal
 ↓
Plan
 ↓
Action
 ↓
Observe
 ↓
Verify
 ↓
Replan
 ↓
Action
 ↓
...
 ↓
Goal reached
```

Поддержать:

- timeout;
- cancellation;
- retries;
- rollback where possible;
- partial completion;
- clarification;
- recovery;
- progress.

Каждый новый action/replan снова проходит security chain.

---

# 26. V2-23 — Self-correction

При сбое:

```text
execute
 ↓
verification failed
 ↓
diagnose
 ↓
alternative plan
 ↓
retry
```

Например:

```text
Tool A unavailable
→ discover alternative
→ use Tool B
```

Нельзя обходить Policy/SafetyGate через retry.

---

# 27. V2-24 — MCP

Перевести MCP с архитектурной готовности к реальной эксплуатации.

Целевые компоненты:

```text
MCP connection manager
MCP authentication
MCP lifecycle
MCP discovery
MCP capability normalization
MCP permission mapping
MCP risk mapping
MCP execution adapter
MCP health
```

MCP tools должны выглядеть для Jarvis как обычные capabilities.

---

# 28. V2-25 — Plugins

Создать реальную plugin ecosystem.

Каждый plugin:

```text
manifest
identity
version
capabilities
permissions
tools
lifecycle
health
configuration
```

Plugin не получает автоматически полный доступ к устройству.

---

# 29. V2-26 — Remote Companion

Remote devices:

```text
Android
PC
Browser
Server
Other trusted devices
```

Remote является capability provider, а не отдельным Brain.

Jarvis остаётся центральной orchestration layer.

---

# 30. V2-27 — Companion UI V2

UI должен стать продуктовым интерфейсом, а не developer dashboard.

Основной UX:

```text
Jarvis

[Listening / Thinking / Acting / Speaking]

Conversation

Suggested action
Current task

Quick actions
```

Рекомендуемая структура:

```text
Home
Conversation
Companion
Memory
Routines
Tools
Models
Automation
Privacy
Diagnostics
Settings
```

---

# 31. V2-28 — Live Interaction UI

Во время voice interaction отображать:

```text
LISTENING
UNDERSTANDING
THINKING
ACTING
VERIFYING
SPEAKING
```

Примеры user-facing feedback:

```text
Opening Telegram…
Searching…
Changing volume…
Done.
```

Не показывать пользователю внутренние stack traces.

---

# 32. V2-29 — Diagnostics V2

Для каждого interaction иметь:

```text
requestId
route
provider
model
tokens
latency
tool
risk
verdict
gate
execution
verification
error
fallback
```

Timeline:

```text
00 ms wake
140 ms ASR
310 ms route
420 ms LLM
810 ms TTFT
1430 ms TTS first audio
```

Diagnostics должны помогать находить реальные bottlenecks.

---

# 33. V2-30 — Privacy & Security Center

Пользователь должен понимать:

```text
Что Jarvis знает
Что Jarvis может сделать
Какие permissions выданы
Что отправляется в cloud
Что хранится локально
Какие memories существуют
Какие tools активны
```

Нужны:

- memory management;
- cloud policy;
- permission audit;
- tool audit;
- execution history;
- revocation.

---

# 34. V2-31 — Reliability Engineering

Нужны end-to-end сценарии, а не только unit tests.

Проверять:

```text
voice
→ understanding
→ plan
→ action
→ verify
→ response
```

Отдельно:

- simple command;
- ambiguous request;
- multi-step task;
- cloud failure;
- network loss;
- local fallback;
- permission missing;
- app unavailable;
- stale context;
- tool failure;
- cancellation;
- user interruption;
- timeout;
- concurrency;
- reboot;
- screen off;
- headphones;
- low battery.

---

# 35. V2-32 — Golden Interaction Suite

Создать реальный набор эталонных пользовательских фраз.

Например сотни/тысячи вариантов одной semantic intent:

```text
«сделай громче»
«прибавь»
«не слышно»
«давай погромче»
«сделай чуть тише»
```

Проверять:

```text
understanding
tool selection
arguments
risk
execution
verification
spoken response
latency
```

Golden suite становится регрессионным benchmark.

---

# 36. V2-33 — Battery / Thermal / Memory

Ассистент не должен заметно разрушать автономность устройства.

Проверять:

- CPU;
- RAM;
- wakelocks;
- audio processing;
- model residency;
- hardware acceleration;
- thermal throttling;
- battery usage;
- background lifecycle.

Разделить:

```text
idle path
cold path
hot path
voice interaction
agent execution
```

---

# 37. V2-34 — Model Intelligence Layer

Добавить профили:

```text
FAST
BALANCED
QUALITY
REASONING
LOCAL
PRIVATE
```

В идеале пользователь не должен вручную выбирать модель для каждого запроса.

Router должен решать сам.

---

# 38. V2-35 — Personalization

Jarvis должен постепенно адаптироваться к пользователю.

Изучаемые параметры:

- стиль общения;
- предпочитаемые приложения;
- routines;
- preferred tools;
- response length;
- voice;
- automation preferences;
- habitual periods;
- repetitive tasks.

Learning должен оставаться контролируемым и объяснимым.

---

# 39. V2-36 — Natural Conversation

Поддерживать multi-turn context:

```text
User:
«Открой браузер»

Jarvis:
«Открыл.»

User:
«Найди там дешёвые билеты в Берлин.»

Jarvis:
…

User:
«А самый ранний?»

Jarvis:
…
```

Без повторного задания предыдущего контекста.

---

# 40. V2-37 — Autonomous Task Completion

Jarvis получает цель, а не список команд.

Пример:

> «Подготовь мне всё необходимое для поездки в Берлин завтра.»

Jarvis должен:

```text
understand goal
→ decompose
→ gather information
→ plan
→ execute safe actions
→ ask for decisions
→ verify
→ summarize
```

Это ключевой переход от assistant к companion.

---

# 41. V2-38 — Trust Model

Технический enforcement остаётся детерминированным, но пользовательская модель доверия должна быть понятной.

Возможные режимы:

```text
Never
Ask every time
Ask when risky
Allow safe actions
Allow trusted tool
Allow routine
```

Trust не должен обходить SafetyGate.

---

# 42. V2-39 — Self-maintenance

Jarvis должен обнаруживать проблемы сам:

```text
provider unavailable
model missing
permission revoked
tool broken
service stopped
battery restriction
remote offline
```

И объяснять:

```text
что произошло
почему
что можно сделать
что уже сделано
```

---

# 43. V2-40 — Final Acceptance

Jarvis считается product-ready только после прохождения набора критериев.

## Understanding

Natural language without memorized commands.

## Intelligence

Cloud AI используется эффективно; local fallback сохраняет работоспособность.

## Speed

First response и first audio имеют измеримые и приемлемые latency.

## Voice

Wake → ASR → AI → TTS ощущается как единый поток.

## Actions

Ключевые Android capabilities реально работают на устройстве.

## Agent

Многошаговые задачи выполняются и проверяются.

## Memory

Персонализация полезна и контролируема.

## Proactivity

Suggestions релевантны и не превращаются в spam.

## Verification

Ошибки действий обнаруживаются.

## Security

Нет execution bypass относительно Risk → Policy → SafetyGate → Executor.

## UI

Ключевые возможности понятны и доступны.

## Reliability

Сбои cloud/network/provider/permission не уничтожают core functionality.

## Independence

Без cloud:

```text
Jarvis remains useful
```

При наличии cloud:

```text
Jarvis becomes smarter/faster
```

---

# 44. Что сохранить

Не переписывать автоматически:

- Wake/KWS;
- 8A Autonomy Contracts;
- 8B RiskClassifier;
- 8C AutonomyPolicy;
- 8D SafetyGate;
- 8E Tool Execution Contracts;
- 8F Registry;
- базовые Memory/Routine contracts.

Сначала аудит.

---

# 45. Главные кандидаты на rewrite

Наиболее вероятные зоны глубокой переработки:

## AI Router

Причины для возможного rewrite:

- недостаточно умный route selection;
- отсутствие latency-aware routing;
- слабый fallback;
- hardcoded provider assumptions.

## BrainGraph

Проверить, является ли это реальным orchestration layer или только feature wiring.

## AI Planner

Развить deterministic planner в hybrid planner:

```text
LLM reasoning
+
deterministic validation
+
typed ActionPlan
```

## Voice Pipeline

Особенно внимательно проверить:

- startup;
- blocking;
- serialization;
- streaming;
- cloud/local switching;
- TTS first audio;
- interruption.

## Context Assembly

Проверить, получает ли LLM именно полезный контекст, а не просто большой context dump.

## Browser/Files

Проверить переход от planning к реальному execution и verification.

## ProactiveAgent

Проверить реальную пользу, частоту и качество suggestions.

## UI

Вероятна существенная UX переработка после полного аудита.

---

# 46. Приоритеты

```text
P0 — broken / unsafe / architectural blocker
P1 — сильно ломает core UX
P2 — важная quality problem
P3 — polish
```

Исправлять P0/P1 прежде P2/P3.

---

# 47. Приоритетный порядок V2

```text
V2-00 Audit
      ↓
V2-01 Architecture Hardening
      ↓
V2-02 Brain / Understanding
      ↓
V2-03 AI Router
      ↓
V2-04 LLM Quality
      ↓
V2-05 Structured Output
      ↓
V2-06 Latency
      ↓
V2-07 Streaming Voice
      ↓
V2-08 Cloud ASR
V2-09 Cloud TTS
      ↓
V2-10 Android Presence
V2-11 Global Voice
      ↓
V2-12 Capability Layer
V2-13 Native Tools
V2-14 App Integration
      ↓
V2-15 Browser
V2-16 Files
V2-17 Vision
      ↓
V2-18 Memory
V2-19 Context
V2-20 Routines
V2-21 Proactivity
      ↓
V2-22 Agent
V2-23 Self-correction
      ↓
V2-24 MCP
V2-25 Plugins
V2-26 Remote
      ↓
V2-27 UI
V2-28 Live UI
V2-29 Diagnostics
V2-30 Privacy
      ↓
V2-31 Reliability
V2-32 Golden Suite
V2-33 Performance
V2-34 Model Intelligence
V2-35 Personalization
V2-36 Conversation
V2-37 Autonomous Tasks
V2-38 Trust
V2-39 Self-maintenance
      ↓
V2-40 Final Acceptance
```

---

# 48. Ключевая идея V2

Нельзя превращать V2 в бесконечную гонку за количеством features.

Главная последовательность:

```text
Audit
→ Fix
→ Measure
→ Improve
→ Verify
→ Expand
→ Verify again
```

Не:

```text
Add feature
→ add feature
→ add feature
→ hope it works
```

Цель — максимальная полезность существующей системы, а затем расширение её реальных возможностей.

---

# 49. Stage V2-00 — Prompt для агента

Ниже находится готовый prompt для запуска первой стадии V2.

---

## Prompt

Начинай **Jarvis Roadmap V2, Stage V2-00 — Full System Audit**.

Это НЕ задача на добавление новой крупной функции.

Главная задача сейчас — провести полный технический и продуктовый аудит уже существующей реализации и определить, что действительно готово, что работает частично, где есть скрытые дефекты, где архитектура неоптимальна и что необходимо переписать для достижения следующей цели:

> Android AI companion / assistant, который максимально естественно понимает пользователя, умеет использовать устройство и внешние инструменты, выполняет многошаговые задачи, запоминает полезный контекст, действует проактивно, работает быстро, использует cloud AI при наличии сети и local fallback без сети и при этом остаётся безопасным и контролируемым.

### Текущий baseline

Предыдущий roadmap Stages 8C–27 уже закрыт.

Подтверждены:

- 415/415 unit tests;
- device smoke;
- AI Provider Registry;
- AI Router;
- AI Planner;
- Agent Loop;
- RiskClassifier;
- AutonomyPolicy;
- ActionSafetyGate;
- Tool Execution Contracts;
- Tool Registry;
- Capability Discovery;
- Native Android Tools;
- Plugins architecture;
- MCP architecture;
- Files Agent;
- Browser Agent;
- routines;
- Memory 2.0;
- hybrid voice;
- streaming;
- Vision;
- ProactiveAgent;
- Remote layer;
- Diagnostics UI.

Текущая логическая pipeline:

`Voice → Understanding → Context/Memory → AI Router → Planner → Tool Discovery → Agent Loop → Risk → Policy → SafetyGate → Executor → Verifier → Memory/Proactivity → TTS/UI`

Не считай наличие класса, feature flag, stub или unit test доказательством того, что пользовательская функция реально завершена.

### Главный принцип

Аудируй не по принципу:

`"код существует → функция готова"`

а по принципу:

`"пользователь сказал X → Jarvis должен сделать Y → что реально происходит?"`

Проверяй полный end-to-end flow.

---

## 1. Изучи проект целиком

Перед изменениями изучи:

- структуру модулей;
- Gradle;
- Manifest;
- permissions;
- services;
- receivers;
- VoiceService;
- Wake/KWS ownership;
- ASR;
- TTS;
- AI providers;
- AIRouter;
- BrainGraph;
- Planner;
- Agent loop;
- Registry;
- Discovery;
- native tools;
- Browser Agent;
- Files Agent;
- Vision;
- Memory;
- routines;
- proactive system;
- remote;
- plugins;
- MCP;
- diagnostics;
- UI;
- persistence;
- settings;
- tests.

Отдельно изучи:

- `Jarvis_New_Roadmap.md`
- `COMPANION_STATUS.md`

---

## 2. Architecture Audit

Проверить:

### Architecture

- duplicated responsibilities;
- circular dependencies;
- god classes;
- duplicated models;
- duplicated validators;
- hidden side effects;
- state synchronization;
- lifecycle leaks;
- race conditions;
- thread safety;
- cancellation;
- timeout propagation;
- error propagation;
- persistence.

### Contracts

Убедиться, что:

`RiskClassifier → AutonomyPolicy → ActionSafetyGate → Executor → Verifier`

остаются отдельными.

### Execution

Найти любой путь, через который:

`LLM → Android side effect`

может обойти:

`Risk → Policy → SafetyGate → Executor`.

---

## 3. Runtime AI Routing Audit

Это один из главных пунктов.

Для каждого типа запроса определить:

- deterministic path;
- local NLP;
- local LLM;
- cloud LLM;
- fallback;
- tool path;
- planner path;
- clarification path.

Не делай вывод по архитектуре — измерь runtime.

Если diagnostics недостаточно, добавь минимальную безопасную instrumentation.

Логировать:

```text
requestId
route
provider
model
reasonForRoute
contextSize
inputTokens
outputTokens
streaming
fallback
```

Главный вопрос:

> Какой процент обычных conversational запросов реально доходит до Cloud LLM, какой попадает в local path и какой перехватывается deterministic/skill logic?

---

## 4. LLM Quality Audit

Проверить:

- system prompt;
- persona;
- conversation history;
- memory;
- device context;
- tool descriptions;
- planner prompt;
- model selection;
- token budget;
- context size;
- output format;
- unnecessary context;
- duplicated context;
- accidental local fallback;
- accidental deterministic interception.

Сделать controlled comparison:

```text
1. direct cloud LLM
2. current Jarvis route
3. local LLM
```

Сравнить:

- response quality;
- latency;
- tokens;
- context;
- route;
- model.

Определить, в чём реальная причина неудовлетворительного ответа:

- модель;
- prompt;
- context;
- routing;
- planner;
- parsing;
- post-processing;
- fallback.

Не менять provider/model вслепую.

---

## 5. Latency Audit

Для interaction измерять:

```text
wake
asr_start
asr_partial
asr_final
understanding
routing
context_build
llm_request
ttft
llm_complete
planner
tool
verification
tts_start
first_audio
total
```

Разделить:

```text
time to first acknowledgement
time to first useful content
time to first audio
time to completion
```

Найти bottleneck.

Особенно важно выяснить, почему cloud LLM уже работает, но e2e voice response всё равно может занимать несколько секунд.

Не считать LLM единственной причиной.

---

## 6. Voice Pipeline Audit

Проверить:

`Wake → VAD → ASR → Understanding → Router → LLM → TTS → Audio`

Особенно:

- buffering;
- warm/cold state;
- model loading;
- blocking calls;
- thread handoff;
- duplicate audio conversion;
- serialization;
- waiting for full LLM response;
- TTS startup;
- audio queue;
- cancellation;
- barge-in;
- interruption.

Отдельно определить состояние Cloud ASR/TTS stubs.

---

## 7. BrainGraph Audit

Проверить:

- кто владеет state;
- где создаются providers;
- где создаются routers;
- где собирается context;
- где выбирается provider;
- где вызывается LLM;
- где запускается Planner;
- где запускается Agent;
- где запускается TTS;
- где происходит fallback.

Определить, является ли BrainGraph полноценным orchestration layer или только wiring/feature registry.

---

## 8. Planner / Agent Audit

Проверить:

- simple tasks;
- multi-step tasks;
- clarification;
- discovery;
- plan validation;
- execution;
- verification;
- retry;
- replanning;
- cancellation;
- timeout;
- partial completion;
- failure recovery.

Главный вопрос:

> Может ли Jarvis реально завершить пользовательскую цель, а не только построить ActionPlan?

---

## 9. Tool Audit

Для каждого important tool проверить полный путь:

`discover → validate → permissions → risk → policy → safety → execute → verify → response`

Найти tools, которые:

- существуют формально;
- проходят unit tests;
- но плохо работают на реальном устройстве.

Особенно проверить:

- media;
- settings;
- launcher;
- flashlight;
- volume;
- weather;
- files;
- browser;
- notifications.

---

## 10. Memory / Context Audit

Проверить:

- Companion memory;
- Memory 2.0;
- Behavior Learning;
- Routines;
- current context.

Найти duplicate responsibilities.

Ответить:

> Где сейчас находится source of truth о пользователе?

Решить, нужен ли Memory V3 или достаточно расширить текущую систему.

Не переписывать memory автоматически.

---

## 11. Proactive Audit

Проверить:

- relevance;
- context;
- confidence;
- cooldown;
- feedback;
- quiet periods;
- duplicates;
- noise.

Главная цель:

`helpful companion`

а не:

`notification spam`.

---

## 12. UI Audit

Проверить абсолютно весь UI:

- Dashboard;
- Companion;
- settings;
- model settings;
- cloud settings;
- diagnostics;
- memory;
- routines;
- workflows;
- tools;
- permissions;
- errors;
- loading;
- empty states.

Для каждого UI path проверить:

`виден → понятен → интерактивен → состояние корректно → действие реально происходит → feedback существует → ошибка отображается`

Искать:

- fake controls;
- dead buttons;
- misleading state;
- debug-only UI;
- technical wording;
- broken navigation;
- duplicated screens;
- inconsistent design;
- inaccessible settings.

Сделать отдельные рекомендации по UI V2.

---

## 13. Device Audit

Проверить реальные сценарии:

```text
screen on
screen off
lock screen
background
foreground
headphones
Bluetooth
media
notifications
low battery
network available
network unavailable
cloud failure
local fallback
permission denied
app unavailable
```

Проверить:

- restart;
- service restart;
- reboot;
- permission changes;
- network changes.

---

## 14. Security Audit

Проверить:

- Tool boundaries;
- permissions;
- Policy;
- SafetyGate;
- bindings;
- confirmation;
- stale context;
- TTL;
- argument validation;
- forbidden parameters;
- plugin boundaries;
- MCP boundaries;
- remote boundaries.

Найти любой обход:

`LLM → Tool`

минуя safety stack.

---

## 15. Product Quality Audit

Пройти реальные пользовательские сценарии.

Пример:

```text
проснулся
→ сказал команду
→ Jarvis понял
→ ответил
→ открыл приложение
→ выполнил задачу
→ проверил
→ запомнил контекст
→ продолжил разговор
```

Найти места, где ломается ощущение цельного продукта:

- slow;
- misunderstanding;
- lost context;
- unnecessary confirmation;
- incomplete action;
- weak spoken response;
- poor UI;
- no recovery;
- missing capability.

---

## 16. Rewrite Candidates

Создать таблицу:

| Component | Current state | Problem | Severity | Keep / Refactor / Rewrite | Why |
|---|---|---|---|---|---|

Особенно изучить:

- AI Router;
- BrainGraph;
- Planner;
- VoicePipeline;
- ASR routing;
- TTS routing;
- Context assembly;
- ProactiveAgent;
- Browser Agent;
- Files Agent;
- UI.

Не объявлять rewrite заранее.

---

## 17. Priority Matrix

Классифицировать найденные проблемы:

```text
P0 — broken / unsafe / architectural blocker
P1 — серьёзно ломает core UX
P2 — важная quality problem
P3 — polish
```

Исправлять P0/P1 прежде P2/P3.

---

## 18. Не делать во время V2-00

Не:

- добавлять десятки новых tools;
- строить новую MCP ecosystem;
- переписывать Wake без доказанной причины;
- переписывать 8A–8F без доказанной причины;
- менять LLM provider вслепую;
- удалять модули без анализа;
- делать giant refactor ради стиля;
- превращать audit в feature implementation.

Очевидные P0 можно исправить сразу, если проблема доказана и легко локализуется. Остальные изменения сначала зафиксировать в отчёте.

---

## 19. Deliverable

Создать:

`md_files/JARVIS_V2_FULL_AUDIT.md`

Отчёт должен содержать:

1. Executive summary.
2. Current architecture.
3. Runtime pipeline.
4. Routing matrix.
5. LLM quality findings.
6. Latency breakdown.
7. Voice findings.
8. Planner/Agent findings.
9. Tool findings.
10. Memory findings.
11. Proactivity findings.
12. UI audit.
13. Device/lifecycle audit.
14. Security audit.
15. Battery/performance findings.
16. Stub/partial inventory.
17. Rewrite candidates.
18. Refactor candidates.
19. Keep-as-is components.
20. P0/P1/P2/P3 matrix.
21. Dependency graph.
22. Recommended implementation order.
23. Proposed V2 architecture.
24. Exact next stage.

Каждая важная проблема должна быть привязана к конкретному существующему классу, модулю, pipeline или runtime behavior.

---

## 20. Baseline regression

Запустить:

```bash
./gradlew :core:testDebugUnitTest
./gradlew :app:assembleDebug
```

При наличии отдельного brain/aiprovider suite — запустить его тоже.

При возможности выполнить device smoke:

- wake;
- simple command;
- cloud dialogue;
- local fallback;
- tool execution;
- clarification;
- autonomy dry-run.

---

## 21. Правило завершения

После завершения Stage V2-00:

1. не переходи автоматически к V2-01;
2. сформируй audit report;
3. сформируй priority matrix;
4. перечисли keep/refactor/rewrite;
5. предложи точный порядок исправлений;
6. укажи главный следующий stage.

После этого остановись.

Главная цель V2-00:

> Мы больше не строим Jarvis по принципу «ещё одна функция». Мы сначала убеждаемся, что существующая функция реально работает end-to-end, быстро, предсказуемо, удобно и безопасно.
