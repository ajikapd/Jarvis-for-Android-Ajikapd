# Stage 10 — Device / build / test results

**Date:** 2026-09-30  
**JAVA_HOME:** `/usr/lib/jvm/java-17-openjdk-amd64`  
**Device:** `dideztgiojzpgapn` (Xiaomi 23117RA68G / emerald_global)  
**Package:** `com.jarvis.assistant`  
**Wake pipeline:** not modified

## Build

| Item | Result |
|------|--------|
| `./gradlew :brain:testDebugUnitTest --tests 'com.jarvis.brain.aiprovider.*' :core:testDebugUnitTest :app:assembleDebug` | **SUCCESS** (~5s, UP-TO-DATE) |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Size | 209,807,425 bytes (~200 MB) |
| Mtime | 2026-09-30 16:50 |

## Install / permissions

| Item | Result |
|------|--------|
| `adb devices` | 1 device: `dideztgiojzpgapn` **device** |
| `adb install -r …/app-debug.apk` | **Success** (Streamed Install) |
| `pm grant … RECORD_AUDIO` | **granted=true** |
| `am start … DashboardActivity` | **OK** |
| `DEBUG_VOICE` `action=start` | **OK** — `voice: start requested`; ChatLlm warm-up **2070ms**; ASR loaded **5969ms**; KWS listening |

## Unit tests (JUnit XML under `*/build/test-results/`)

| Suite | Status | Counts (from XML) |
|-------|--------|-------------------|
| `:brain:testDebugUnitTest` (`com.jarvis.brain.aiprovider.*`) | **PASS** | **12** tests, **0** failures, **0** errors, **0** skipped |
| `:core:testDebugUnitTest` | **PASS** | **320** tests, **0** failures, **0** errors, **0** skipped |
| **Combined** | **PASS** | **332** / **332** passed |

XML sources: `brain/build/test-results/testDebugUnitTest/TEST-com.jarvis.brain.aiprovider.AIProviderTest.xml` + 30 core suite XMLs under `core/build/test-results/testDebugUnitTest/`.

## Device commands (debug broadcasts)

Quoting used: `adb shell "am broadcast … --es command \"…\""` so full multi-word Russian phrases were delivered.

Launched `DashboardActivity`, started voice via `DEBUG_VOICE`, then:

| # | Action | Result |
|---|--------|--------|
| 1 | `DEBUG_COMMAND` «включи фонарик» | **OK** — `status=PROCESSED`, reply «Включён.», cap=`DEVICE_LIGHT`/`flashlight` SUCCESS, brain **199ms**, e2e first-audio **504ms**, `slo1s=true` |
| 2 | `DEBUG_COMMAND` «выключи фонарик» | **OK** — `status=PROCESSED`, reply «Выключен.», flashlight SUCCESS, brain **89ms**, e2e first-audio **470ms**, `slo1s=true` |
| 3 | `DEBUG_COMMAND` «сделай громче» | **OK** — `status=PROCESSED`, reply «Громче.», cap=`DEVICE_AUDIO`/`volume` SUCCESS, brain **106ms**, e2e first-audio **329ms**, `slo1s=true` |
| 4 | `DEBUG_COMMAND` «какая погода» | **OK** — `status=PROCESSED`, Moscow weather (~16°), cap=`WEATHER_LOOKUP` SUCCESS, brain **1142ms**, e2e first-audio **4304ms**, `slo1s=false` (TTS-heavy) |
| 5 | `DEBUG_COMMAND` «который час» | **OK** — `status=PROCESSED`, reply «16:53», brain **76ms**, e2e first-audio **483ms**, `slo1s=true` |
| 6 | `DEBUG_COMMAND` «сколько времени» | **OK** — `status=PROCESSED`, reply «16:53», brain **94ms**, e2e first-audio **577ms**, `slo1s=true` |
| 7 | `DEBUG_COMMAND` «кто ты» | **OK** — `status=PROCESSED`, reply «Я Джарвис — голосовой ассистент. Работаю офлайн.», brain **117ms**, e2e first-audio **1039ms**, `slo1s=false` |
| 8 | `DEBUG_COMMAND` «расскажи шутку» | **OK** — `status=PROCESSED`, short joke reply, brain **132ms**, e2e first-audio **1891ms**, `slo1s=false` |
| 9 | `DEBUG_AUTONOMY` `flashlight_on` `live=false` | **OK** — dry-run `status=READY_TO_EXECUTE`, `verdict=ALLOW`, `tool=flashlight`, notes risk=LOW / SAFE_ONLY |

## Logcat tags captured

Filtered / grepped: `Jarvis.AIProvider`, `Jarvis.Capability`, `Jarvis.ChatLlm`, `Jarvis.Debug`, `slo1s` (via VoiceService e2e lines).

Notable startup lines:

- `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=1`
- `Jarvis.Capability`: `registry ready caps=24 tools=24 catalog=24 discovery=on`
- `Jarvis.ChatLlm`: `warm-up 2070ms model=Qwen3-0.6B-Q4_K_M.gguf profile=QUALITY`

## Failures / issues

- **None blocking.** Build, install, unit tests, and all correctly-quoted device commands succeeded.
- Weather / identity / joke e2e missed 1s SLO due to TTS latency (not tool/brain failures).
- Weather brain **1142ms** alone exceeded 1s; flashlight/volume/time stayed well under.

## Key numbers

- **Brain aiprovider unit tests:** 12 / 12 passed  
- **Core unit tests:** 320 / 320 passed  
- **Combined unit tests:** 332 / 332 passed  
- **Install:** Success  
- **Flashlight on first-audio:** 504 ms (`slo1s=true`)  
- **Flashlight off first-audio:** 470 ms (`slo1s=true`)  
- **Volume up first-audio:** 329 ms (`slo1s=true`)  
- **Weather brain / e2e:** 1142 ms / 4304 ms (`slo1s=false`)  
- **Time («который час» / «сколько времени»):** 483 ms / 577 ms (`slo1s=true`)  
- **Who / joke e2e:** 1039 ms / 1891 ms (`slo1s=false`)  
- **Autonomy dry-run:** ALLOW / READY_TO_EXECUTE  
