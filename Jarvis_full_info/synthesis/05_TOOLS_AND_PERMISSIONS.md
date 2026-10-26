# 05 — Tools, разрешения, companion

## Tools (модуль `:tools`)

Реализованные классы (файлы `*Tool*.kt`):

| Класс | Назначение (по коду/имени) |
|-------|----------------------------|
| FlashlightTool | Фонарик CameraManager |
| VolumeTool | Громкость |
| BrightnessTool | Яркость |
| WeatherTool | Погода (сеть) |
| LauncherTool | Запуск приложений |
| MusicTool | Музыка / media intents |
| SystemSettingTool | Системные настройки (wifi/bt и т.п.) |
| KnowledgeTool | Знания / Wikipedia-like |
| TimerTool | Таймер |
| ClipboardTool | Буфер |
| CalendarTool | Календарь |
| ContactsTool | Контакты |
| CallTool | Звонки |
| SmsTool | SMS |
| TelegramSendTool | Отправка через Telegram (UI automation path) |
| NotificationTool | Уведомления |
| FilesTool | Файлы |
| ScreenshotTool | Скриншот |
| UiClickTool / UiTypeTool / UiSwipeTool / UiGlobalTool / UiReadTool / UiWaitTool | UI automation через accessibility |

Регистрация и исполнение идут через ToolRegistry / DefaultToolExecutor / BrainGraph wiring.

Policy на live-пути: `RiskBasedPolicy`.

## Разрешения (`AndroidManifest`)

Runtime / special (неполный список ключевых):

- RECORD_AUDIO, POST_NOTIFICATIONS  
- FOREGROUND_SERVICE + FOREGROUND_SERVICE_MICROPHONE  
- RECEIVE_BOOT_COMPLETED, SYSTEM_ALERT_WINDOW  
- REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, WAKE_LOCK  
- SMS / CALENDAR / CONTACTS / CALL_PHONE / CALL_LOG  
- WRITE_SETTINGS, WIFI/BT  
- LOCATION coarse/fine  
- READ_MEDIA_* / storage legacy  
- PACKAGE_USAGE_STATS  
- INTERNET (модели + cloud)  
- KILL_BACKGROUND_PROCESSES  

Queries: launcher apps, dial, media browse, отдельные пакеты (напр. MTS Music).

## Онбординг

`PermissionsHelper` + `OnboardingActivity`: RECORD_AUDIO, notifications, overlay, battery, autostart (HyperOS), Accessibility и др.

## Companion

- `PhoneContextBus`, `ObservationDiagnosticsBuffer`  
- `SystemStateObserver`, `UsageStatsObserver`  
- `CompanionMemory`, `CompanionBehavior`, `CompanionProactive`  
- UI: companion panel в Control Center / settings tabs  
- ProactiveDecision types: SUGGESTION / QUESTION / CONFIRMATION_REQUEST / NO_OP  

Отдельно в core/BrainGraph: `ProactiveAgent` (Stage 25) — library wiring.

## Screen control

`JarvisAccessibilityService` + UI tools; OCR ML Kit assets присутствуют в mergeDebugAssets.

## Overlay

`OverlayOrbService` / VoiceEngine overlay при свёрнутом Live (нужен SYSTEM_ALERT_WINDOW).
