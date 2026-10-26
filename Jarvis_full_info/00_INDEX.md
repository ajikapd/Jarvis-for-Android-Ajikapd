# Jarvis_full_info — полный снимок знаний о проекте

**Дата сборки пакета:** 2026-10-06  
**Репозиторий:** `/mnt/D/Jarvis_for_android`  
**Приложение:** `com.jarvis.assistant` · versionName `0.1.0-dev` · versionCode `1`  
**Правило содержимого:** только факты о текущем состоянии проекта (код, отчёты, UI-пакеты, настройки). Планы «что сделать дальше» не описываются как roadmap-идеи; статусы уже выполненных этапов приведены как факты.

---

## Как пользоваться архивом

| Путь | Содержание |
|------|------------|
| `00_INDEX.md` | Этот файл |
| `synthesis/` | Сводные документы на русском (обзор, архитектура, модели, UI, tools, настройки, статус V2) |
| `copies/md_files/` | **Все** существующие отчёты проекта из `md_files/` (аудит, стадии 9–27, V2-00…V2-09, handoff, device tests) |
| `copies/CHANGELOG.md` | Полный changelog с начала разработки |
| `copies/Ui интерфейс/` | UI-аудит / HTML-мокапы (предыдущий пакет) |
| `copies/Ui интерфейс Редизайн/` | UI 3.0 redesign (токены, handoff, HTML) |
| `inventory/` | Дампы структуры: gradle-модули, kotlin-деревья, layouts, tools, ModelCatalog, JarvisSettings |

---

## Краткая идентичность продукта

Jarvis — мультимодульный **Android voice assistant** (offline-first + hybrid cloud):

- wake word «джарвис» → ASR → оркестрация → tools / диалог → TTS;
- packageId `com.jarvis.assistant`, namespace `com.jarvis.app`;
- minSdk 29, target/compileSdk 34, ABI `arm64-v8a`, Java/Kotlin 17;
- тестовое устройство в отчётах: Xiaomi 23117RA68G (Helio G99), Android 16 / HyperOS 3.

---

## С чего начать чтение

1. `synthesis/01_OVERVIEW.md` — общая картина  
2. `synthesis/02_ARCHITECTURE.md` — модули и runtime pipeline  
3. `synthesis/03_MODELS_AND_VOICE.md` — модели, ASR/TTS/LLM  
4. `synthesis/04_UI.md` — интерфейс UI 3.0  
5. `copies/md_files/JARVIS_V2_FULL_AUDIT.md` — полный системный аудит V2-00  
6. `copies/CHANGELOG.md` — хронология этапов с 2026-09-14  

---

## Важная оговорка из аудита

В `BrainGraph` собрана богатая библиотека Stage 11–27 (planner, agent loop, Memory2, vision stubs и т.д.), но **живой пользовательский путь** идёт через `VoiceService` → `AssistantOrchestrator.process()` (skills + DialogueResponder / hybrid LLM). Planner/AgentLoop на обычном `process()` не являются основным продуктовым путём (см. аудит).
