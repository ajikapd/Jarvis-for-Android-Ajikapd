# AUDIT — текущее состояние UI Jarvis

**Источник правды:** layouts в `app/src/main/res/layout/` + `DashboardActivity.kt` + related Activities.  
**Не путать:** `activity_dashboard.xml` — это старый **UI Reference v2** (длинный визуальный референс), **не** live shell. Live shell = `activity_dashboard_shell.xml`.

---

## 1. Карта экранов

| # | Экран | Layout | Host | Статус UI |
|---|--------|--------|------|-----------|
| A | Shell (шапка + bottom nav) | `activity_dashboard_shell.xml` | `DashboardActivity` | **WORKING** |
| 0 | Главная | `screen_home.xml` | Dashboard tab 0 | **WORKING** |
| 1 | Live | `screen_live.xml` | Dashboard tab 1 | **PARTIAL** (голос ок, cam/screen — stub UX) |
| 2 | Память | `screen_memory.xml` | Dashboard tab 2 | **WORKING** (данные local) |
| 3 | Команды | `screen_skills.xml` | Dashboard tab 3 | **WORKING** |
| 4a | Ещё → Основные | `screen_settings.xml` main panel | Dashboard tab 4 | **WORKING** / часть toggles cosmetic |
| 4b | Ещё → Companion | `screen_companion_panel.xml` include | tab 4 | **PARTIAL** (diagnostics-heavy) |
| 4c | Ещё → Модели | `screen_models_panel.xml` + `item_model_card.xml` | tab 4 | **WORKING** (download) |
| B | Онбординг | `activity_onboarding.xml` | `OnboardingActivity` | **WORKING** |
| C | Гид | `activity_usage_guide.xml` | `UsageGuideActivity` | **WORKING** |
| D | Test Console | `activity_test_console.xml` | `TestConsoleActivity` | **WORKING** (dev) |
| E | Not implemented | `screen_not_implemented.xml` | fallback | **STUB** placeholder |
| F | UI Reference (legacy) | `activity_dashboard.xml` | не shell | **REFERENCE ONLY** |
| G | Overlay orb | code + HTML asset | `OverlayOrbService` / `VoiceEngine.showOverlay` | **PARTIAL** |

Bottom nav labels: `ГЛАВНАЯ | LIVE | ПАМЯТЬ | КОМАНДЫ | ЕЩЁ`

---

## 2. Что работает (WORKING)

### Shell
- Header `JARVIS · LOCAL`
- Динамический bottom nav (5 вкладок), active gold state
- Inflate screen into `screenContainer`

### Главная
- `CoreView` orb (animated)
- `assistantState` / `StateGlyphView` от `JarvisEvents`
- Transcript + response панели
- Кнопка **Активировать JARVIS** → `VoiceController.start` + permissions
- Статус фонового сервиса

### Live
- Orb + state + timer при активном live
- Transcript / said text из events
- **Начать Live-разговор** / activate
- Mic button (visual toggle)
- Stop / minimize wiring
- Intent/tool debug lines

### Память
- Список фактов из local memory store
- Добавить факт вручную
- Очистить память
- Ссылка в каталог моделей
- Поиск (UI присутствует, wiring через setupMemoryScreen)

### Команды
- Каталог skills/команд с категориями
- Поиск + chips
- Copy / выполнить (через debug command path если сервис активен)

### Настройки → Основные
- Тема (Classic / Arc / Stealth / Light) — preference + UI
- Персона
- Chat profile Быстро/Баланс/Качество
- Cloud LLM API key + prefer cloud
- **ASR mode** spinner (V2-08)
- **TTS mode** spinner (V2-09)
- Wake enable / sensitivity
- Memory toggle, confirm sensitive
- Diagnostics snapshot line
- Открыть Usage Guide

### Настройки → Модели
- Каталог моделей, download/progress/delete через `ModelManager`

### Онбординг
- Пошаговые permissions (mic, notifications, overlay, accessibility, battery, …)
- Continue → guide / dashboard

### Usage Guide
- Статический обучающий контент + «Понятно»

### Test Console
- Text command + voice status (debug)

---

## 3. PARTIAL / cosmetic / half-wired

| Элемент | Что видно | Реальность |
|---------|-----------|------------|
| Live **Camera** | Toast «в разработке», thumb «Cam · скоро» | Vision UI stub, нет camera pipeline на live path |
| Live **Screen share** | Toggle показывает share card | Card visibility only — **нет** MediaProjection/stream |
| Live **Captions** | Visual active state | Не отдельный captions engine |
| Live share card | «Экран транслируется» | UI-only |
| Adaptive Listening / Low Power toggles | В настройках | Preference flags; поведение ограничено |
| Encryption «ON» | Показано как ON | Не полноценный UI-управляемый crypto layer |
| Companion panel | Sources, events, proactive suggest | Diagnostics / Stage-7 style; не полный autonomy UI |
| Theme LIGHT | Выбор есть | Визуал в основном dark; light — слабое покрытие |
| Home chips «модель/слух/сеть» | Static labels in XML | Не всегда синхронизированы с router telemetry |
| Overlay orb | Floating CoreView | Зависит от overlay permission; HTML orb отдельный |

---

## 4. STUB / не использовать как «готово»

| Элемент | Комментарий |
|---------|-------------|
| `screen_not_implemented.xml` | Generic placeholder |
| `activity_dashboard.xml` | Design reference only — **не** production navigation |
| Live camera preview | `visibility=gone` + toast |
| Live screen share backend | Нет real capture |
| Cloud Vision UI | Нет dedicated screen |
| MCP / Plugins / Browser Agent UI | Нет пользовательских экранов (backend constructed elsewhere) |
| Separate Chat transcript history UI | Нет полноценного chat thread UI (голос-first) |

---

## 5. Voice vs UI ownership (важно)

```text
UI (DashboardActivity)
  → VoiceController.start / speak / stopSpeaking
  → VoiceService (foreground) owns mic + brain turn
  → VoiceEngine owns Wake/ASR/TTS playback (JarvisSpeaker)

НЕ создавать второй VoiceService / второй mic owner из UI задач.
```

ASR/TTS hybrid modes настраиваются в Settings → Основные; runtime path V2-07…V2-09.

---

## 6. Дизайн-язык (факт)

| Token | Value |
|-------|-------|
| BG | `#0A0C10` / home-live `#0F0E14` |
| Panel | `#11151B` / `#1A1822` |
| Primary gold | `#E0AD55` / `#E3A542` |
| Cyan | `#62D9DF` |
| Text | `#F4F0E7` / `#F2EEE6` |
| Muted | `#9DA5AE` / `#9A96A8` |
| Danger | `#E46B58` / `#D9634A` |
| Status font | monospace, letter-spacing |

См. также `DESIGN_TOKENS.md` и `app/src/main/res/values/colors.xml`.

---

## 7. Известные UX-долги (для следующего агента)

1. Live cam/screen — либо убрать misleading UI, либо подключить реальный backend (Vision = later roadmap).
2. Синхронизировать status chips (модель/сеть) с `BrainGraph.diagnosticsSnapshot` / ASR-TTS telemetry.
3. Light theme consistency.
4. Memory search: убедиться, что EditText, а не только TextView-label (сейчас `memorySearch` — TextView с кликом?).
5. Companion UI — упростить для пользователя vs raw diagnostics.
6. Не ломать bottom-nav inflate pattern без миграции на Fragment/NavComponent (сейчас manual inflate).

---

## 8. Версии / roadmap UI

- New Roadmap Stages 8C–27: много backend; UI только частично отражает.
- V2-08 ASR / V2-09 TTS: settings controls добавлены в Основные.
- V2-10 OS Presence — **не** начинать из этого пакета без явной задачи.
