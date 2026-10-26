# Jarvis — handoff для следующего агента (статус на 2026-09-18)

Документ для передачи другому ИИ (Claude и т.п.): что уже сделано, где лежит код, что ещё дырявое, и куда логично идти дальше.  
Бюджет проекта: **строго 0 ₽**, только OSS / бесплатные API без ключей.  
Целевое устройство: **Xiaomi 23117RA68G (Helio G99 / MT6789), HyperOS 3, Android 16** — см. [`DEVICE_SPECS.md`](../DEVICE_SPECS.md).

Опорные доки (не дублировать целиком):
- [`jarvis-agent-prompt.md`](../jarvis-agent-prompt.md) — этапы 1–5 (обязательный DoD промпта)
- [`jarvis-full-roadmap.md`](../jarvis-full-roadmap.md) — **активная дорожная карта** (фазы 0–7 после A–E)
- [`md_files/JARVIS_MASTER_ROADMAP_v2.md`](JARVIS_MASTER_ROADMAP_v2.md) — длинная визия (OCR, автоматизации, виджеты…)
- [`SMOKE_RESULTS.md`](../SMOKE_RESULTS.md) — фиксация Phase 0 device smoke
- Этот файл — **фактическое состояние кода**

---

## 0d. Companion foundation (2026-09-21)

| Пункт | Статус |
|-------|--------|
| Audit vs Phone Companion vision | **done** |
| `PhoneObservation` / `PhoneContext` / `PhoneContextBus` | **есть** |
| `SystemStateObserver` (screen/battery/charger/net) | **есть** |
| A11y → `APP_FOREGROUND` (observe only) | **есть** |
| Settings → tab **Companion** diagnostics | **есть** |
| VoiceInteraction / UsageStats / Learning / Autonomy | **не начато** |

См. [`md_files/COMPANION_STATUS.md`](COMPANION_STATUS.md).

---

## 0. Активная фаза: Chat LLM + Models tab

| Пункт | Статус |
|-------|--------|
| Phase 0 | **закрыт** |
| Phase 1 music-engine | **отменена** |
| Phase 2 follow-up | **закрыт** |
| Phase 3 OCR | **paused** (код ScreenOcr есть) |
| `ChatLlmEngine` + Qwen 1.5B GGUF (llama.cpp AAR) | **есть** |
| `DialogueResponder` → templates → ChatLlm → hint | **есть** |
| Settings вкладка **Модели** (download Chat/Tool/TTS/ASR) | **есть** |
| `ModelManager` → `files/models/` | **есть** |

См. [`SMOKE_RESULTS.md`](../SMOKE_RESULTS.md).

---

## 0b. Phase 2 (диалог follow-up) — закрыта

| Пункт | Статус |
|-------|--------|
| `ConversationContext.lastToolCall` | **есть** |
| `FollowUpResolver` | **есть** |
| Weather `day` 0/1/2 | **есть** |
| DoD Phase 2 device smoke | **PASS** |

---

## 0c. Предыдущая: Phase 0 (Hardening)

| Пункт | Статус |
|-------|--------|
| Smoke A first-audio ≤1с (10 фраз) | **PASS 10/10** |
| Compound + ReAct hygiene | **PASS** |
| Telegram ×5 → Избранное (через поиск) | **PASS 5/5** |
| Telegram send by contact + склонения | **PASS** |
| ASR chunk glue (endpoint 1.6s + incomplete) | **PASS** |
| Кион transport pause/next/resume | **PASS** |
| Кион play / избранное (MediaBrowser) | **FAIL/PARTIAL** (внутренний плеер отменён; остаётся Кион/session) |
| ui.wait / minimize / force_stop / SMS / calls / calendar | **PASS** |
| **DoD Phase 0** | **практически закрыт** |

---

## 1. Что это за продукт

Офлайн-first голосовой ассистент на Android:
- wake → ASR (RU+EN) → Brain (rules-first + FunctionGemma) → tools / UI (a11y) → TTS
- сеть только для погоды / знаний / музыки-статуса по необходимости
- «руки» через Accessibility + NotificationListener + системные Intent/API

---

## 2. Модули Gradle

| Модуль | Назначение |
|--------|------------|
| `:app` | UI, `VoiceService`, `VoiceEngine`, `JarvisSpeaker`, `BrainGraph`, онбординг |
| `:core` | модели Intent/Tool, policy, registry, ConversationContext |
| `:brain` | RuleBasedBrain, LlmBrain, AssistantOrchestrator, DialogueResponder, memory |
| `:tools` | все Tool-реализации (фонарик, музыка, SMS, UI…) |
| `:screen-control` | AccessibilityService, ScreenController, NotificationListener |
| `:asr-engine` | Sherpa RU, BufferedEn, NeMo refine, CommandTextSelector |
| `:tts-engine` | офлайн TTS |
| `:audio-engine` | захват / VAD |
| `:wakeword-engine` | wake «джарвис» |
| `:overlay-ui`, `:background-service`, `:connectivity-router`, `:permissions-onboarding` | вспомогательные |

Сборка: `./gradlew :app:assembleDebug` (JDK 17, Gradle wrapper).  
Пакет: `com.jarvis.assistant`.

---

## 3. Статус этапов промпта

| Этап | Сейчас (код) | Комментарий |
|------|--------------|-------------|
| 1 Hands | **~92%** | + ui.wait, minimize/force_stop; Telegram-like ждёт device smoke |
| 2 APIs | **~90%** | музыка-пульт + headless knowledge |
| 3 Brain | **~75%** | ReAct + history; chat ещё шаблоны |
| 4 Speed | **~75%** | логи slo1s; цифры PENDING |
| 5 Icon | **Done** | adaptive mipmap |

---

## 4. Что сделано по фазам A–E + Phase 0

### Фаза A — музыка (Stage 2)
- [`MusicTool.kt`](../tools/src/main/kotlin/com/jarvis/tools/MusicTool.kt): Kion → MediaSession → `PLAY_FROM_SEARCH`, TTL статуса

### Фаза B — latency
- skip refine без латиницы; phrase TTS; `tts first-audio` / `slo1s` логи

### Фаза C — агент
- LLM history+memory; ReAct ≤3; rules paraphrase

### Фаза D — Hands residual
- call history / SMS read / calendar list / notification reply

### Фаза E — polish
- DialogueResponder history heuristics; KnowledgeTool headless (Jina)

### Phase 0 (full-roadmap) — hardening
- [`UiWaitTool`](../tools/src/main/kotlin/com/jarvis/tools/UiTools.kt) id=`ui.wait`
- [`ActionCoordinator.waitForNode`](../screen-control/src/main/kotlin/com/jarvis/screencontrol/ActionCoordinator.kt) — backoff; click/type/launch ждут дерево
- [`LauncherTool`](../tools/src/main/kotlin/com/jarvis/tools/LauncherTool.kt): `minimize`/`close` = Home; `force_stop` = killBackgroundProcesses + app details + **CONFIRM**
- skills: `ui_wait.json`, обновлён `launch_app.json`
- smoke: [`scripts/smoke_phase0.sh`](../scripts/smoke_phase0.sh), [`SMOKE_RESULTS.md`](../SMOKE_RESULTS.md)

---

## 5. Пайплайн голоса

```
mic → VAD / wake → Sherpa RU (+ optional NeMo/EN refine)
  → BrainGraph → rules / FunctionGemma → tools (+ ReAct ≤3)
  → JarvisSpeaker (chunked TTS)
```

---

## 6. Tools (`BrainGraph`)

flashlight, volume, brightness, timer, calendar, sms, launcher, phone.call, contacts.search, files.search, system.setting, notifications.list, clipboard, screenshot, ui.click / ui.type / ui.swipe / ui.global / ui.read / **ui.wait**, weather, music, knowledge.

---

## 7. Известные дыры

1. Telegram-like — смягчено wait; **нужен smoke ×5**
2. Закрыть приложение — **разведено** minimize / force_stop
3. SAF — слабый
4. PLAY_FROM_SEARCH может мигать UI — smoke B
5. Chat — шаблоны
6. ReAct качество — smoke C
7. SLO 1с — **не доказан цифрами** (PENDING)
8–10. Spotify OAuth / embeddings / OCR — фазы 1–3 full-roadmap

---

## 8. Порядок дальше (строго)

0. **Закрыть Phase 0 DoD** на устройстве (`SMOKE_RESULTS.md`)  
1. Свой `:music-engine` (ExoPlayer + MediaStore + Jamendo…)  
2. Dialogue path / last SkillCall / embeddings v0  
3. OCR fallback  
4. Notes/tasks/automations  
5. Widgets/export/debug UI  
6. Perf bake-off + low_power + tests  
7. Watchdog / health-check  

Не начинать Phase 1, пока Phase 0 не зелёный.

---

## 9. Smoke на устройстве

```bash
./gradlew :app:installDebug
./scripts/smoke_phase0.sh
adb logcat -s VoiceService JarvisSpeaker MusicTool
# фразы из SMOKE_RESULTS.md → заполнить PASS/FAIL и ms
```

Онбординг: a11y + notification listener + battery unrestricted.
