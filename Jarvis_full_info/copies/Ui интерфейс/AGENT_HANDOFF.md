# AGENT_HANDOFF — как дорабатывать UI

## Цель пакета
Дать следующему агенту визуал + аудит, чтобы править UI **точечно**, не ломая voice stack.

## Обязательные правила

1. **Не трогать** Wake/KWS, `VoiceEngine` ownership, V2-07 stream, V2-08 ASR, V2-09 TTS **без явной задачи**.
2. Live shell = `activity_dashboard_shell.xml`. Не подключать `activity_dashboard.xml` как main.
3. Экраны сейчас = **inflate layout в FrameLayout**, не Navigation Component. Меняя навигацию — мигрируй целиком или сохраняй `showScreen(index)`.
4. Camera / screen share в Live — **stubs**. Не обещать Vision в UI copy, пока нет backend.
5. Секреты (API keys) — только в EditText + prefs; не логировать.
6. Сохраняй тёмный design language (см. `DESIGN_TOKENS.md`), если нет задачи на редизайн.

## Рекомендуемый порядок работ

1. Открыть `html/index.html` → согласовать целевой вид.
2. Найти layout в `SOURCE_MAP.md`.
3. Править XML + wiring в `DashboardActivity` (или dedicated Activity).
4. Проверить: `./gradlew :app:assembleDebug`
5. Device smoke: вкладки + activate + settings save.

## Что можно улучшать безопасно

- Копирайт, spacing, typography
- Accessibility (contentDescription, contrast)
- Status chips sync with diagnostics
- Memory search UX (настоящий EditText + filter)
- Companion panel humanization
- Light theme completeness
- Hide or clearly label Live cam/screen as «скоро»

## Чего избегать

- Второй VoiceService / второй AudioCapture
- Hardcode API keys
- Fake “streaming Vision” UI
- Переписывание bottom nav без тестов на tab restore (`EXTRA_OPEN_TAB`)
- Удаление settings ASR/TTS без миграции prefs

## Проверка после UI-изменений

- [ ] Onboarding → Guide → Dashboard
- [ ] Все 5 вкладок открываются
- [ ] Settings sub-tabs Main/Companion/Models
- [ ] Activate JARVIS + state updates
- [ ] Live activate / stop
- [ ] Memory list add/clear
- [ ] Commands list scroll/filter
- [ ] Models download UI still works
- [ ] assembleDebug green

## Контакты с backend (только чтение для UI)

- Events: `JarvisEvents` / `AssistantEvent`
- State: `AssistantState`
- Settings: `JarvisSettings`
- Models: `ModelManager` / `ModelCatalog`
- Memory: companion memory + LongTermMemory paths in Dashboard setup
