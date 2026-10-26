# Stage 13 — Device / build / test results

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
| Mtime | 2026-09-30 17:29 |

## Install / permissions

| Item | Result |
|------|--------|
| `adb devices` | 1 device: `dideztgiojzpgapn` **device** |
| `adb install -r …/app-debug.apk` | **Success** (Streamed Install) |
| `pm grant … RECORD_AUDIO` | **granted** |
| `am start … DashboardActivity` | **OK** |
| Cold start + `DEBUG_VOICE` `action=start` | **OK** — `voice: start requested`; ChatLlm load **1685ms**, warm-up **2090ms**; ASR loading |
| **Stage 13 wiring — planner** | `Jarvis.Planner`: **`aiPlanner=deterministic`** `discovery=on` **`agentLoop=on`** **`verifier=on`** |
| **Stage 13 wiring — router** | `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=0` **`router=on`** |

## Unit tests (JUnit XML under `*/build/test-results/`)

| Suite | Status | Counts (from XML) |
|-------|--------|-------------------|
| `:brain:testDebugUnitTest` (`com.jarvis.brain.aiprovider.*`) | **PASS** | **22** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `AIProviderTest` | **PASS** | 12 |
| ↳ `AIRouterTest` | **PASS** | 10 |
| `:core:testDebugUnitTest` | **PASS** | **348** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `DefaultResultVerifierTest` | **PASS** | **9** tests, **0** failures |
| ↳ `AgentExecutionLoopTest` | **PASS** | **9** tests, **0** failures |
| ↳ `DeterministicAIPlannerTest` | **PASS** | 10 tests, **0** failures |
| **Combined (core + brain filter)** | **PASS** | **370** / **370** passed |

XML sources: `brain/build/test-results/testDebugUnitTest/TEST-com.jarvis.brain.aiprovider.*.xml`, `core/build/test-results/testDebugUnitTest/TEST-com.jarvis.core.verification.DefaultResultVerifierTest.xml`, `TEST-com.jarvis.core.agent.AgentExecutionLoopTest.xml`, + 32 other core suite XMLs under `core/build/test-results/testDebugUnitTest/`.

## Device commands (debug broadcasts)

Quoting used: `adb shell "am broadcast … --es command \"…\""` so full multi-word Russian phrases were delivered.

After install and voice start, sequential `DEBUG_COMMAND` runs waited for `Jarvis.VoiceService` **e2e first-audio** (or clarification + e2e) before the next command. Log excerpt: `/tmp/stage13_device.log`. Runner: `scripts/stage13_device_run.sh`.

| # | Action | Result |
|---|--------|--------|
| 1 | `DEBUG_COMMAND` «включи фонарик» | **OK** — `status=PROCESSED`, cap=`DEVICE_LIGHT`/`flashlight` SUCCESS **`verified=true`**, brain **254ms**, e2e **565ms**, `slo1s=true` |
| 2 | `DEBUG_COMMAND` «выключи фонарик» | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **27ms**, e2e **419ms**, `slo1s=true` |
| 3 | `DEBUG_COMMAND` «громче» | **OK** — `status=PROCESSED`, cap=`DEVICE_AUDIO`/`volume` SUCCESS **`verified=true`**, brain **217ms**, e2e **435ms**, `slo1s=true` |
| 4 | `DEBUG_COMMAND` «тише» | **OK** — `status=PROCESSED`, volume SUCCESS **`verified=true`**, brain **92ms**, e2e **321ms**, `slo1s=true` |
| 5 | `DEBUG_COMMAND` «погода» | **OK** — `status=PROCESSED`, weather SUCCESS **`verified=true`**, brain **892ms**, e2e **4350ms**, `slo1s=false` |
| 6 | `DEBUG_COMMAND` «который час» | **OK** — `status=PROCESSED`, time reply, brain **82ms**, e2e **565ms**, `slo1s=true` |
| 7 | `DEBUG_COMMAND` «сколько времени» | **OK** — `status=PROCESSED`, time reply, brain **182ms**, e2e **708ms**, `slo1s=true` |
| 8 | `DEBUG_COMMAND` «кто ты» | **OK** — `status=PROCESSED`, identity reply, brain **109ms**, e2e **1241ms**, `slo1s=false` |
| 9 | `DEBUG_COMMAND` «шутка» | **OK** — `status=PROCESSED`, joke (LLM path), brain **24582ms**, e2e **25175ms**, `slo1s=false` |
| 10 | `DEBUG_COMMAND` «привет» | **OK** — `status=PROCESSED`, greeting, brain **67ms**, e2e **608ms**, `slo1s=true` |
| 11 | `DEBUG_COMMAND` «что ты умеешь» | **OK** — `status=PROCESSED`, capabilities summary, brain **56ms**, e2e **1629ms**, `slo1s=false` |
| 12 | `DEBUG_COMMAND` «какой сегодня день» | **OK** — `status=PROCESSED`, date reply, brain **83ms**, e2e **1002ms**, `slo1s=false` |
| 13 | `DEBUG_COMMAND` «открой настройки» | **OK** — `status=PROCESSED`, cap=`APP_LAUNCH`/`launcher` SUCCESS **`verified=true`**, brain **6765ms**, e2e **7148ms**, `slo1s=false` |
| 14 | `DEBUG_COMMAND` «выключи музыку» | **PARTIAL** — `status=NEEDS_CLARIFICATION`, brain **10ms**, e2e **785ms**, `slo1s=true` |
| 15 | `DEBUG_COMMAND` «включи фонарик» (repeat) | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **108ms**, e2e **328ms**, `slo1s=true` |
| 16 | `DEBUG_AUTONOMY` `flashlight_on` `live=false` | **OK** — dry-run `status=READY_TO_EXECUTE`, `verdict=ALLOW`, `tool=flashlight` |
| 17 | `DEBUG_AUTONOMY` `volume_up` `live=false` | **OK** — dry-run `status=READY_TO_EXECUTE`, `verdict=ALLOW`, `tool=volume` |

## Logcat tags captured

Filtered / grepped: `Jarvis.Planner`, `Jarvis.AIProvider`, `Jarvis.Capability`, `Jarvis.ChatLlm`, `Jarvis.Debug`, `Jarvis.VoiceService`, `slo1s` (e2e lines).

Notable startup lines:

- `Jarvis.Planner`: **`aiPlanner=deterministic`** `discovery=on` **`agentLoop=on`** **`verifier=on`**
- `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=0` **`router=on`**
- `Jarvis.ChatLlm`: load **1685ms**, warm-up **2090ms** model=`Qwen3-0.6B-Q4_K_M.gguf` profile=QUALITY

Tool paths with explicit verification on device (log `verified=true`): flashlight (×3), volume (×2), weather, launcher.

## Failures / issues

- **Non-blocking:** 14/15 text commands reached **`PROCESSED`**; **1** («выключи музыку») returned **`NEEDS_CLARIFICATION`** (expected — no music-stop capability).
- **SLO:** Weather, identity, joke (LLM), capabilities, date, settings launch missed 1s (`slo1s=false`) — latency, not verifier/router wiring failures.
- **«шутка»** LLM-heavy (brain **24582ms**) — functional but slow.

## Key numbers

- **DefaultResultVerifierTest:** 9 / 9 passed  
- **AgentExecutionLoopTest:** 9 / 9 passed  
- **Brain aiprovider unit tests:** 22 / 22 passed (12 + 10)  
- **Core unit tests:** 348 / 348 passed  
- **Combined unit tests:** 370 / 370 passed  
- **Install:** Success  
- **`verifier=on` on device:** **yes** (`Jarvis.Planner` at VoiceService start)  
- **`agentLoop=on` on device:** **yes**  
- **`aiPlanner=deterministic` on device:** **yes**  
- **`router=on` on device:** **yes** (`Jarvis.AIProvider` at VoiceService start)  
- **Device `DEBUG_COMMAND` with `PROCESSED`:** **14 / 15**  
- **Device commands with e2e audio (incl. clarification):** **15 / 15**  
- **Autonomy dry-runs:** **2 / 2** ALLOW / READY_TO_EXECUTE  
- **Capability lines with `verified=true`:** **7** (flashlight ×3, volume ×2, weather, launcher)  

## Summary

| Gate | Result |
|------|--------|
| Build + unit tests (370, 0 failures) | **PASS** |
| Install + voice warm-up + **`verifier=on`** + **`agentLoop=on`** + **`router=on`** | **PASS** |
| Large device command matrix (15 DEBUG_COMMAND + 2 autonomy) | **PASS** (14 PROCESSED + 1 clarification + 2 autonomy dry-runs) |
| **Overall Stage 13 device suite** | **PASS** |
