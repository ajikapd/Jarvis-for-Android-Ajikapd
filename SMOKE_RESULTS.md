# SMOKE_RESULTS — Phase 0

Дата: 2026-09-18 (вечерний прогон: Кион + Telegram «Избранное»)  
Device: **23117RA68G**  
Плеер: **Кион** (`ru.mts.music.android`)  
Чат: Telegram **Избранное** (`org.telegram.messenger`)

---

## Сводка

| Блок | Статус | Итог |
|------|--------|------|
| A. Latency first-audio ≤1с | **PASS 10/10** | (ранее) |
| B. Музыка Кион | **PARTIAL** | pause/resume/next **PASS**; play/favorites через MediaBrowser **слаб** (тишина / ERR) |
| C. Compound | **PASS** | |
| D. Telegram-like ×5 → Избранное | **PASS 5/5** | путь: поиск → «избранное» → клик по чату (не EditText) → ввод в сообщение |
| E. «напиши &lt;контакт&gt; в телеграмме» | **PASS** | контакт из фразы + склонения (жене→жена); «Что написать?» → отправка; клик по 1-му результату поиска |
| F. ASR incomplete-hold | **PASS** | endpoint 1.6с; «напиши любимой жене» ждёт «в телеграмме» |
| G. Phase 1 own music | **PASS** | `включи piano` → свой ExoPlayer «SoundHelix Song 1» (Jamendo test client_id suspended → open-catalog) |
| E. ui.wait / minimize / force_stop / SMS / calls | **PASS** | |

**DoD Phase 0:** по Hands/Telegram и latency — **закрыт**. Музыка: transport OK, поиск трека в Кионе — дыра (кандидат в Phase 1 / доработка Kion adapter).

---

## B. Кион

| # | Фраза | Результат | PASS |
|---|-------|-----------|:----:|
| 1 | включи песню queen bohemian rhapsody | Kion MediaBrowser weak → generic intent «Включаю…»; статус пустой | PARTIAL |
| 2 | включи избранное | ERR «утекай»/тишина → FAILED | FAIL |
| 3 | что играет | «на паузе: .» / пусто | PARTIAL |
| 4 | пауза / продолжай / следующий | `Пауза.` / `Играю.` / `Дальше.` | **PASS** |

---

## D. Telegram ×5 (Избранное)

Команда (стабильный путь):
`открой telegram, нажми поиск, введи избранное, нажми избранное, напиши тест джарвис N`

| Прогон | Результат | PASS |
|-------:|-----------|:----:|
| 1 | Открыл → Поиск → ввод → Избранное → ввод текста | **PASS** |
| 2 | то же | **PASS** |
| 3 | то же | **PASS** |
| 4 | то же | **PASS** |
| 5 | то же | **PASS** |

**Success rate: 5 / 5**

Фиксы, без которых падало:
- compound split **до** `AsrPhraseNormalizer` (иначе запятые съедаются → ложный SMS)
- SMS не перехватывает фразы с `открой`/`telegram`/`найди`
- ReAct не крутится на голом `ui.click`
- синонимы «Избранное» / Saved Messages; soft-continue click→type

Прямой «найди избранное» в списке чатов нестабилен (дерево + «Соединение…»).

---

## Phase 2 — диалог / follow-up (2026-09-18)

| Шаг | Результат |
|-----|-----------|
| «какая погода в Москве» | **PASS** — текущая погода (~1.6с, сеть) |
| «а послезавтра?» | **PASS** — day=2, город из `lastToolCall`, brain **7ms** |
| «ещё раз» | **PASS** — повтор последнего SkillCall |

**DoD Phase 2: PASS** (без тяжёлой LLM на follow-up).

---

## Следующий шаг по full-roadmap

**Phase 1 cancelled:** `:music-engine` удалён. `MusicTool` — Кион / MediaSession.

## Chat LLM + Models tab (2026-09-21)

| Шаг | Результат |
|-----|-----------|
| «что думаешь про кошек» без GGUF | **PASS** — hint «Настройки → Модели → Диалог · Qwen» |
| «включи фонарик» | **PASS** — rules/tool, без chat LLM |
| Settings → Модели | карточки Chat/Tool/TTS/ASR + download в `files/models/` |

Полный chat smoke (Qwen inference): скачать «Диалог · Qwen» (~1.1 GB) на устройстве или `adb push` в  
`…/files/models/llm/chat/qwen2.5-1.5b-instruct-q4_k_m.gguf`.

---

## Следующий шаг по full-roadmap

**Phase 2 closed.**  
**Chat LLM + Models tab shipped** (download UI + DialogueResponder wiring).  
**Phase 3 OCR** — код есть, device DoD ещё нужен.  
Дальше: device smoke после скачивания Qwen; затем OCR DoD или Phase 4.

