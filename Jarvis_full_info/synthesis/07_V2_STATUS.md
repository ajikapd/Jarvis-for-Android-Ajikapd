# 07 — Статус Roadmap / V2 (факт на момент документов в md_files)

## New Roadmap (до V2)

Считается **закрытым** в документации (`ROADMAP_FULL_COMPLETION_REPORT.md`, Stages 8C–27).

Подтверждено в baseline V2 docs: unit 415/415, device smoke, наличие библиотек AI Router/Planner/AgentLoop/Memory2/Vision/Remote/Diagnostics и т.д.

## Jarvis Roadmap V2 (`md_files/Jarvis_Roadmap_V2.md`)

Файл roadmap существует. По отчётам в `md_files/` **зафиксированы как COMPLETE** как минимум:

| Stage | Отчёт | Статус в отчёте |
|-------|-------|-----------------|
| V2-00 | JARVIS_V2_FULL_AUDIT.md | Audit done |
| V2-01 | JARVIS_V2_01_ARCHITECTURE_HARDENING_REPORT.md | Report present |
| V2-02 | JARVIS_V2_02_SEMANTIC_UNDERSTANDING_REPORT.md | Report present |
| V2-03 | JARVIS_V2_03_HYBRID_AI_ROUTER_REPORT.md | Report present |
| V2-04 | JARVIS_V2_04_LLM_QUALITY_CONTEXT_REPORT.md | Report present |
| V2-05 | JARVIS_V2_05_STRUCTURED_AI_OUTPUT_REPORT.md | Report present |
| V2-06 | JARVIS_V2_06_LATENCY_ENGINEERING_REPORT.md | Report present |
| V2-07 | JARVIS_V2_07_REAL_STREAMING_VOICE_REPORT.md | Report present |
| V2-08 | JARVIS_V2_08_CLOUD_ASR_REPORT.md | **COMPLETE** (stop before V2-09 historically, then V2-09 done) |
| V2-09 | JARVIS_V2_09_CLOUD_TTS_REPORT.md | **V2-09 COMPLETE**, stop rule: V2-10 not started |

Полные тексты — в `copies/md_files/`.

## Ключевой вывод аудита V2-00 (не «идея», а зафиксированный факт)

Архитектурно богатый стек Stage 11–27 **присутствует в коде/тестах/BrainGraph**, но **обычный user path** = skills + DialogueResponder + hybrid LLM, а не полный plan→agent→verify цикл.

## UI после V2-09

В коде приложения применён UI 3.0 redesign (shell, Home/Assistant/Live/Activity/Control Center, ThemeApplier, ActivityFeed, ConversationStore). Исходные пакеты макетов лежат в `copies/Ui интерфейс*`.

## Прочие документы в md_files (копии)

Device test results STAGE9…STAGE19_27, NARA_CLOUD_DEVICE_TEST, COMPANION_STATUS, HANDOFF files, raw `.log`/`.tsv` прогонов, `jarvis_agent_prompt.md`, `JARVIS_AGENT_START.md`, `Jarvis_New_Roadmap.md`.
