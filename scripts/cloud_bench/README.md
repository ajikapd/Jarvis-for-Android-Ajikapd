# Cloud LLM bench (Jarvis)

Скрипт замеряет облачные LLM (Gemini / Groq) на синтетическом наборе из 30 промптов.
Только Python 3 **стандартная библиотека** (без `pip install`).

Код приложения Jarvis **не** трогает. Ключи API принадлежат вам: скрипт читает их только из переменных окружения и **никогда** не печатает значения ключей и заголовки `Authorization` / `x-goog-api-key`.

## Требования

- `python3` 3.8+
- Переменные:
  - `GEMINI_API_KEY` — для моделей `provider=gemini`
  - `GROQ_API_KEY` — для моделей `provider=groq`
  - `MISTRAL_API_KEY` — необязателен (зарезервирован)

## Как запустить (ключ не в history)

Пробел перед `export` мешает bash записать строку в `~/.bash_history` (при обычных настройках):

```bash
 cd /mnt/D/Jarvis_for_android/scripts/cloud_bench
 export GEMINI_API_KEY='…ваш ключ…'
 export GROQ_API_KEY='…ваш ключ…'
 python3 bench.py --yes
```

Полезные флаги:

| Флаг | Смысл |
|------|--------|
| `--dry-run` | Только план и оценка токенов, без HTTP |
| `--yes` | Не спрашивать подтверждение |
| `--models gemini-3.5-flash,openai/gpt-oss-20b` | Фильтр моделей |
| `--variants default,minimal-thinking` | Какие варианты reasoning |
| `--limit-prompts 5` | Первые N промптов |
| `--out DIR` | Корень для `results/<timestamp>/` |
| `--pause 4` | Пауза между запросами (сек), по умолчанию 4 |

Список моделей — в `models.json` (можно править без правки кода). Промпты — `prompts.json`.

### Пример dry-run

```bash
python3 bench.py --dry-run --limit-prompts 3 --models gemini-3.5-flash-lite
```

### Тесты

```bash
cd /mnt/D/Jarvis_for_android/scripts/cloud_bench
python3 -m unittest discover -s tests -v
```

## Ожидаемое время и токены

Полный прогон: **9 моделей × 2 варианта × 30 промптов ≈ 540 запросов**.  
При паузе 4 с + латентность сети это порядка **~1–2+ часов** (плюс ретраи 429/503).

Оценка токенов перед стартом **консервативная**: для `default` умножается на **×3** (размышления), для `minimal-thinking` — ×1. Это не счётчик биллинга.

Сузить прогон:

```bash
python3 bench.py --yes --models openai/gpt-oss-20b --limit-prompts 10 --variants default
```

## Результаты

В `results/<UTC-timestamp>/`:

| Файл | Содержимое |
|------|------------|
| `results.jsonl` | Одна JSON-строка на запрос (метрики + текст ответа) |
| `summary.md` | Таблица success / TTFT / total / токены / auto-score / 429·503 |
| `short_report.txt` | Краткая сводка **без** полных ответов и **без** ключей |

Каталог `results/` в `.gitignore`.

## Что прислать Claude

1. `short_report.txt`
2. `results.jsonl` (если нужны тексты ответов для ручной оценки)

Не присылайте значения API-ключей и не вставляйте сырой вывод с `Authorization: Bearer …`.

## Режимы рассуждения

Для каждой модели: `default` (без спец-параметров) и `minimal-thinking`.

Скрипт **пробирует** параметры (HTTP 400 = не поддерживается) и пишет в `summary.md`, какой вариант принят:

- **Gemini** (OpenAI-совместимый `…/v1beta/openai/chat/completions`, fallback `streamGenerateContent`): по очереди `reasoning_effort=none|minimal|low`, затем `extra_body.google.thinking_config`, нативно `thinkingBudget=0` / `thinkingLevel`.
- **Groq gpt-oss-***: `reasoning_effort=low` (+ `include_reasoning=false`).
- **Groq qwen/***: `reasoning_effort=none`.

Если ничего не принято → `variant=unsupported`.

Источники: [Gemini thinking](https://ai.google.dev/gemini-api/docs/thinking), [Gemini OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai), [Groq Reasoning](https://console.groq.com/docs/reasoning).

## Метрики

- **TTFT** — время до первого токена **видимого** текста (не reasoning/thought).
- `total_ms`, `in_tokens`, `out_tokens`, `reasoning_tokens` (из `usage` / `usageMetadata`, если есть).
- Таймаут запроса: 60 с. При 429/503 — до 2 повторов с экспоненциальной паузой; каждая попытка пишется в `attempts`.
