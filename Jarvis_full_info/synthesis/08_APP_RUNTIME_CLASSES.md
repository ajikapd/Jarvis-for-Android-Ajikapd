# 08 — Runtime-классы app-слоя (инвентарь фактов)

Ниже — ключевые типы в `app/src/main/kotlin` (не полный listing; полный список в `inventory/app_classes.txt`).

## Голос / сервис

- `VoiceService`, `VoiceController`, `VoiceEngine`
- `JarvisSpeaker`, `HybridTtsBridge` / cloud TTS factory
- ASR: `AndroidSpeechCloudAsrBackend`, `OpenAiWhisperStreamingAsrBackend`, `CloudAsrFactory`, Hybrid ASR controller bridge
- `OverlayOrbService`, `CoreView`, `StateGlyphView`

## Мозг / wiring

- `BrainGraph` — composition root
- `ModelManager`, `ModelCatalog`
- `LlamaChatLlmEngine`

## UI

- `DashboardActivity` — shell + все вкладки
- `OnboardingActivity`, `UsageGuideActivity`, `TestConsoleActivity`
- `ThemeApplier`, `ActivityFeed`, `ConversationStore`
- `JarvisToggle`, `JarvisSlider`
- `CoreTheme`

## Companion

- `companion/CompanionBehavior`, `CompanionMemory`, `CompanionProactive`
- `companion/SystemStateObserver`, `UsageStatsObserver`

## Settings

- `JarvisSettings` (SharedPreferences facade)

## Debug

- `DebugCommandReceiver` (debug source set)
- Test console activity

Полные деревья пакетов `core` и `brain` — `inventory/core_brain_packages.txt`.
