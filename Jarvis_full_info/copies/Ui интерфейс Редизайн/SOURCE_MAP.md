# SOURCE MAP — JARVIS UI 3.0

## Core

| UI surface | Existing source contract | Mockup |
|---|---|---|
| Shell | `activity_dashboard_shell.xml` / `DashboardActivity` | `00-shell.html` |
| Home | `screen_home.xml` | `01-home.html` |
| Live | `screen_live.xml` | `02-live.html` |
| Memory | `screen_memory.xml` | `03-memory.html` |
| Capabilities | `screen_skills.xml` | `04-commands.html` |
| Main settings | `screen_settings.xml` | `05-settings-main.html` |
| Companion | `screen_companion_panel.xml` | `06-settings-companion.html` |
| Models | `screen_models_panel.xml` + `item_model_card.xml` | `07-settings-models.html` |
| Onboarding | `activity_onboarding.xml` | `08-onboarding.html` |
| Guide | `activity_usage_guide.xml` | `09-usage-guide.html` |
| Test Console | `activity_test_console.xml` | `10-test-console.html` |
| Overlay Orb | `OverlayOrbService` / `VoiceEngine.showOverlay` | `11-overlay-orb.html` |

## New UX surfaces

| UI surface | Purpose | Implementation note |
|---|---|---|
| Assistant | Thread + action plan + confirmation | New UI surface; use the same VoiceController/VoiceService ownership. Do not create a second mic owner. |
| Activity | Execution history / pending actions / retry | Can be fed from existing `JarvisEvents`, router events and tool results. |
| Automations | Routine builder / recurring actions | UI contract only until routine engine exists. |

## Recommended implementation migration

1. Keep `DashboardActivity` as the voice/runtime owner.
2. Migrate manual screen switching to Navigation/Fragments only after the new screens have real layouts wired.
3. Add a shared `ActionCard` / `ActionPreview` component so Assistant, Capabilities and Activity use the same interaction model.
4. Add a single `AssistantState` presentation model for top bar + orb + activity state.
5. Feed Memory / Activity from existing stores/events rather than duplicating state in each screen.
6. Keep diagnostics out of the primary user path.
