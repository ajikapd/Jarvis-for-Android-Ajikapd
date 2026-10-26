# 01 — Обзор проекта Jarvis (текущее состояние)

## Что это

Android-приложение **Jarvis Assistant** — голосовой ассистент с:

- локальным wake / ASR / TTS;
- гибридным диалогом (облако NaraRouter + локальный GGUF);
- набором системных и UI tools;
- companion/proactive слоем (частично);
- Control Center UI 3.0 (Home / Assistant / Live / Activity / More).

Имя root Gradle-проекта: `jarvis`.  
applicationId: `com.jarvis.assistant`.

## Версия и окружение сборки (из кода и CHANGELOG)

| Параметр | Значение |
|----------|----------|
| versionName | 0.1.0-dev |
| versionCode | 1 |
| minSdk | 29 |
| targetSdk / compileSdk | 34 |
| NDK ABI | arm64-v8a |
| JVM | 17 |
| Gradle | 8.9 (CHANGELOG) |
| AGP | 8.6.1 (CHANGELOG) |
| Kotlin | 2.0.21 (CHANGELOG) |

Устройство разработки/тестов в документации: **Xiaomi 23117RA68G** (emerald), MediaTek Helio G99, 12 GB RAM.

## Основные входные точки приложения

| Компонент | Роль |
|-----------|------|
| `OnboardingActivity` | Первый запуск: разрешения |
| `UsageGuideActivity` | Гид после онбординга |
| `DashboardActivity` | Главный UI shell + вкладки |
| `VoiceService` | Foreground service, владелец voice pipeline |
| `VoiceController` / `VoiceEngine` | Старт/стоп голоса, mic, wake, ASR handoff |
| `BrainGraph` | Composition root / DI-реестр |
| `AssistantOrchestrator` | Обработка `UserRequest` |
| `TestConsoleActivity` | Debug-консоль (debug-сборка) |
| `DebugCommandReceiver` | ADB inject команд (debug) |
| `OverlayOrbService` | Оверлей-орб при свёрнутом Live |
| Accessibility / NLS | Screen control / notifications (companion) |

## Что реально умеет пользователь сегодня

1. Сказать «джарвис» → команда (фонарик, громкость, приложения, погода и др. через skills/tools).  
2. Live-режим непрерывного разговора.  
3. Текстовый диалог на вкладке Ассистент (тот же VoiceController).  
4. Диалог через cloud LLM (если ключ + сеть + preferCloud) с fallback на локальную GGUF.  
5. Скачать модели из UI (Ещё → Модели).  
6. Память фактов (LongTermMemory), настройки темы/персоны/ASR/TTS режимов.  
7. Companion-панель (диагностика контекста/proactive suggestions — UI).  
8. Автоматизации (RoutineStore in-memory UI).  
9. Activity-лента событий из JarvisEvents.

## Жёсткие продуктовые инварианты (из agent handoff UI / V2)

- Не создавать второго владельца микрофона / VoiceService из UI.  
- Home IDs сохранены: `homeOrb`, `activateAssistant`, `currentTranscript`, `currentResponse`.  
- Camera / Screen share в Live — честные partial (без fake backend).  
- V2-08/09: hybrid ASR/TTS подключены; wake/KWS не переписывались.

## Документация в дереве проекта

Всё ниже уже скопировано в `copies/`:

- `md_files/` — десятки отчётов (стадии 9–27, V2-00…V2-09, device logs/tsv).  
- `CHANGELOG.md` — поэтапная история с 2026-09-14.  
- `Ui интерфейс/` и `Ui интерфейс Редизайн/` — UI atlas / redesign package.
