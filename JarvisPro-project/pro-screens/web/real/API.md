# Мост данных телефона для экранов Pro (для агентов)

Файлы `app/web/real/*.js` склеиваются по имени и подключаются в конце `<body>` каждой страницы (после скрипта страницы). Работают только в настоящем приложении: в демо `PRO.on === false` и экраны остаются как есть.

## Общая часть `00-core.js` (готова, не менять)
- `PRO.on`: признак настоящего приложения. Начинай каждый файл с `(function(){ if(!window.PRO||!PRO.on) return; … })();`
- `PRO.bind(route, fn(phoneEl))`: fn вызывается при каждом показе экрана (маршрут из `ROUTES` в app.js) и после выдачи разрешения. `phoneEl` — `.phone` этого экрана.
- `PRO.q(kind, args)` → Promise(JSON).
- `PRO.need(el, j)`: если `j.need`, рисует в `el` плашку «Нужен доступ… [Разрешить]» и возвращает true.
- `PRO.send(text)`: отправить Джарвису текстовую команду, как голосом. Он выполнит её своими инструментами: позвонить, SMS, Telegram, напоминание, такси через агента и т.д.
- `PRO.esc`, `PRO.time(ms)` → «09:40», `PRO.day(ms)` → «Сегодня / Завтра / 3 дек», `PRO.ago(ms)`, `PRO.money(n)`, `PRO.refresh()`.
- Классы `.real-need` и `.real-empty` (пусто).
- Скрипт страницы объявляет top-level `const`/`let`/функции (например `BR`, `bDraw`, `PP`, `pDraw`). Они видны из нашего файла по имени, их можно менять и вызывать.

## Запросы `PRO.q(kind, args)`
| kind | args | ответ |
|---|---|---|
| status | — | `{battery, charging, notifAccess, dndAccess, perms:{calendar,contacts,calllog,sms,bluetooth,location}}` |
| reminders | — | `{items:[{id, at(ms), text, daily}]}` |
| reminderAdd | `{text, at(ms), daily}` | `{ok, id}` |
| reminderDel | `{id}` | `{ok, removed}` |
| calendar | `{days}` | `{items:[{title, begin, end, loc, allDay}]}` или `{need:'calendar'}` |
| notes | — | `{lists:{покупки:[…], дела:[…], заметки:[…]}}` |
| noteAdd / noteDel | `{list, item}` | `{ok}` |
| notifs | — | `{items:[{key, pkg, app, title, text, time, canReply, actions:[названия]}]}` или `{need:'notifications'}` |
| notifReply | `{key, text}` | `{ok, sent}` |
| notifDismiss | `{key}` | `{ok, done}` |
| notifAction | `{key, i}` | `{ok, done}` |
| contacts | — | `{items:[{id, name, phone, starred, birthday?('--MM-DD' или 'YYYY-MM-DD'), lastCall?{at,dur,type}}], callLog}` или `{need:'contacts'}` |
| routines | — | `{items:[{name, steps:[фразы]}]}` («Мои команды»: своя фраза → цепочка) |
| routineAdd | `{name, steps:[…]}` | `{ok}` |
| routineDel | `{name}` | `{ok}` |
| memory | — | `{items:[{id, text, kind, at}]}` |
| memoryDel | `{id}` | `{ok}` |
| modes | — | `{dnd, dndAccess, ringer(0 тихо,1 вибро,2 звук), mode, writeSettings}` |
| modeSet | `{mode: normal\|sleep\|work\|drive\|focus}` | `{ok, mode}` или `{need:'dnd'}` (сам откроет настройки) |
| voices | — | `{items:[{id, name, about, on, ready, download}]}` |
| voiceSet | `{id}` | `{ok, msg}` |
| bt | — | `{items:[{name, addr, kind: watch\|car\|audio\|other, connected}], on}` или `{need:'bluetooth'}` |
| spend | `{days}` | `{items:[{bank, sum, income, where, at}]}` (из SMS банков) или `{need:'sms'}` |
| tool | `{id, args}` | `{status: SUCCESS\|…, msg}` — только инструменты на чтение: `weather`, `briefing`, `web.search {query}`, `rates`, `news`, `system.status` (остальные → `PRO.send`, там подтверждение) |
| openApp | `{pkg:'a,b', url}` | открыть приложение (первое установленное) или ссылку |
| dial | `{phone}` | набор номера |
| smsTo | `{phone, text}` | окно SMS с текстом |
| web | `{url}` | открыть ссылку |

Открыть настоящий раздел Джарвиса: `JarvisNative.open('section:Мои команды')`, `open('screen:memory'|'screen:skills'|'screen:routines')`, `open('live')` (Live-разговор, во время него появляется шар поверх приложений).

## Правила
- Демо-данные на экране заменить настоящими. Если данных нет, показать честное `.real-empty` с подсказкой, как добавить (голосом: «Джарвис, напомни…»). Выдуманные данные не показывать.
- Кнопки делают настоящее действие через PRO.q / PRO.send / JarvisNative.open.
- Дизайн экрана сохранить: те же классы и разметка. Цвета только через CSS-переменные (`var(--gold)`, `var(--ok)`, `var(--text2)`) или как в разметке страницы. Тема перекрашивается сборщиком.
- Нарисованную строку состояния не трогать (она скрыта), заглушки видео не добавлять.
- Проверка: страницы лежат в `D:/apk/Jarvis Pro/app/assets/www/` (`s17.html#brief` и т.д.). В Playwright перед загрузкой подставь заглушку `window.JarvisNative` (`real()` → true, `q(id,kind,args)` отвечает через `setTimeout(() => proData(id, {...}))`, остальные методы пустые, как в `qa/check_screens.js`). После загрузки добавь `addScriptTag` с `real/00-core.js` и своим файлом, затем вызови `document.dispatchEvent(new CustomEvent('proshow',{detail:{r:'brief', el:document.querySelector('#brief')}}))`. Проверь пустые данные, нет разрешения, нормальные данные, отсутствие ошибок JS и вид на снимке.
- `make_app.py` не запускать и чужие файлы не трогать, только свой `real/NN-*.js`.
