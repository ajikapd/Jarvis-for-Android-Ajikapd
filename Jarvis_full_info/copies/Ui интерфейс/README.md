# Ui интерфейс — пакет для передачи агенту

**Назначение:** полный снимок текущего Android UI Jarvis, чтобы другой агент мог дорабатывать интерфейс без повторного reverse-engineering всего приложения.

**Дата снимка:** 2026-10-01  
**Корень проекта:** `/mnt/D/Jarvis_for_android`  
**Эта папка:** `Ui интерфейс/`

---

## Быстрый старт для агента

1. Прочитай **`AUDIT.md`** — что реально работает / что заглушка.
2. Открой **`html/index.html`** в браузере — визуальные мокапы всех экранов.
3. Смотри **`SOURCE_MAP.md`** — какой XML/Kotlin отвечает за экран.
4. Следуй **`AGENT_HANDOFF.md`** — правила правок UI без поломки voice pipeline.

Не начинай с V2-10 / Vision / нового Brain, если задача только UI.

---

## Содержимое

| Файл / папка | Содержание |
|--------------|------------|
| `AUDIT.md` | Аудит UI: WORKING / PARTIAL / STUB |
| `SOURCE_MAP.md` | Layout ↔ Activity ↔ id контролов |
| `AGENT_HANDOFF.md` | Инструкции следующему агенту |
| `DESIGN_TOKENS.md` | Цвета, типографика, компоненты |
| `html/index.html` | Навигация по мокапам |
| `html/*.html` | Внешний вид каждого экрана |
| `assets/jarvis-ui.css` | Общие стили мокапов |

---

## Главная навигация приложения

```text
OnboardingActivity (первый запуск)
    ↓
UsageGuideActivity (гид)
    ↓
DashboardActivity (shell + 5 вкладок)
    ├── 0 ГЛАВНАЯ   screen_home.xml
    ├── 1 LIVE      screen_live.xml
    ├── 2 ПАМЯТЬ    screen_memory.xml
    ├── 3 КОМАНДЫ   screen_skills.xml
    └── 4 ЕЩЁ       screen_settings.xml
            ├── Основные
            ├── Companion
            └── Модели
```

Дополнительно: `TestConsoleActivity` (debug), floating overlay orb (`OverlayOrbService`).
