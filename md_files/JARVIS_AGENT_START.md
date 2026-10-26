# JARVIS AGENT START — PRODUCTION BOOTSTRAP

> **Назначение:** единая стартовая инструкция для coding-agent, который продолжает разработку JARVIS в существующем Android-репозитории.
>
> **Главный принцип:** агент не начинает проект заново и не реализует весь roadmap сразу. Он сначала определяет фактическое состояние репозитория, затем выбирает ближайший незавершённый этап и доводит только его до проверяемого результата.

---

## 0. КОНТЕКСТ ПРОЕКТА

JARVIS — Android-приложение в формате APK, ориентированное на локальный/offline-first персональный интеллект.

Цель продукта:

- максимально полезный локальный ассистент;
- голосовое взаимодействие;
- русский язык как один из приоритетных;
- wake word;
- локальные ASR/VAD/TTS;
- локальный Brain/NLP/LLM;
- память и контекст;
- инструменты и навыки;
- автоматизации;
- vision в следующих этапах;
- интернет только там, где он действительно необходим;
- единая Android-архитектура вместо набора разрозненных команд;
- понятный, быстрый и премиальный UI.

JARVIS должен восприниматься как **единый интеллект**, а не как список voice commands.

---

# 1. ИСТОЧНИКИ ИСТИНЫ

Перед любой реализацией агент обязан прочитать доступные проектные документы.

Основные документы:

1. `JARVIS_MASTER_ROADMAP_v2.md`
2. `JARVIS_CODING_AGENT_MASTER_PROMPT.md`
3. `JARVIS_UI_UX_DESIGN_SYSTEM.md`
4. `JARVIS_UI_REFERENCE_v2.html`
5. `docs/FOUNDATION.md`
6. существующий исходный код репозитория.

Если какого-либо документа нет — не придумывать его содержимое. Использовать реально доступные документы и код.

### Приоритет

Если документы противоречат друг другу:

1. фактический рабочий код и текущая конфигурация проекта;
2. roadmap;
3. coding-agent contract;
4. UI/UX design system;
5. HTML visual reference.

Не переписывай рабочую архитектуру только ради соответствия старому документу.

---

# 2. АБСОЛЮТНЫЕ ПРАВИЛА

## 2.1 Не делать всё сразу

Запрещено:

- реализовывать весь roadmap одним проходом;
- создавать десятки пустых модулей заранее;
- добавлять fake implementations;
- оставлять production TODO вместо реализации;
- создавать параллельные системы с одинаковой ответственностью;
- переписывать рабочие классы без необходимости;
- добавлять библиотеку только ради нескольких строк кода.

Правильный цикл:

`READ → AUDIT → PLAN → IMPLEMENT → BUILD → TEST → MEASURE → DOCUMENT → NEXT`

---

## 2.2 Сначала аудит

Перед изменением файлов:

```bash
pwd
find . -maxdepth 3 -type f | sort
cat settings.gradle.kts
find . -name "build.gradle.kts" -o -name "build.gradle" | sort
```

Затем изучить ключевые исходники:

```bash
find core app domain data feature-skills -type f 2>/dev/null | sort
```

Проверить Git:

```bash
git status --short
git log -5 --oneline
```

Если Git отсутствует — продолжить без него.

---

# 3. ФАКТИЧЕСКАЯ ТОЧКА ПРОДОЛЖЕНИЯ

После аудита агент обязан составить внутреннюю таблицу:

| Область | Есть | Работает | Частично | Нет |
|---|---:|---:|---:|---:|
| Gradle/build | | | | |
| common models | | | | |
| ToolRegistry | | | | |
| ToolExecutor | | | | |
| Context | | | | |
| Brain | | | | |
| NLP | | | | |
| Wake Word | | | | |
| VAD | | | | |
| ASR | | | | |
| TTS | | | | |
| LLM | | | | |
| Memory | | | | |
| Skills | | | | |
| UI | | | | |
| Permissions | | | | |
| Diagnostics | | | | |

Нельзя считать файл реализованным только потому, что он существует.

---

# 4. ТЕКУЩИЙ ANDROID-КОНТУР

Ожидаемая базовая структура:

```text
app
core/
  common
  audio
  brain
  nlp
  llm
  asr
  tts
  wakeword
domain
data
feature-skills
```

В будущем архитектура может расширяться:

```text
core/memory
core/context
core/security
core/vision
core/automation
core/tools
feature-ui
feature-settings
feature-diagnostics
```

Но модуль добавляется только тогда, когда его функциональность реально нужна текущему этапу.

---

# 5. АРХИТЕКТУРНЫЙ КОНТРАК

Основной поток:

```text
Microphone
    ↓
Wake Word
    ↓
VAD
    ↓
Streaming / Local ASR
    ↓
UserRequest
    ↓
Assistant Orchestrator
    ├── Fast Intent Engine
    ├── Context Engine
    ├── Memory
    └── Local LLM
    ↓
ToolCall
    ↓
Security / Policy
    ↓
ToolExecutor
    ↓
ToolResult
    ↓
Response Planner
    ↓
TTS
    ↓
Speaker
```

Ключевое правило:

**LLM не имеет права напрямую выполнять произвольные Android/system actions.**

LLM формирует структурированный `ToolCall`.

Policy/security слой решает, разрешено ли выполнение.

Executor выполняет разрешённый инструмент.

---

# 6. DETERMINISTIC-FIRST

Для простых команд не надо использовать LLM без причины.

Примеры:

```text
"включи фонарик"
"выключи Bluetooth"
"поставь таймер на 10 минут"
"сколько времени"
```

сначала проходят через быстрый intent/tool слой.

LLM нужен для:

- сложной формулировки;
- контекста;
- неоднозначности;
- планирования;
- многошаговых задач;
- естественного диалога.

Цель:

**минимальная задержка при максимальной надёжности.**

---

# 7. CONFIDENCE-AWARE EXECUTION

Каждый этап понимания должен иметь confidence.

Рекомендуемая логика:

```text
HIGH
→ выполнить

MEDIUM
→ уточнить или показать предполагаемое действие

LOW
→ не выполнять опасное действие
→ запросить уточнение / перейти в conversational fallback
```

Нельзя превращать низкую уверенность в уверенное действие.

---

# 8. TOOL SAFETY

Каждый инструмент должен иметь:

- id;
- description;
- parameters;
- risk level;
- required permissions;
- offline/online requirement;
- confirmation policy.

Минимальные уровни:

```text
SAFE
LOW
CONFIRM
SENSITIVE
BLOCKED
```

Примеры:

### SAFE

- время;
- дата;
- фонарик;
- локальные вычисления.

### LOW

- изменение некоторых системных настроек;
- запуск приложения.

### CONFIRM

- отправка сообщения;
- удаление данных;
- изменение важных настроек.

### SENSITIVE

- доступ к чувствительным данным;
- финансовые операции;
- массовые действия.

### BLOCKED

- действие, которое политика приложения запрещает.

---

# 9. TOOL RESULT

Результат инструмента должен быть структурирован.

Минимальные статусы:

```text
SUCCESS
FAILED
DENIED
NEEDS_CONFIRMATION
UNAVAILABLE
OFFLINE
AMBIGUOUS
```

Не использовать:

```kotlin
return true
```

как замену реальному результату.

Не выдавать пользователю "готово", если действие фактически не выполнено.

---

# 10. ПРОТОКОЛ РАЗРАБОТКИ

Для каждой задачи:

## STEP A — READ

Изучить:

- roadmap;
- соответствующий модуль;
- существующие модели;
- существующие интерфейсы;
- тесты;
- Gradle.

## STEP B — PLAN

Сформулировать:

```text
Что уже есть:
Что отсутствует:
Что именно меняем:
Какие файлы затрагиваем:
Как проверяем:
```

## STEP C — IMPLEMENT

Изменять минимальное необходимое количество файлов.

## STEP D — BUILD

Сначала узкая проверка:

```bash
./gradlew :core:common:build
```

или соответствующий модуль.

После завершения этапа:

```bash
./gradlew assembleDebug
```

## STEP E — TEST

Запускать существующие unit tests.

При необходимости добавить тесты.

## STEP F — REAL DEVICE

Если функция относится к Android runtime:

- собрать APK;
- установить;
- проверить на реальном телефоне;
- проверить permissions;
- проверить lifecycle;
- проверить background;
- проверить screen off, если это относится к функции.

## STEP G — DOCUMENT

Обновить документацию только после проверки.

---

# 11. НЕ СОЗДАВАТЬ ДУБЛИ

Перед созданием нового класса поискать существующие аналоги.

Например, если уже есть:

```text
ToolRegistry
ToolExecutor
ToolDefinition
ToolCall
```

не создавать без необходимости:

```text
SkillRegistry
CommandRegistry
ActionRegistry
CommandExecutor
```

с той же ответственностью.

Сначала расширить существующую архитектуру.

---

# 12. COMMON — ФУНДАМЕНТ

`core/common` должен содержать только действительно общие контракты и модели.

Уже ожидаемые сущности:

```text
UserRequest
AssistantResponse
Intent
IntentType
Slot
Action
ActionType
ActionStatus
ToolCall
ToolResult
ToolDefinition
ToolParameter
ToolRegistry
ToolExecutor
ConversationContext
```

Перед добавлением новой модели проверить:

- нужна ли она нескольким слоям;
- не дублирует ли существующую;
- не относится ли она на самом деле к конкретному feature/module.

---

# 13. CONVERSATION CONTEXT

Context должен постепенно поддерживать:

- текущий диалог;
- последние сообщения;
- текущую задачу;
- активный tool;
- pending confirmation;
- references;
- временные slots;
- состояние диалога.

Не превращать Context в бесконечный контейнер всего приложения.

---

# 14. MEMORY

Memory должна быть отдельным слоем.

Разделять:

```text
short-term context
long-term memory
episodic memory
semantic memory
user preferences
temporary state
```

Память должна иметь:

- источник;
- timestamp;
- confidence;
- sensitivity;
- возможность удаления;
- понятные правила retrieval.

Чувствительные данные не должны логироваться без необходимости.

---

# 15. LOCAL-FIRST

По умолчанию:

```text
Voice → local
ASR → local
NLP → local
Intent → local
Memory → local
LLM → local
TTS → local
Tools → local where possible
```

Интернет используется только если:

- функция действительно требует сети;
- пользователь разрешил;
- online route выбран явно архитектурой.

Offline не должен считаться ошибкой.

---

# 16. NETWORK ROUTER

В будущем:

```text
ConnectivityRouter
```

должен решать:

```text
LOCAL_ONLY
LOCAL_PREFERRED
ONLINE_ALLOWED
ONLINE_REQUIRED
```

Пример:

```text
"сколько времени" → LOCAL_ONLY
"открой настройки" → LOCAL_ONLY
"какая погода сегодня" → ONLINE_REQUIRED
```

Если online unavailable:

- не ломать весь assistant;
- сообщить причину;
- предложить локальный fallback, если возможен.

---

# 17. WAKE WORD

Wake word должен быть:

- локальным;
- экономным по батарее;
- работающим в фоне настолько, насколько позволяют Android restrictions;
- устойчивым к шуму;
- с минимальными false positives.

Обязательно тестировать:

```text
screen on
screen off
app foreground
app background
device idle
headphones
speaker playback
ambient noise
reboot
```

Не обещать lock-screen/background возможности, которые Android фактически не разрешает без соответствующих mechanisms/permissions.

---

# 18. VAD

VAD нужен для определения:

```text
speech started
speech continued
speech ended
```

Проверять:

- шум;
- короткие фразы;
- длинные фразы;
- паузы;
- speaker playback;
- microphone route.

---

# 19. ASR

ASR должен стремиться к:

- локальности;
- низкой задержке;
- русскому языку;
- streaming/partial recognition;
- корректному endpointing;
- обработке чисел и команд.

Метрики:

```text
latency
RTF
WER/CER
battery
memory
```

---

# 20. TTS

TTS должен:

- работать offline;
- поддерживать русский;
- запускать ответ быстро;
- поддерживать interruption/barge-in;
- корректно освобождать audio resources.

Во время TTS пользователь должен иметь возможность перебить ассистента голосом.

---

# 21. BRAIN / ORCHESTRATOR

Brain — не giant switch на тысячи строк.

Разделять:

```text
Input normalization
Intent recognition
Context resolution
Memory retrieval
Planning
Tool selection
Policy check
Execution
Response generation
```

Оркестратор координирует эти слои.

---

# 22. SKILLS

Skill/tool должен быть атомарным и тестируемым.

Хороший skill:

```text
input
→ validate
→ execute
→ ToolResult
```

Не смешивать UI, network, LLM и системные действия в одном огромном классе.

---

# 23. AUTOMATION

Автоматизации представлять:

```text
WHEN
IF
THEN
```

Например:

```text
WHEN 07:30
IF weekday
THEN announce weather
```

Automation Engine должен быть:

- локальным;
- ограниченным permissions;
- устойчивым к перезапуску;
- observable;
- безопасным.

---

# 24. PROACTIVE AI

JARVIS может предлагать действия, но:

**suggestion ≠ execution**

Автоматическое выполнение только если пользователь заранее создал и разрешил automation.

Не делать скрытую инициативность.

---

# 25. VISION

Vision — поздний этап.

Архитектура должна позволять:

```text
Camera
→ frame preprocessing
→ local vision model
→ structured observation
→ Brain
```

Не включать камеру постоянно без необходимости.

Индикатор камеры должен быть очевиден.

---

# 26. UI — НЕ ОТДЕЛЬНАЯ ИГРУШКА

UI отражает состояние реального backend.

Нельзя показывать:

```text
ONLINE
```

если backend offline.

Нельзя показывать:

```text
EXECUTING
```

если tool не запущен.

Нельзя рисовать fake metrics.

UI должен получать состояние из observable state.

---

# 27. UI FOUNDATION

Основные экраны:

```text
HOME
LIVE
MEMORY
SKILLS
MORE
```

В More:

```text
Automations
Vision
Activity
Settings
Diagnostics
Privacy
Developer Mode
Model Manager
```

---

# 28. JARVIS UI DESIGN SYSTEM

Использовать:

`JARVIS_UI_UX_DESIGN_SYSTEM.md`

Основная визуальная идея:

```text
graphite
+
brass
+
arc cyan
+
restrained danger red
```

Не превращать приложение в RGB gaming HUD.

Не копировать HTML буквально.

HTML — визуальный reference.

Android UI должен быть native и production-ready.

---

# 29. THEMES

Поддерживать концепцию:

```text
JARVIS CLASSIC
ARC REACTOR
STEALTH
LIGHT
CUSTOM
```

Семантика цветов должна сохраняться между темами:

```text
danger = danger
success = success
warning = warning
offline = offline
```

Accent color не должен ломать accessibility.

---

# 30. HOME

Рекомендуемый порядок:

```text
Greeting
↓
JARVIS Status
↓
System Snapshot
↓
Quick Actions
↓
Active Automation
↓
Last Activity
```

Не перегружать dashboard.

---

# 31. LIVE

Live должен визуально показывать реальные состояния:

```text
IDLE
LISTENING
THINKING
EXECUTING
SPEAKING
ERROR
OFFLINE
```

Центральный orb должен быть state-driven.

---

# 32. ACTIVITY

Показывать:

```text
request
→ action
→ result
→ duration
```

Для developer mode дополнительно:

```text
intent
confidence
memory retrieval
model
tokens/time
tool call
permission
network
errors
```

---

# 33. DIAGNOSTICS

Разделять:

### User mode

```text
"Не удалось выполнить действие"
"Нет доступа к микрофону"
"Функция требует интернет"
```

### Developer mode

Полный trace.

Не показывать stack trace обычному пользователю.

---

# 34. MODEL MANAGER

В будущем Model Manager должен контролировать:

- доступные модели;
- размер;
- RAM;
- download;
- checksum/integrity;
- активную модель;
- fallback;
- version;
- delete.

Не хранить огромные модели в APK без необходимости.

---

# 35. BATTERY

Voice assistant должен иметь профили:

```text
BALANCED
LOW_POWER
PERFORMANCE
CHARGING
```

Непрерывное wake-word listening должно быть измерено.

Не оптимизировать "на глаз".

---

# 36. PERFORMANCE

Измерять:

```text
wake latency
ASR latency
intent latency
LLM latency
tool latency
TTS latency
end-to-end latency
RAM
CPU
battery
```

Для каждого крупного этапа иметь baseline.

---

# 37. LOGGING

Логи должны быть структурированными.

Минимальные поля:

```text
timestamp
component
event
duration
status
request_id
```

Не писать в лог:

- приватный текст без необходимости;
- полные чувствительные данные;
- credentials;
- tokens;
- passwords.

---

# 38. ERROR HANDLING

Ошибка должна иметь путь:

```text
detect
→ classify
→ recover
→ explain
→ log
```

Не скрывать исключения.

Не использовать пустые catch blocks.

---

# 39. TEST PYRAMID

### Unit

Для:

- Intent;
- Context;
- tools;
- policy;
- parsers;
- state machines.

### Integration

Для:

```text
request
→ brain
→ tool
→ result
```

### Device

Для:

- microphone;
- permissions;
- background;
- audio;
- TTS;
- wake word;
- system tools.

### Soak

Для длительной работы:

```text
1h
4h
overnight
```

если это возможно.

---

# 40. REAL DEVICE CHECKLIST

После Android runtime изменений:

```text
[ ] install
[ ] launch
[ ] permission flow
[ ] screen on
[ ] screen off
[ ] app background
[ ] app reopen
[ ] reboot
[ ] offline
[ ] online
[ ] low battery
[ ] headphones
[ ] Bluetooth
[ ] noisy environment
```

---

# 41. ANDROID-SPECIFIC SAFETY

Учитывать:

- foreground service restrictions;
- microphone permissions;
- notification permission;
- background execution;
- battery optimization;
- Android lifecycle;
- audio focus;
- Bluetooth routes;
- screen-off behavior;
- boot restrictions.

Не обходить системные ограничения сомнительными hacks.

---

# 42. DEPENDENCIES

Перед добавлением dependency:

1. можно ли решить штатным Kotlin/Android API?
2. уже есть ли зависимость в проекте?
3. действительно ли она нужна runtime?
4. какова цена по размеру APK/RAM/CPU?
5. поддерживает ли она offline?
6. лицензия подходит?

Если ответ неубедительный — dependency не добавлять.

---

# 43. НЕ ДОПУСКАТЬ FAKE IMPLEMENTATIONS

Запрещены:

```kotlin
TODO()
return true
return false
delay(1000)
println("done")
```

если они маскируют незавершённую production-функцию.

Допустимы explicit states:

```text
UNAVAILABLE
NOT_IMPLEMENTED
REQUIRES_PERMISSION
```

если они честно отражены в архитектуре и UI.

---

# 44. ИМЕНОВАНИЕ

Имена должны описывать ответственность.

Плохо:

```text
Manager
Helper
Utils
Stuff
Handler
```

если из имени непонятно назначение.

Хорошо:

```text
ToolExecutor
ConversationContext
IntentResolver
PermissionPolicy
ModelManager
ConnectivityRouter
```

---

# 45. СТИЛЬ KOTLIN

Предпочитать:

- immutable data;
- `val`;
- data classes;
- sealed types;
- interfaces;
- coroutines;
- Flow/StateFlow;
- dependency inversion;
- lifecycle-aware scopes.

Избегать:

- global mutable state;
- огромных классов;
- deep inheritance;
- ненужных reflection;
- лишних abstraction layers.

---

# 46. ПЕРЕД СОЗДАНИЕМ КЛАССА

Ответить:

```text
Почему этот класс нужен?
Кто его вызывает?
Кто владеет его lifecycle?
Какая у него единственная ответственность?
Как его протестировать?
Не существует ли уже такой ответственности?
```

Если ответа нет — класс не создавать.

---

# 47. КРИТЕРИЙ ГОТОВНОСТИ ЭТАПА

Этап завершён только когда:

```text
implementation
+
compile
+
tests
+
real-device verification where applicable
+
error handling
+
documentation
```

Если один пункт отсутствует — этап не объявлять полностью завершённым.

---

# 48. ФОРМАТ ОТЧЁТА АГЕНТА

После работы сообщать:

```text
## Что проверено

## Что найдено

## Что изменено

## Какие файлы изменены

## Почему именно так

## Build

## Tests

## Device test

## Known limitations

## Следующий этап
```

Не писать длинный отчёт о действиях, которые фактически не выполнялись.

---

# 49. ПЕРВАЯ ЗАДАЧА АГЕНТА

Не писать новый код сразу.

Сначала:

```text
1. прочитать документы;
2. проверить структуру репозитория;
3. проверить Gradle;
4. проверить существующие common models;
5. проверить ToolRegistry/ToolExecutor;
6. проверить ConversationContext;
7. проверить Brain/NLP;
8. проверить app;
9. определить фактическую текущую стадию;
10. найти ближайший незавершённый вертикальный срез.
```

После этого выбрать **ОДИН** следующий этап.

---

# 50. ПЕРВЫЙ ВЕРТИКАЛЬНЫЙ СРЕЗ

Если фундамент действительно готов, приоритет:

```text
UserRequest
→ IntentResolver
→ ToolRegistry
→ ToolExecutor
→ ToolResult
→ AssistantResponse
```

с одной реальной Android-функцией.

Например:

```text
"включи фонарик"
```

Путь должен быть реальным:

```text
voice/text
→ normalized request
→ intent
→ tool call
→ permission/policy
→ flashlight executor
→ result
→ response
```

После этого расширять количество skills.

Не начинать одновременно wake word + ASR + LLM + memory + UI.

---

# 51. ПРИНЦИП VERTICAL SLICE

Каждая новая крупная функция должна по возможности проходить весь путь:

```text
INPUT
→ UNDERSTANDING
→ POLICY
→ ACTION
→ RESULT
→ RESPONSE
→ OBSERVABILITY
```

Так архитектура проверяется реальным использованием, а не количеством созданных файлов.

---

# 52. ЕСЛИ BUILD ЛОМАЕТСЯ

Не переходить к следующей функции.

Алгоритм:

```text
1. сохранить точную ошибку;
2. определить модуль;
3. определить первый реальный источник ошибки;
4. исправить минимально;
5. повторить build;
6. проверить регрессию.
```

Не чинить десять вещей одновременно.

---

# 53. ЕСЛИ НЕЯСНО, ЧТО ДЕЛАТЬ

Приоритет:

```text
working code
>
build correctness
>
architecture consistency
>
roadmap order
>
new feature
```

Нельзя добавлять новую фичу, если фундаментальный build сломан.

---

# 54. ФИНАЛЬНАЯ ЦЕЛЬ

JARVIS должен прийти к архитектуре:

```text
Local perception
      ↓
Local understanding
      ↓
Context + memory
      ↓
Reasoning / planning
      ↓
Secure tools
      ↓
Real-world actions
      ↓
Observable result
      ↓
Natural response
```

При этом:

- локальность — default;
- сеть — capability;
- LLM — reasoning layer, а не root access;
- tools — контролируемый execution layer;
- memory — управляемая;
- UI — отображение реального состояния;
- privacy — default;
- performance — measured;
- safety — enforced.

---

# 55. ЗАПУСК

После прочтения этого файла агент должен начать не с реализации roadmap, а с аудита.

**Команда для первого шага:**

```bash
cd ~/Friday && printf '\\n=== STATUS ===\\n' && git status --short 2>/dev/null || true; printf '\\n=== MODULES ===\\n' && cat settings.gradle.kts; printf '\\n=== FILES ===\\n' && find . -maxdepth 4 -type f | sort | head -n 300
```

После выполнения аудита агент должен:

1. определить текущую стадию;
2. назвать конкретную следующую задачу;
3. показать минимальный план;
4. только затем изменять файлы.

**Не реализовывать весь roadmap. Реализовывать ближайший проверяемый шаг.**
