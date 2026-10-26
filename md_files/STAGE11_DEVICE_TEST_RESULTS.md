# Stage 11 — Device / build / test results

**Date:** 2026-09-30  
**JAVA_HOME:** `/usr/lib/jvm/java-17-openjdk-amd64`  
**Device:** `dideztgiojzpgapn` (Xiaomi 23117RA68G / emerald_global)  
**Package:** `com.jarvis.assistant`  
**Receiver:** `com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver`  
**Wake pipeline:** not modified

## Build

| Item | Result |
|------|--------|
| `./gradlew :brain:testDebugUnitTest --tests 'com.jarvis.brain.aiprovider.*' :core:testDebugUnitTest :app:assembleDebug` | **SUCCESS** |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Size | 209,807,484 bytes (~200 MB) |
| Mtime | 2026-09-30 17:09 |

## Install / permissions

| Item | Result |
|------|--------|
| `adb devices` | 1 device: `dideztgiojzpgapn` **device** |
| `adb install -r …/app-debug.apk` | **Success** (Streamed Install) |
| `pm grant … RECORD_AUDIO` | **granted** |
| `am start … DashboardActivity` | **OK** |
| Cold start + `DEBUG_VOICE` `action=start` | **OK** — `voice: start requested`; ChatLlm load **3615ms**, warm-up **8284ms**; VoicePipeline owning mic |
| **Stage 11 wiring — planner** | `Jarvis.Planner`: **`aiPlanner=deterministic`** `discovery=on` |
| **Stage 11 wiring — router** | `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=0` **`router=on`** |

## Unit tests (JUnit XML under `*/build/test-results/`)

| Suite | Status | Counts (from XML) |
|-------|--------|-------------------|
| `:brain:testDebugUnitTest` (`com.jarvis.brain.aiprovider.*`) | **PASS** | **22** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `AIProviderTest` | **PASS** | 12 |
| ↳ `AIRouterTest` | **PASS** | 10 |
| `:core:testDebugUnitTest` | **PASS** | **330** tests, **0** failures, **0** errors, **0** skipped |
| **Combined** | **PASS** | **352** / **352** passed |

XML sources: `brain/build/test-results/testDebugUnitTest/TEST-com.jarvis.brain.aiprovider.AIProviderTest.xml`, `TEST-com.jarvis.brain.aiprovider.AIRouterTest.xml`, + 31 core suite XMLs under `core/build/test-results/testDebugUnitTest/`.

## Device commands (debug broadcasts)

Quoting used: `adb shell "am broadcast … --es command \"…\""` so full multi-word Russian phrases were delivered.

After cold start and voice start, sequential `DEBUG_COMMAND` runs waited for `Jarvis.VoiceService` **e2e first-audio** (or clarification + e2e) before the next command. Log excerpt: `/tmp/stage11_device.log`. Runner: `scripts/stage11_device_run.sh`.

| # | Action | Result |
|---|--------|--------|
| 1 | `DEBUG_COMMAND` «включи фонарик» | **OK** — `status=PROCESSED`, reply «Включён.», cap=`DEVICE_LIGHT`/`flashlight` SUCCESS, brain **414ms**, e2e **715ms**, `slo1s=true` |
| 2 | `DEBUG_COMMAND` «выключи фонарик» | **OK** — `status=PROCESSED`, reply «Выключен.», flashlight SUCCESS, brain **37ms**, e2e **401ms**, `slo1s=true` |
| 3 | `DEBUG_COMMAND` «сделай громче» | **OK** — `status=PROCESSED`, reply «Громче.», cap=`DEVICE_AUDIO`/`volume` SUCCESS, brain **162ms**, e2e **393ms**, `slo1s=true` |
| 4 | `DEBUG_COMMAND` «сделай тише» | **OK** — `status=PROCESSED`, reply «Тише.», volume SUCCESS, brain **84ms**, e2e **392ms**, `slo1s=true` |
| 5 | `DEBUG_COMMAND` «какая погода» | **OK** — `status=PROCESSED`, Moscow weather (~16°), cap=`WEATHER_LOOKUP` SUCCESS, brain **972ms**, e2e **4358ms**, `slo1s=false` |
| 6 | `DEBUG_COMMAND` «который час» | **OK** — `status=PROCESSED`, reply «17:11», brain **78ms**, e2e **550ms**, `slo1s=true` |
| 7 | `DEBUG_COMMAND` «сколько времени» | **OK** — `status=PROCESSED`, reply «17:11», brain **160ms**, e2e **666ms**, `slo1s=true` |
| 8 | `DEBUG_COMMAND` «кто ты» | **OK** — `status=PROCESSED`, identity reply, brain **131ms**, e2e **1285ms**, `slo1s=false` |
| 9 | `DEBUG_COMMAND` «расскажи шутку» | **OK** — `status=PROCESSED`, joke reply, brain **55ms**, e2e **1633ms**, `slo1s=false` |
| 10 | `DEBUG_COMMAND` «привет» | **OK** — `status=PROCESSED`, reply «Привет. Чем могу помочь?», brain **54ms**, e2e **616ms**, `slo1s=true` |
| 11 | `DEBUG_COMMAND` «что ты умеешь» | **OK** — `status=PROCESSED`, capabilities summary, brain **82ms**, e2e **1595ms**, `slo1s=false` |
| 12 | `DEBUG_COMMAND` «какой сегодня день» | **OK** — `status=PROCESSED`, reply «Среда, 30 сентября 2026», brain **81ms**, e2e **1092ms**, `slo1s=false` |
| 13 | `DEBUG_COMMAND` «открой настройки» | **OK** — `status=PROCESSED`, reply «Открыл.», cap=`APP_LAUNCH`/`launcher` SUCCESS, brain **8140ms**, e2e **8422ms**, `slo1s=false` |
| 14 | `DEBUG_COMMAND` «поставь таймер на 1 минуту» | **OK** — `status=PROCESSED`, reply «Таймер: 1 мин.», cap=`DEVICE_TIMER`/`timer` SUCCESS, brain **100ms**, e2e **433ms**, `slo1s=true` |
| 15 | `DEBUG_COMMAND` «выключи музыку» | **PARTIAL** — `status=NEEDS_CLARIFICATION`, clarification prompt (no media stop tool), brain **2ms**, e2e **825ms**, `slo1s=true` |
| 16 | `DEBUG_COMMAND` «включи фонарик» (repeat) | **OK** — `status=PROCESSED`, reply «Включён.», flashlight SUCCESS, brain **135ms**, e2e **347ms**, `slo1s=true` |
| 17 | `DEBUG_COMMAND` «выключи фонарик» (repeat) | **OK** — `status=PROCESSED`, reply «Выключен.», flashlight SUCCESS, brain **10ms**, e2e **256ms**, `slo1s=true` |
| 18 | `DEBUG_AUTONOMY` `flashlight_on` `live=false` | **OK** — dry-run `status=READY_TO_EXECUTE`, `verdict=ALLOW`, `tool=flashlight`, notes risk=LOW / SAFE_ONLY |
| 19 | `DEBUG_AUTONOMY` `volume_up` `live=false` | **OK** — dry-run `status=READY_TO_EXECUTE`, `verdict=ALLOW`, `tool=volume`, notes risk=LOW / SAFE_ONLY |

## Logcat tags captured

Filtered / grepped: `Jarvis.Planner`, `Jarvis.AIProvider`, `Jarvis.Capability`, `Jarvis.ChatLlm`, `Jarvis.Debug`, `Jarvis.VoiceService`, `slo1s` (e2e lines).

Notable startup lines:

- `Jarvis.Planner`: **`aiPlanner=deterministic`** `discovery=on`
- `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=0` **`router=on`**
- `Jarvis.Capability`: `registry ready caps=24 tools=24 catalog=24 discovery=on`
- `Jarvis.ChatLlm`: load **3615ms**, warm-up **8284ms** model=`Qwen3-0.6B-Q4_K_M.gguf` profile=QUALITY

## Failures / issues

- **Non-blocking:** 16/17 text commands reached **`PROCESSED`**; **1** («выключи музыку») returned **`NEEDS_CLARIFICATION`** (expected — no music-stop capability).
- **SLO:** Weather, identity, chat-heavy replies, weekday, and settings launch missed 1s e2e (`slo1s=false`) due to brain/TTS/launcher latency — not install/planner/router failures.
- **Settings launch** brain **8140ms** (launcher path) — functional but slow vs device commands.

## Key numbers

- **Brain aiprovider unit tests:** 22 / 22 passed (12 + 10)  
- **Core unit tests:** 330 / 330 passed  
- **Combined unit tests:** 352 / 352 passed  
- **Install:** Success  
- **`aiPlanner=deterministic` on device:** **yes** (`Jarvis.Planner` at VoiceService start)  
- **`router=on` on device:** **yes** (`Jarvis.AIProvider` at VoiceService start)  
- **Device `DEBUG_COMMAND` with `PROCESSED`:** **16 / 17**  
- **Device commands with e2e audio (incl. clarification):** **17 / 17**  
- **Autonomy dry-runs:** **2 / 2** ALLOW / READY_TO_EXECUTE  
- **Flashlight on e2e (1st / repeat):** 715 ms / 347 ms (`slo1s=true`)  
- **Flashlight off e2e (1st / repeat):** 401 ms / 256 ms (`slo1s=true`)  
- **Volume up / down e2e:** 393 ms / 392 ms (`slo1s=true`)  
- **Weather brain / e2e:** 972 ms / 4358 ms (`slo1s=false`)  
- **Timer 1 min e2e:** 433 ms (`slo1s=true`)  
- **Open settings brain / e2e:** 8140 ms / 8422 ms (`slo1s=false`)  

## Summary

| Gate | Result |
|------|--------|
| Build + unit tests (352) | **PASS** |
| Install + voice warm-up + **`aiPlanner=deterministic`** + **`router=on`** | **PASS** |
| Large device command matrix (≥18 steps) | **PASS** (16 PROCESSED + 1 clarification + 2 autonomy dry-runs) |
| **Overall Stage 11 device suite** | **PASS** (music clarification expected; settings latency noted) |
