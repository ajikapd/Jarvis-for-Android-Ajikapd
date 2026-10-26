# J.A.R.V.I.S. V2 — Android UI Architecture Prototype

## Запуск

Откройте терминал в этой папке и выполните:

```bash
python3 -m http.server 8080
```

Затем откройте `http://localhost:8080`. Для микрофона браузеру обычно нужен `localhost` или HTTPS. На ПК интерфейс располагается в вертикальном контейнере смартфона; на экране телефона использует доступную ширину.

## Экраны

- `index.html` — реакторное ядро, AI Router, Wake Word, voice providers, pipeline latency и демо Overlay Orb.
- `live.html` — анимированное абстрактное сознание, состояние continuous voice, chunks/VAD UI и Structured AI Output.
- `chat.html` — лог сообщений и раскрываемый trace `[INTENT] / [ROUTER] / [TOOL] / [LATENCY]`.
- `scenarios.html` — конфигурируемые автоматизации Android/AI и триггеры смартфона.
- `memory.html` — категории долговременного контекста, приоритет, включение в системный промпт и token-compaction demo.
- `tools.html` — системные навыки Android и матрица разрешений.
- `system.html` — процедурный график, gauges, benchmark, Runtime Services и тест-консоль.
- `profile.html` — router/model catalog, ASR/TTS, настройки компаньона и четыре палитры HUD.

## Состояние и темы

Выборы сохраняются в `localStorage` под ключом `jarvisV2Prototype_v2`, поэтому router, модель, движки голоса, разрешения-демо, сценарии, память и темы не сбрасываются при переходе между страницами. Темы доступны в профиле: Cyan Mark L, Gold/Crimson, Stealth Violet и Red Alert.

## Важное ограничение прототипа

Эта папка — фронтенд-демо UI, а не собранное Android-приложение. Нативные функции нужно связать с реализацией Jarvis V2 в Android-коде: реальная маршрутизация, локальные GGUF-модели, облачные ASR/TTS, Overlay Window, AlarmManager, системные настройки, уведомления и сервисы. Матрица разрешений, runtime services, показатели диагностики, ping и latency здесь симулируются; включение демо-переключателя не выдаёт системное разрешение. Распознавание/озвучивание из браузера использует только доступные браузерные API и не тождественно Offline STT/TTS из Android.
