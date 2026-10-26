# SOURCE_MAP — layout ↔ code

## Activities

| Class | Layout | Entry |
|-------|--------|-------|
| `com.jarvis.app.OnboardingActivity` | `activity_onboarding.xml` | First launch if `needsOnboarding` |
| `com.jarvis.app.UsageGuideActivity` | `activity_usage_guide.xml` | After onboarding / from settings |
| `com.jarvis.app.DashboardActivity` | `activity_dashboard_shell.xml` | Main |
| `com.jarvis.app.TestConsoleActivity` | `activity_test_console.xml` | Debug / manifest |

## Dashboard tabs (`showScreen`)

| Index | Label | Layout | Setup in DashboardActivity |
|------:|-------|--------|----------------------------|
| 0 | ГЛАВНАЯ | `screen_home.xml` | events → orb/state/transcript; activate button |
| 1 | LIVE | `screen_live.xml` | `wireLiveControls`, timer, activate |
| 2 | ПАМЯТЬ | `screen_memory.xml` | `setupMemoryScreen` |
| 3 | КОМАНДЫ | `screen_skills.xml` | commands list/chips/search |
| 4 | ЕЩЁ | `screen_settings.xml` | `setupSettingsToggles` + sub-tabs |

Settings sub-tabs (`showSettingsTab`):
- `main` → `#settingsMainPanel`
- `companion` → `#companionPanel` (`screen_companion_panel.xml` included/inflated)
- `models` → `#modelsPanel` (`screen_models_panel.xml`)

## Critical view IDs

### Home
`homeOrb`, `assistantState`, `homeGlyph`, `currentTranscript`, `currentResponse`, `activateAssistant`, `assistantStateDetail`, `homeQuickTip`

### Live
`liveOrb`, `liveState`, `liveGlyph`, `liveTranscript`, `liveTranscriptSaid`, `liveTimer`, `liveTimerDot`, `liveMic`, `liveCam`, `liveScreen`, `liveCaps`, `liveStop`, `liveActivate`, `liveMinimize`, `liveMenu`, `liveCamThumb`, `liveShareCard`, `liveShareStop`, `liveIntent`, `liveTool`

### Memory
`memorySearch`, `factsContainer`, `btnAddMemoryFact`, `memoryModelsStatus`, `btnOpenModelsFromMemory`, `clearMemoryButton`

### Commands
`commandsSearch`, `commandsCategoryChips`, `commandsList`

### Settings (main)
`tabSettingsMain`, `tabSettingsCompanion`, `tabSettingsModels`, `btnOpenUsageGuide`, theme/persona chips, `chatProfile*`, `cloudApiKeyInput`, `asrModeSpinner`, `ttsModeSpinner`, toggles (`togglePreferCloud`, wake, memory, …), diagnostics texts

### Models
See `screen_models_panel.xml` + `item_model_card.xml` (`progress`, `message`, download/delete buttons)

## Custom views
- `com.jarvis.app.CoreView` — орб
- `com.jarvis.app.StateGlyphView` — glyph анимация
- `com.jarvis.app.JarvisToggle` — custom toggle в settings

## Drawables (часто используемые)
`bg_card`, `bg_search`, `bg_action_gold`, `bg_action_danger`, `bg_ctl_circle`, `bg_ctl_active`, `bg_end_capsule`, `bg_timer_pill`, `bg_live_glow`, `bg_persona`, `bg_persona_active`

## Preferences
`JarvisSettings` + `SharedPreferences("jarvis_prefs")` — theme, persona, wake, cloud keys, asr_mode, tts_mode, …
