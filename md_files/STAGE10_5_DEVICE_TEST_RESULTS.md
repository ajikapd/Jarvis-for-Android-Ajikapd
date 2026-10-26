# Stage 10.5 — Device / build / test results

**Date:** 2026-09-30  
**JAVA_HOME:** `/usr/lib/jvm/java-17-openjdk-amd64`  
**Device:** `dideztgiojzpgapn` (Xiaomi 23117RA68G / emerald_global)  
**Package:** `com.jarvis.assistant`  
**Receiver:** `com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver`  
**Wake pipeline:** not modified

## Build

| Item | Result |
|------|--------|
| `./gradlew :brain:testDebugUnitTest --tests 'com.jarvis.brain.aiprovider.*' :core:testDebugUnitTest :app:assembleDebug` | **SUCCESS** (artifacts present; APK mtime 16:56) |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Size | 209,807,425 bytes (~200 MB) |
| Mtime | 2026-09-30 16:56 |

## Install / permissions

| Item | Result |
|------|--------|
| `adb devices` | 1 device: `dideztgiojzpgapn` **device** |
| `adb install -r …/app-debug.apk` | **Success** (Streamed Install) |
| `pm grant … RECORD_AUDIO` | **granted** |
| `am start … DashboardActivity` | **OK** |
| Cold start + `DEBUG_VOICE` `action=start` | **OK** — `voice: start requested`; ChatLlm load **2007ms**, warm-up **1733ms**; VoicePipeline owning mic |
| **Stage 10.5 wiring** | `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=0` **`router=on`** |

## Unit tests (JUnit XML under `*/build/test-results/`)

| Suite | Status | Counts (from XML) |
|-------|--------|-------------------|
| `:brain:testDebugUnitTest` (`com.jarvis.brain.aiprovider.*`) | **PASS** | **22** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `AIProviderTest` | **PASS** | 12 |
| ↳ `AIRouterTest` | **PASS** | 10 |
| `:core:testDebugUnitTest` | **PASS** | **320** tests, **0** failures, **0** errors, **0** skipped |
| **Combined** | **PASS** | **342** / **342** passed |

XML sources: `brain/build/test-results/testDebugUnitTest/TEST-com.jarvis.brain.aiprovider.AIProviderTest.xml`, `TEST-com.jarvis.brain.aiprovider.AIRouterTest.xml`, + 30 core suite XMLs under `core/build/test-results/testDebugUnitTest/`.

## Device commands (debug broadcasts)

Quoting used: `adb shell "am broadcast … --es command \"…\""` so full multi-word Russian phrases were delivered.

After cold start and voice start, sequential `DEBUG_COMMAND` runs waited for `Jarvis.VoiceService` **e2e first-audio** before the next command. Log excerpt: `/tmp/stage10_5_device.log`.

| # | Action | Result |
|---|--------|--------|
| 1 | `DEBUG_COMMAND` «включи фонарик» | **OK** — `status=PROCESSED`, reply «Включён.», cap=`DEVICE_LIGHT`/`flashlight` SUCCESS, brain **289ms**, e2e **529ms**, `slo1s=true` |
| 2 | `DEBUG_COMMAND` «выключи фонарик» | **OK** — `status=PROCESSED`, reply «Выключен.», flashlight SUCCESS, brain **30ms**, e2e **413ms**, `slo1s=true` |
| 3 | `DEBUG_COMMAND` «сделай громче» | **OK** — `status=PROCESSED`, reply «Громче.», cap=`DEVICE_AUDIO`/`volume` SUCCESS, brain **118ms**, e2e **326ms**, `slo1s=true` |
| 4 | `DEBUG_COMMAND` «сделай тише» | **OK** — `status=PROCESSED`, reply «Тише.», volume SUCCESS, brain **32ms**, e2e **226ms**, `slo1s=true` |
| 5 | `DEBUG_COMMAND` «какая погода» | **OK** — `status=PROCESSED`, Moscow weather (~16°), cap=`WEATHER_LOOKUP` SUCCESS, brain **885ms**, e2e **3861ms**, `slo1s=false` |
| 6 | `DEBUG_COMMAND` «который час» | **OK** — `status=PROCESSED`, reply «17:01», brain **103ms**, e2e **494ms**, `slo1s=true` |
| 7 | `DEBUG_COMMAND` «сколько времени» | **OK** — `status=PROCESSED`, reply «17:01», brain **59ms**, e2e **412ms**, `slo1s=true` |
| 8 | `DEBUG_COMMAND` «кто ты» | **OK** — `status=PROCESSED`, identity reply, brain **94ms**, e2e **956ms**, `slo1s=true` |
| 9 | `DEBUG_COMMAND` «расскажи шутку» | **OK** — `status=PROCESSED`, joke reply, brain **57ms**, e2e **1395ms**, `slo1s=false` |
| 10 | `DEBUG_COMMAND` «привет» | **OK** — `status=PROCESSED`, reply «Привет. Чем могу помочь?», brain **57ms**, e2e **583ms**, `slo1s=true` |
| 11 | `DEBUG_COMMAND` «что ты умеешь» | **OK** — `status=PROCESSED`, capabilities summary, brain **49ms**, e2e **1458ms**, `slo1s=false` |
| 12 | `DEBUG_COMMAND` «открой настройки» | **OK** — `status=PROCESSED`, reply «Открыл.», cap=`APP_LAUNCH`/`launcher` SUCCESS, brain **5168ms**, e2e **5531ms**, `slo1s=false` |
| 13 | `DEBUG_COMMAND` «поставь таймер на 1 минуту» | **OK** — `status=PROCESSED`, reply «Таймер: 1 мин.», cap=`DEVICE_TIMER`/`timer` SUCCESS, brain **40ms**, e2e **293ms**, `slo1s=true` |
| 14 | `DEBUG_COMMAND` «какая громкость» | **OK*** — `status=PROCESSED`, reply «Громче.» (query vs action mismatch), volume SUCCESS, brain **85ms**, e2e **258ms**, `slo1s=true` |
| 15 | `DEBUG_COMMAND` «громкость максимум» | **OK** — `status=PROCESSED`, reply «Максимум.», volume SUCCESS, brain **29ms**, e2e **212ms**, `slo1s=true` |
| 16 | `DEBUG_COMMAND` «сделай потише» | **OK** — `status=PROCESSED`, reply «Тише.», volume SUCCESS, brain **32ms**, e2e **217ms**, `slo1s=true` |
| 17 | `DEBUG_COMMAND` «выключи музыку» | **PARTIAL** — `status=NEEDS_CLARIFICATION`, clarification prompt (no media tool), brain **2ms**, e2e **778ms**, `slo1s=true` |
| 18 | `DEBUG_COMMAND` «какая сегодня дата» | **OK*** — `status=PROCESSED`, reply «Сегодня дата — 20 октября.» (wrong vs system date), brain **2355ms**, e2e **2847ms**, `slo1s=false` |
| 19 | `DEBUG_COMMAND` «какой сегодня день» | **OK** — `status=PROCESSED`, reply «Среда, 30 сентября 2026», brain **85ms**, e2e **821ms**, `slo1s=true` |
| 20 | `DEBUG_AUTONOMY` `flashlight_on` `live=false` | **OK** — dry-run `status=READY_TO_EXECUTE`, `verdict=ALLOW`, `tool=flashlight`, notes risk=LOW / SAFE_ONLY |
| 21 | `DEBUG_AUTONOMY` `volume_up` `live=false` | **OK** — dry-run `status=READY_TO_EXECUTE`, `verdict=ALLOW`, `tool=volume`, notes risk=LOW / SAFE_ONLY |

\* Pipeline completed with `PROCESSED` but reply semantics questionable (see Failures).

## Logcat tags captured

Filtered / grepped: `Jarvis.AIProvider`, `Jarvis.Capability`, `Jarvis.ChatLlm`, `Jarvis.Debug`, `Jarvis.VoiceService`, `slo1s` (e2e lines).

Notable startup lines:

- `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=0` **`router=on`**
- `Jarvis.Capability`: `registry ready caps=24 tools=24 catalog=24 discovery=on`
- `Jarvis.ChatLlm`: load **2007ms**, warm-up **1733ms** model=`Qwen3-0.6B-Q4_K_M.gguf` profile=QUALITY

## Failures / issues

- **Non-blocking:** 18/19 text commands reached **`PROCESSED`** with tool/chat replies; **1** («выключи музыку») returned **`NEEDS_CLARIFICATION`** (no music stop).
- **Quality:** «какая громкость» answered as volume **action** («Громче.») instead of level readout.
- **Quality:** «какая сегодня дата» replied **20 октября** while «какой сегодня день» correctly said **30 сентября 2026**.
- **SLO:** Weather, settings launch, long chat, and date query missed 1s e2e (`slo1s=false`) due to brain/TTS latency — not install/router failures.
- **Settings launch** brain **5168ms** (launcher path) — functional but slow.

## Key numbers

- **Brain aiprovider unit tests:** 22 / 22 passed (12 + 10)  
- **Core unit tests:** 320 / 320 passed  
- **Combined unit tests:** 342 / 342 passed  
- **Install:** Success  
- **`router=on` on device:** **yes** (log line at VoiceService start)  
- **Device `DEBUG_COMMAND` with `PROCESSED`:** **18 / 19**  
- **Device commands with e2e audio (incl. clarification):** **19 / 19**  
- **Autonomy dry-runs:** **2 / 2** ALLOW / READY_TO_EXECUTE  
- **Flashlight on e2e:** 529 ms (`slo1s=true`)  
- **Flashlight off e2e:** 413 ms (`slo1s=true`)  
- **Volume up / down e2e:** 326 ms / 226 ms (`slo1s=true`)  
- **Weather brain / e2e:** 885 ms / 3861 ms (`slo1s=false`)  
- **Timer 1 min e2e:** 293 ms (`slo1s=true`)  
- **Open settings brain / e2e:** 5168 ms / 5531 ms (`slo1s=false`)  

## Summary

| Gate | Result |
|------|--------|
| Build + unit tests (342) | **PASS** |
| Install + voice warm-up + **`router=on`** | **PASS** |
| Large device command matrix | **PASS** (18 PROCESSED + 1 clarification + 2 autonomy dry-runs) |
| **Overall Stage 10.5 device suite** | **PASS** (with noted music/date/volume-query caveats) |
