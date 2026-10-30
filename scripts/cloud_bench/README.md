# cloud_bench: замер облачных LLM для Jarvis

Скрипт на стандартной библиотеке Python (pip не нужен). Ключи берутся ТОЛЬКО из переменных
окружения `GEMINI_API_KEY` и `GROQ_API_KEY`; они не печатаются и не пишутся в файлы.

## Запуск

```bash
cd /mnt/D/Jarvis_for_android/scripts/cloud_bench
python3 -m unittest discover -s tests -t .          # тесты на локальной заглушке, интернет не нужен
python3 bench.py --providers groq --dry-run         # покажет план и оценку токенов, ничего не отправит

 export GROQ_API_KEY='...'                          # пробел в начале: команда не попадёт в историю
python3 bench.py --preflight --providers groq       # быстрая проверка доступа (по 1-3 запроса на модель)
python3 bench.py --providers groq --yes             # полный прогон

 export GEMINI_API_KEY='...'
python3 bench.py --providers gemini --yes

python3 bench.py --merge results/<каталог_groq> results/<каталог_gemini>   # общий отчёт
```

В конце смотри `short_report.txt` (его можно целиком копировать в чат) и `results.jsonl`.

## Что измеряется

Для каждой модели два варианта: `default` и `min` (минимальные размышления). Параметры варианта
`min` подбираются автоматически из `min_candidates` в `models.json`: берётся первый, который сервер
принял (HTTP 200); 400 = не поддерживается, пробуем следующий. Если ничего не подошло, прогоняется
только `default`.

- TTFT 1-й чанк: до первого SSE-события; TTFT текст: до первого ВИДИМОГО текста (без размышлений);
- число чанков (если всё приходит одним куском, помечается `NO-STREAM`);
- токены in / out / reasoning / total из usage (`n/a`, если провайдер не отдал);
- `leak%`: доля ответов, где размышления протекли в текст тегами `<thought>`/`<think>` (они вырезаются);
- автооценка: memory, command (JSON с нужным tool), plan (JSON из 1-3 шагов с известными tool).

## Защита от лишних трат

- перед прогоном `--preflight` (отключается `--no-preflight`): упавшая модель пропускается (`--force`
  чтобы всё равно запустить); ошибки классифицируются: `blocked_by_cloudflare_user_agent_or_region`,
  `auth_or_region`, `rate_limited` / `rate_limited_daily`, `overloaded`, `network_error`, `missing_key`;
- суточный лимит (`PerDay`/`daily`/`TPD`) отключает модель до конца прогона (`daily_quota_exhausted`);
- три сбоя подряд отключают модель (`circuit_open`);
- 429/503/сеть: до 2 повторов, ждём `Retry-After` или экспоненциальную паузу;
- модели `heavy` (лимит 20 запросов в сутки) и `experimental` по умолчанию выключены:
  `--include-heavy`, `--include-experimental` или назвать модель явно через `--models`.

## Если Groq отвечает 403 `error code: 1010`

Это Cloudflare перед Groq. Скрипт уже шлёт собственный User-Agent (не `Python-urllib`). Если всё
равно 1010, задай другой: `export BENCH_USER_AGENT='Mozilla/5.0 ...'`. Если `Connection refused` или
timeout, проверь прокси/VPN/сеть (urllib, как и curl, использует переменные `http_proxy`/`https_proxy`).

## Полезные флаги

`--models gpt-oss-20b` (id или часть названия), `--variants default`, `--limit-prompts 5`,
`--pause 3` (секунды между запросами к одной модели), `--workers 1` (по одной модели за раз),
`--retries 2`, `--timeout 60`.
