# CHANGELOG — Jarvis Android

Все значимые изменения и результаты проверки на реальном устройстве фиксируются здесь.

---

## Этап 0 — Разведка и подготовка окружения

- **Дата:** 2026-09-14
- **Устройство:** Xiaomi 23117RA68G (emerald_global), Android 16 (SDK 36), HyperOS 3.0 (`OS3.0.305.0.WNFMIXM`)
- **SoC:** MediaTek MT6789 (Helio G99) — 2x Cortex-A76 + 6x Cortex-A55
- **RAM:** 12 GB (доступно ~3.6 GB при обычной нагрузке)
- **Хранилище:** ~291 GB свободно
- **GPU:** Mali-G57 (Vulkan + OpenCL доступны)
- **Результаты:**
  - ADB подключение по USB подтверждено
  - Локальный SDK установлен в `$HOME/Android/Sdk` (platforms android-34, build-tools 34.0.0)
  - Gradle 8.9 + AGP 8.6.1 + Kotlin 2.0.21 — рабочее окружение
  - `DEVICE_SPECS.md` создан с полными характеристиками и обоснованием выбора моделей
  - LLM: Qwen2.5-1.5B-Instruct Q4_K_M (ориентир ~1.1 GB, 5–10 tok/s на A76)

---

## Этап 1 — Скелет приложения и живучий фон

- **Дата:** 2026-09-14
- **Что сделано:**
  - Мультимодульный Gradle-проект из 13 модулей (app + 12 библиотек) по архитектуре из промпта
  - `core`: событийная шина `JarvisEvents` (SharedFlow) + `AssistantState` enum
  - `background-service`: `JarvisService` (Foreground Service, тип microphone, START_STICKY) + `BootReceiver` (BOOT_COMPLETED)
  - `permissions-onboarding`: `PermissionsHelper` — утилиты проверки/открытия настроек (HyperOS autostart, overlay, battery, accessibility)
  - `app`: онбординг-экран (6 шагов: RECORD_AUDIO, POST_NOTIFICATIONS, overlay, батарея, автозапуск, Accessibility), кнопка запуска
  - 9 модулей-заглушек (wakeword, audio, asr, tts, brain, tools, screen-control, overlay-ui, connectivity) с marker-классами
  - Иконка приложения (vector, синий круг + cyan линии)
- **Установка на устройство:**
  - `adb install -r` — Success
  - `adb shell pm grant` — RECORD_AUDIO, POST_NOTIFICATIONS выданы
  - `SYSTEM_ALERT_WINDOW` — allow через appops
- **Проверка на реальном железе:**
  - ✅ Foreground Service стартует и показывает уведомление «Ассистент активен»
  - ✅ Сервис пережил выключение/включение экрана (PID сохранён)
  - ✅ Сервис пережил переключение между приложениями (HOME → Settings → обратно)
  - ✅ Сервис оставался стабилен после 2 минут бездействия
  - ⏳ Долговременная проверка (несколько часов) — требует ручного наблюдения
- **Ограничения/заметки:**
  - `BOOT_COMPLETED` защищённый broadcast — тест через `adb shell am broadcast` невозможен, реальная перезагрузка тестируется в Этапе 9
  - Кнопка «Запустить Jarvis» в debug-сборках доступна без полной настройки батареи (BuildConfig.DEBUG fallback)
  - В HyperOS при первом запуске появляется системный экран «Автозапуск в фоновом режиме» — не мешает работе
---

## Этап 2 — Вертикальный срез «включи фонарик» (deterministic-first)

- **Дата:** 2026-09-14
- **Что сделано:**
  - Модель/контракты в `core/common` (по AGENT_START раздел 12):
    `UserRequest`, `AssistantResponse`, `Intent`, `IntentType`, `Slot`, `ConfidenceLevel`,
    `ToolDefinition`, `ToolParameter`, `ToolCall`, `ToolResult`, `ToolResultStatus`,
    `RiskLevel`, `OnlineRequirement`, `ParameterType`, `AssistantResponseStatus`,
    `ConversationContext`, интерфейсы `Tool`, `ToolRegistry`, `ToolExecutor`,
    `DefaultToolExecutor`, `ToolPolicy`/`RiskBasedPolicy`/`PolicyDecision`, `IntentResolver`
  - `core/nlp`: `RussianNormalizer` + `RuleBasedIntentResolver` (rule-based, deterministic-first)
  - `core/event`: расширены события (`IntentResolved`, `ToolExecuted`) и `AssistantState`
    (IDLE/LISTENING/THINKING/EXECUTING/SPEAKING)
  - `tools`: `FlashlightTool` (CameraManager.setTorchMode, back camera, риск SAFE)
  - `brain`: `AssistantOrchestrator` (UserRequest→Intent→policy→Tool→Result→Response) +
    `ResponsePlanner` + `ToolCallFactory`
  - `app`: `BrainGraph` (composition root), `TestConsoleActivity` (debug-консоль,
    только debug-сборка), `DebugCommandReceiver` (запуск команды с ADB, только debug)
  - Удалён мусор из корня: папка `src/` с дублирующими заглушками
  - Debug-компоненты (console + receiver) вынесены в `app/src/debug/AndroidManifest.xml` —
    не попадают в release
- **Тесты (JVM):**
  - `core`: RussianNormalizerTest (5), RuleBasedIntentResolverTest (5) — pass
  - `brain`: AssistantOrchestratorTest (5: PROCESSED, NEEDS_CLARIFICATION, DENIED,
    NEEDS_CONFIRMATION, ConversationContext) — pass
- **Проверка на реальном устройстве** (`adb shell am broadcast` debug-приёмник):
  - ✅ «включи фонарик» → intent=FLASHLIGHT action=on conf=0.95 → tool=flashlight/SUCCESS
    → status=PROCESSED → «Фонарик включён.» (134 ms e2e)
  - ✅ «выключи фонарик» → intent=FLASHLIGHT action=off → SUCCESS → «Фонарик выключен.» (10 ms)
  - ✅ «расскажи анекдот» → intent=UNKNOWN → NEEDS_CLARIFICATION
  - ✅ Debug-консоль (`TestConsoleActivity`) открывается и активна
- **Заметки:**
  - `android.util.Log` в `brain`/`core` запрещён — ломает JVM-юнит-тесты; логирование
    ведётся из app-слоя (`DebugCommandReceiver`)
  - Старые 9 модулей-заглушек (wakeword, audio, asr, tts, screen-control, overlay-ui,
    connectivity) пока пустые — будут наполняться следующими вертикальными срезами
  - `unsupported` «включи фонарик» через UI требует ручного ввода (рядовая штуковина adb)

---

## Этап 3 — Голосовой срез: wake word «джарвис» → ASR → команда

- **Дата:** 2026-09-14
- **Что сделано:**
  - `audio-engine`: `AudioCapture` (AudioRecord PCM16, 16 kHz mono, чтение в корутине)
  - `asr-engine`: `SherpaAsrSession` + `AsrChunkResult`; модель
    `sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16` (offline, ~30 MB)
  - `wakeword-engine`: `WakeWordEngine` — детект «джарвис» в том же ASR-потоке (rule-based),
    `OnnxWakeWordEngine` (резерв, KWS-модель не используется); `wakeDetected` SharedFlow,
    `userRequests` эмитится всегда (пустой текст = bare wake)
  - end-to-end: AudioCapture → SherpaAsrSession → WakeWordEngine → BrainGraph → фонарик
  - Debug: ASR_TEST (прогон WAV через ASR), исправлен баг sign-расширения в чтении PCM16
- **Проблема и фикс:** каталоги моделей, созданные `adb shell`, невидимы приложению —
  теперь приложение само делает `modelDir.mkdirs()` (owner `u0_a428`), затем push файлов
- **Проверка на устройстве:**
  - ✅ `voice: listening started (say "джарвис, ...")` — модель грузится, AudioRecord стабилен
  - ✅ ASR_TEST: распознана русская речь end-to-end на устройстве
  - ✅ «джарвис, включи свет» и «джарвис, выключи свет мне быстренько тут» — фонарик переключается
  - ✅ Без команды после «джарвис» — bare wake → «слушаю, сэр» (голосом)

---

## Этап 4 — TTS + слышимый фидбек (в процессе)

- **Дата:** 2026-09-14
- **Решение:** sherpa-onnx (не Google TTS — отклонён пользователем). Модель
  **Piper ru_RU-dmitri-medium int8** (мужской голос, 22050 Гц, ~20 MB, MIT) — RTF ~0.5-0.65.
  Supertonic-3 (живой/красочный, 10 спикеров) отклонён: RTF ~1.8 → на телефоне 4-6 с отклика.
- **Что сделано:**
  - `tts-engine`: `JarvisTts` (обёртка sherpa-onnx `OfflineTts`) + `TtsPlayer`
    (AudioTrack PCM_FLOAT; двухтональный chime 880→1174 Гц на wake word)
  - `app`: `JarvisSpeaker` — barge-in (stop речи) + chime на `wakeDetected`; bare wake →
    «Слушаю, сэр.»; команда → исполняется молча (ради скорости); fallback без модели — только chime
  - Endpoint-правила ASR ужаты для быстрого отклика: rule1 2.4→1.6 с, rule2 1.4→1.0 с
  - Модель dmitri запушена на устройство (chmod 755 из-за shell-ownership), TTS init подтверждён:
    `TTS ready (22050 Hz)`
- **Тесты (JVM):** core 10 + brain 5 + wakeword 8 = 23 — BUILD SUCCESSFUL
- **Доработки (второй раунд, по итогам теста голосом):**
  - **Двойной бип + двойная фраза** — причина: параллельные голосовые пайплайны
    (adb-бродкаст + кнопка консоли; `BroadcastReceiver` создаётся заново на каждую доставку,
    поэтому старый гард не работал). Решение: единый процесс-wide `VoiceController`/`VoiceEngine`
    (app-слой) — второй старт всегда гасит первый.
  - Движок: дедуп wake-эмиссии (1.5 с) и одинаковых запросов (1 с) в `WakeWordEngine`.
  - **Тишина после bare wake:** `AWAITING_COMMAND` теперь прерывается сам через 6 с
    (было 15 с) → состояние вернётся в IDLE без голосового напоминания.
  - Эхо-защита: микрофон не кормит ASR не только во время ответа, но и 400 мс после
    (дренажный хвост); известные фразы-самоответы («слушаю сэр») отфильтровываются.
  - Диагностика: журнал `voice[V#]` с id сессии и переходами состояний `state X -> Y`.
  - Тесты wakeword: 8 → 10 (bare wake + следование команды, повторное срабатывание).
- **Осталось:** проверка живым голосом (chime → «слушаю, сэр» → команда молча), roadmap-статус
