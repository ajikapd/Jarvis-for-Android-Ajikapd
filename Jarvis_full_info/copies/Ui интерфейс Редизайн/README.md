# JARVIS UI — Cinematic Assistant Interface 3.0

Полностью переработанный UI-мокап для Android Jarvis. Визуальная система вдохновлена кинематографичным sci-fi / superhero tech интерфейсом: graphite/navy, reactor cyan, controlled gold/red, glass/steel panels и HUD microcopy — без франшизных логотипов и арта.

## Новая архитектура

```text
Onboarding
  ↓
Command Deck (Главная)
  ├── Ассистент      → chat / context / action plan
  ├── Live           → continuous voice
  ├── Активность     → execution trace / approvals / retry
  └── Ещё            → Control Center
        ├── Память
        ├── Команды
        ├── Автоматизации  (new UI surface)
        ├── Companion
        ├── Модели
        └── Developer / Guide

Overlay Orb остаётся независимым компактным voice affordance.
```

## Сохранённые функциональные контракты

На Home сохранены IDs `homeOrb`, `activateAssistant`, `currentTranscript`, `currentResponse`. Названия legacy экранов и основной набор функциональных поверхностей также сохранены; новые экраны добавлены поверх них.

## Важно

Live Camera / Screen Share, routines backend и часть Companion функций остаются честно обозначены как partial/stub там, где текущий backend не предоставляет реальный pipeline.
