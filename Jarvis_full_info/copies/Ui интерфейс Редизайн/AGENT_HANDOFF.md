# AGENT HANDOFF — UI 3.0

## Non-negotiables

- Do not create a second microphone or VoiceService owner from UI work.
- Preserve `VoiceController.start / speak / stopSpeaking / stop` wiring.
- Keep existing Home IDs: `homeOrb`, `activateAssistant`, `currentTranscript`, `currentResponse`.
- Treat Camera and Screen Share as partial/stub until real backend exists.
- Never represent a UI-only state as a completed backend capability.

## UX architecture

Primary navigation is now:

`Home → Assistant → Live → Activity → More`

Secondary modules live under Control Center:

`Memory · Capabilities · Automations · Companion · Models · Developer`

All actionable operations should follow:

`intent → plan → preview → confirmation (when needed) → execution → activity`

## Visual system

Use `assets/jarvis-ui.css` tokens. Avoid one-off colors, gradients or component-specific typography. The intended aesthetic is cinematic technical UI, not literal Marvel branding.

## Interaction rules

- One obvious primary action per screen.
- State must be visible without opening diagnostics.
- User-facing language comes first; technical identifiers are secondary.
- Sensitive actions get a distinct confirmation state.
- Failure states provide retry/context rather than only a red error label.
