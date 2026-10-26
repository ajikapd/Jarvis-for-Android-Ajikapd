# AUDIT — JARVIS UI 3.0

Этот пакет является визуально-информационной переработкой UI-мокапов. Backend contract из исходного проекта не переписывается в макете; там, где текущий backend partial/stub, это явно отражено в UI.

## Новая карта экранов

| Surface | Файл | Роль |
|---|---|---|
| Shell | `html/00-shell.html` | Новая модель навигации и state visibility |
| Home / Command Deck | `html/01-home.html` | Голосовой ready state, orb, текущий запрос, quick actions |
| Assistant | `html/02-assistant.html` | **Новая first-class surface**: conversation thread, action plan, confirmation |
| Live | `html/02-live.html` | Continuous voice session |
| Activity | `html/08-activity.html` | **Новая first-class surface**: execution trace, pending approvals, retry |
| Memory | `html/03-memory.html` | Управляемый long-term context |
| Capabilities | `html/04-commands.html` | Actions/capabilities вместо простого списка фраз |
| Control Center | `html/05-settings-main.html` | Главный хаб настроек и secondary tools |
| Companion | `html/06-settings-companion.html` | Presence/context/proactive suggestions |
| Models | `html/07-settings-models.html` | ASR/TTS/LLM runtime stack |
| Automations | `html/10-automations.html` | **Новая architectural surface** для routines |
| Onboarding | `html/08-onboarding.html` | First-run access setup |
| Usage Guide | `html/09-usage-guide.html` | Mental model: speak → see → trust → tune |
| Test Console | `html/10-test-console.html` | Инженерный surface |
| Overlay Orb | `html/11-overlay-orb.html` | Floating voice affordance |
| Not Implemented | `html/12-not-implemented.html` | Честный fallback |

## Новая нижняя навигация

```text
ГЛАВНАЯ | АССИСТЕНТ | LIVE | АКТИВНОСТЬ | ЕЩЁ
```

Эта навигация отражает пользовательские контексты, а не внутренние модули. Memory, Capabilities, Models, Companion, Automations и Developer доступны через `ЕЩЁ` / Control Center.

## Action contract

Новый общий UX-контракт для операций:

```text
user intent
  → plan / capability
  → preview
  → confirmation (если чувствительное действие)
  → tool execution
  → activity result
```

Это позволяет одному и тому же действию иметь одинаковую презентацию в Assistant, Capabilities и Activity.

## Сохранённые production IDs

Home сохраняет существующие IDs:
- `homeOrb`
- `activateAssistant`
- `currentTranscript`
- `currentResponse`

## Partial / stub

- Live camera preview — disabled / partial.
- Live screen share — disabled / partial.
- Companion proactive path — UI surface, но не обещает полноценную автономию.
- Automations — архитектурная UI-поверхность; routine engine должен быть подключён отдельно.
- Overlay Orb — зависит от overlay permission.

## Что сознательно убрано из primary surface

Raw diagnostics, ASR/TTS provider details, cloud key fields и внутренние Stage labels больше не конкурируют с основным пользовательским workflow. Они доступны через Control Center / Developer surface.
