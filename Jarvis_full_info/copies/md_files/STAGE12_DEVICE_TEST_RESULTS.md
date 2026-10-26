# Stage 12 — Device / build / test results

**Date:** 2026-09-30  
**JAVA_HOME:** `/usr/lib/jvm/java-17-openjdk-amd64`  
**Device:** `dideztgiojzpgapn` (Xiaomi 23117RA68G / emerald_global)  
**Package:** `com.jarvis.assistant`  
**Receiver:** `com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver`  
**Wake pipeline:** not modified

## Build

| Item | Result |
|------|--------|
| `./gradlew :core:testDebugUnitTest :brain:testDebugUnitTest --tests 'com.jarvis.brain.aiprovider.*' :app:assembleDebug` | **SUCCESS** |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Size | 209,807,543 bytes (~200 MB) |
| Mtime | 2026-09-30 17:21 |

## Install / permissions

| Item | Result |
|------|--------|
| `adb devices` | 1 device: `dideztgiojzpgapn` **device** |
| `adb install -r …/app-debug.apk` | **Success** (Streamed Install) |
| `pm grant … RECORD_AUDIO` | **granted** |
| `am start … DashboardActivity` | **OK** |
| Cold start + `DEBUG_VOICE` `action=start` | **OK** — `voice: start requested`; ChatLlm warm-up **1757ms**, ASR loaded **5233ms**; KWS listening |
| **Stage 12 wiring — planner** | `Jarvis.Planner`: **`aiPlanner=deterministic`** `discovery=on` **`agentLoop=on`** |
| **Stage 12 wiring — router** | `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=1` **`router=on`** |

## Unit tests (JUnit XML under `*/build/test-results/`)

| Suite | Status | Counts (from XML) |
|-------|--------|-------------------|
| `:brain:testDebugUnitTest` (`com.jarvis.brain.aiprovider.*`) | **PASS** | **22** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `AIProviderTest` | **PASS** | 12 |
| ↳ `AIRouterTest` | **PASS** | 10 |
| `:core:testDebugUnitTest` | **PASS** | **330** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `AgentExecutionLoopTest` | **PASS** | **9** tests, **0** failures |
| ↳ `DeterministicAIPlannerTest` | **PASS** | **10** tests, **0** failures |
| **Combined** | **PASS** | **352** / **352** passed |

XML sources: `brain/build/test-results/testDebugUnitTest/TEST-com.jarvis.brain.aiprovider.*.xml`, `core/build/test-results/testDebugUnitTest/TEST-com.jarvis.core.agent.AgentExecutionLoopTest.xml`, `TEST-com.jarvis.core.planner.DeterministicAIPlannerTest.xml`, + 31 other core suite XMLs under `core/build/test-results/testDebugUnitTest/`.

## Device commands (debug broadcasts)

Quoting used: `adb shell "am broadcast … --es command \"…\""` so full multi-word Russian phrases were delivered.

After cold start and voice start, sequential `DEBUG_COMMAND` runs waited for `Jarvis.VoiceService` **e2e first-audio** (or clarification + e2e) before the next command. Log excerpt: `/tmp/stage12_device.log`. Startup log: `/tmp/stage12_startup.log`. Runner: `scripts/stage12_device_run.sh`.

| # | Action | Result |
|---|--------|--------|
| 1 | `DEBUG_COMMAND` «включи фонарик» | **OK** — `status=PROCESSED`, reply «Включён.», cap=`DEVICE_LIGHT`/`flashlight` SUCCESS, brain **21ms**, e2e **1095ms**, `slo1s=false` (prior voice turn still draining in log; inject result correct) |
| 2 | `DEBUG_COMMAND` «выключи фонарик» | **OK** — `status=PROCESSED`, reply «Выключен.», flashlight SUCCESS, brain **26ms**, e2e **338ms**, `slo1s=true` |
| 3 | `DEBUG_COMMAND` «громче» | **OK** — `status=PROCESSED`, reply «Громче.», cap=`DEVICE_AUDIO`/`volume` SUCCESS, brain **78ms**, e2e **301ms**, `slo1s=true` |
| 4 | `DEBUG_COMMAND` «тише» | **OK** — `status=PROCESSED`, reply «Тише.», volume SUCCESS, brain **28ms**, e2e **280ms**, `slo1s=true` |
| 5 | `DEBUG_COMMAND` «погода» | **OK** — `status=PROCESSED`, Moscow weather (~16°), cap=`WEATHER_LOOKUP` SUCCESS, brain **2944ms**, e2e **5618ms**, `slo1s=false` |
| 6 | `DEBUG_COMMAND` «который час» | **OK** — `status=PROCESSED`, reply «17:26», brain **74ms**, e2e **512ms**, `slo1s=true` |
| 7 | `DEBUG_COMMAND` «сколько времени» | **OK** — `status=PROCESSED`, reply «17:26», brain **66ms**, e2e **521ms**, `slo1s=true` |
| 8 | `DEBUG_COMMAND` «кто ты» | **OK** — `status=PROCESSED`, identity reply, brain **92ms**, e2e **953ms**, `slo1s=true` |
| 9 | `DEBUG_COMMAND` «шутка» | **OK** — `status=PROCESSED`, joke reply (LLM path), brain **7862ms**, e2e **8376ms**, `slo1s=false` |
| 10 | `DEBUG_COMMAND` «привет» | **OK** — `status=PROCESSED`, reply «Привет. Чем могу помочь?», brain **44ms**, e2e **507ms**, `slo1s=true` |
| 11 | `DEBUG_COMMAND` «что ты умеешь» | **OK** — `status=PROCESSED`, capabilities summary, brain **70ms**, e2e **1510ms**, `slo1s=false` |
| 12 | `DEBUG_COMMAND` «какой сегодня день» | **OK** — `status=PROCESSED`, reply «Среда, 30 сентября 2026», brain **77ms**, e2e **920ms**, `slo1s=true` |
| 13 | `DEBUG_COMMAND` «открой настройки» | **OK** — `status=PROCESSED`, reply «Открыл.», cap=`APP_LAUNCH`/`launcher` SUCCESS, brain **5563ms**, e2e **5922ms**, `slo1s=false` |
| 14 | `DEBUG_COMMAND` «выключи музыку» | **PARTIAL** — `status=NEEDS_CLARIFICATION`, clarification prompt (no media stop tool), brain **2ms**, e2e **830ms**, `slo1s=true` |
| 15 | `DEBUG_COMMAND` «включи фонарик» (repeat) | **OK** — `status=PROCESSED`, reply «Включён.», flashlight SUCCESS, brain **74ms**, e2e **280ms**, `slo1s=true` |
| 16 | `DEBUG_AUTONOMY` `flashlight_on` `live=false` | **OK** — dry-run `status=READY_TO_EXECUTE`, `verdict=ALLOW`, `tool=flashlight`, notes risk=LOW / SAFE_ONLY |
| 17 | `DEBUG_AUTONOMY` `volume_up` `live=false` | **OK** — dry-run `status=READY_TO_EXECUTE`, `verdict=ALLOW`, `tool=volume`, notes risk=LOW / SAFE_ONLY |

## Logcat tags captured

Filtered / grepped: `Jarvis.Planner`, `Jarvis.AIProvider`, `Jarvis.Capability`, `Jarvis.ChatLlm`, `Jarvis.Debug`, `Jarvis.VoiceService`, `slo1s` (e2e lines).

Notable startup lines:

- `Jarvis.Planner`: **`aiPlanner=deterministic`** `discovery=on` **`agentLoop=on`**
- `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=1` **`router=on`**
- `Jarvis.ChatLlm`: warm-up **1757ms** model=`Qwen3-0.6B-Q4_K_M.gguf` profile=QUALITY

## Failures / issues

- **Non-blocking:** 14/15 text commands reached **`PROCESSED`**; **1** («выключи музыку») returned **`NEEDS_CLARIFICATION`** (expected — no music-stop capability).
- **SLO:** Weather, joke (LLM), capabilities summary, settings launch, and first flashlight e2e missed 1s (`slo1s=false`) due to brain/TTS/launcher latency — not install/planner/agent-loop/router failures.
- **Settings launch** brain **5563ms** (launcher path) — functional but slow vs device commands.
- **«шутка»** took LLM-heavy path (brain **7862ms**) — functional.

## Key numbers

- **Brain aiprovider unit tests:** 22 / 22 passed (12 + 10)  
- **AgentExecutionLoopTest:** 9 / 9 passed  
- **DeterministicAIPlannerTest:** 10 / 10 passed  
- **Core unit tests:** 330 / 330 passed  
- **Combined unit tests:** 352 / 352 passed  
- **Install:** Success  
- **`agentLoop=on` on device:** **yes** (`Jarvis.Planner` at VoiceService start)  
- **`aiPlanner=deterministic` on device:** **yes**  
- **`router=on` on device:** **yes** (`Jarvis.AIProvider` at VoiceService start)  
- **Device `DEBUG_COMMAND` with `PROCESSED`:** **14 / 15**  
- **Device commands with e2e audio (incl. clarification):** **15 / 15**  
- **Autonomy dry-runs:** **2 / 2** ALLOW / READY_TO_EXECUTE  
- **Flashlight on e2e (1st / repeat):** 1095 ms / 280 ms  
- **Flashlight off e2e:** 338 ms (`slo1s=true`)  
- **Volume up / down e2e:** 301 ms / 280 ms (`slo1s=true`)  
- **Weather brain / e2e:** 2944 ms / 5618 ms (`slo1s=false`)  
- **Open settings brain / e2e:** 5563 ms / 5922 ms (`slo1s=false`)  

## Summary

| Gate | Result |
|------|--------|
| Build + unit tests (352, 0 failures) | **PASS** |
| Install + voice warm-up + **`agentLoop=on`** + **`aiPlanner=deterministic`** + **`router=on`** | **PASS** |
| Large device command matrix (15 DEBUG_COMMAND + 2 autonomy) | **PASS** (14 PROCESSED + 1 clarification + 2 autonomy dry-runs) |
| **Overall Stage 12 device suite** | **PASS** (music clarification expected; LLM/settings latency noted) |
