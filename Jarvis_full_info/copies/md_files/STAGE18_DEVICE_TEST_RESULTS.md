# Stage 18 — Browser agent / device verification

**Date:** 2026-09-30  
**JAVA_HOME:** `/usr/lib/jvm/java-17-openjdk-amd64`  
**Device:** `dideztgiojzpgapn` (Xiaomi 23117RA68G / emerald)  
**Package:** `com.jarvis.assistant`  
**Receiver:** `com.jarvis.assistant/com.jarvis.app.DebugCommandReceiver`  
**Wake pipeline:** not modified

## Build

| Item | Result |
|------|--------|
| `./gradlew :core:testDebugUnitTest :brain:testDebugUnitTest --tests 'com.jarvis.brain.aiprovider.*' :app:assembleDebug` | **SUCCESS** |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Size | 209,807,602 bytes (~200 MB) |
| Mtime | 2026-09-30 17:55 (+0300) |

## Install / permissions

| Item | Result |
|------|--------|
| `adb devices` | 1 device: `dideztgiojzpgapn` **device** |
| `adb install -r …/app-debug.apk` | **Success** (Streamed Install) |
| `pm grant … RECORD_AUDIO` | **granted** |
| `am start … DashboardActivity` | **OK** |
| Cold start + `DEBUG_VOICE` `action=start` | **OK** — VoiceService start; planner log at service init |
| **Stage 18 wiring — planner** | `Jarvis.Planner`: **`aiPlanner=deterministic`** `discovery=on` **`agentLoop=on`** **`verifier=on`** **`nativeTools=24`** **`liveSafe=3`** **`liveRead=3`** **`plugins=0`** **`mcp=0`** **`filesAgent=on`** **`browserAgent=on`** |
| **Stage 16 wiring — router** | `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=0` **`router=on`** |

Startup log excerpt (install + `DEBUG_VOICE`):

```
Jarvis.Planner: aiPlanner=deterministic discovery=on agentLoop=on verifier=on nativeTools=24 liveSafe=3 liveRead=3 plugins=0 mcp=0 filesAgent=on browserAgent=on
Jarvis.AIProvider: providers=2 local=1 cloud=1 available=0 router=on
```

## Unit tests (JUnit XML under `*/build/test-results/`)

| Suite | Status | Counts (from XML) |
|-------|--------|-------------------|
| `:brain:testDebugUnitTest` (`com.jarvis.brain.aiprovider.*`) | **PASS** | **22** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `AIProviderTest` | **PASS** | 12 |
| ↳ `AIRouterTest` | **PASS** | 10 |
| `:core:testDebugUnitTest` | **PASS** | **377** tests, **0** failures, **0** errors, **0** skipped (38 suites) |
| ↳ `BrowserAgentTest` | **PASS** | **6** tests, **0** failures |
| ↳ `FilesAgentTest` | **PASS** | **7** tests, **0** failures |
| ↳ `McpRegistryTest` | **PASS** | 5 tests, **0** failures |
| ↳ `PluginRegistryTest` | **PASS** | 5 tests, **0** failures |
| ↳ `NativeAndroidToolCatalogTest` | **PASS** | 6 tests, **0** failures |
| **Combined (core + brain filter)** | **PASS** | **399** / **399** passed |

XML sources: `brain/build/test-results/testDebugUnitTest/TEST-com.jarvis.brain.aiprovider.*.xml`, `core/build/test-results/testDebugUnitTest/TEST-com.jarvis.core.browseragent.BrowserAgentTest.xml`, `TEST-com.jarvis.core.filesagent.FilesAgentTest.xml`, + 36 other core suite XMLs under `core/build/test-results/testDebugUnitTest/`.

### BrowserAgentTest (Stage 18)

| Test | Result |
|------|--------|
| `offlineYieldsEmpty` | pass |
| `openIsConfirm` | pass |
| `actIsSensitive` | pass |
| `searchOnlyPipeline` | pass |
| `inspectExtractVerifyAreLowRisk` | pass |
| `defaultPipelineHasSearchOpenActWithConfirms` | pass |

### FilesAgentTest (Stage 17, retained)

| Test | Result |
|------|--------|
| `searchPlanSingleToolNoConfirm` | pass |
| `deleteRequiresConfirmAndSensitive` | pass |
| `inferDeleteFromRussian` | pass |
| `inferSearchDefault` | pass |
| `mutatingNeedsConfirm` | pass |
| `planFromGoalSearchUsesGoalTextAsQuery` | pass |
| `readIsSafeRead` | pass |

## Device commands (debug broadcasts)

Quoting used: `adb shell "am broadcast … --es command \"…\""` so full multi-word Russian phrases were delivered.

After install and voice start, sequential `DEBUG_COMMAND` runs waited for `Jarvis.VoiceService` **e2e first-audio** (or clarification + e2e) before the next command. Log excerpt: `md_files/.stage18_device.log`. Runner: `scripts/stage17_device_run.sh` (same Stage 16–18 matrix).

| # | Command | Result |
|---|---------|--------|
| 1 | `DEBUG_COMMAND` «включи фонарик» | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **181ms**, e2e **504ms**, `slo1s=true` |
| 2 | `DEBUG_COMMAND` «выключи фонарик» | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **15ms**, e2e **311ms**, `slo1s=true` |
| 3 | `DEBUG_COMMAND` «громче» | **OK** — `status=PROCESSED`, volume SUCCESS **`verified=true`**, brain **167ms**, e2e **354ms**, `slo1s=true` |
| 4 | `DEBUG_COMMAND` «тише» | **OK** — `status=PROCESSED`, volume SUCCESS **`verified=true`**, brain **54ms**, e2e **285ms**, `slo1s=true` |
| 5 | `DEBUG_COMMAND` «погода» | **OK** — `status=PROCESSED`, weather SUCCESS **`verified=true`**, brain **815ms**, e2e **3867ms**, `slo1s=false` |
| 6 | `DEBUG_COMMAND` «который час» | **OK** — `status=PROCESSED`, time reply, brain **123ms**, e2e **795ms**, `slo1s=true` |
| 7 | `DEBUG_COMMAND` «сколько времени» | **OK** — `status=PROCESSED`, time reply, brain **65ms**, e2e **545ms**, `slo1s=true` |
| 8 | `DEBUG_COMMAND` «кто ты» | **OK** — `status=PROCESSED`, identity reply, brain **63ms**, e2e **1027ms**, `slo1s=false` |
| 9 | `DEBUG_COMMAND` «шутка» | **OK** — `status=PROCESSED`, joke (LLM path), brain **12220ms**, e2e **12763ms**, `slo1s=false` |
| 10 | `DEBUG_COMMAND` «привет» | **OK** — `status=PROCESSED`, greeting, brain **63ms**, e2e **598ms**, `slo1s=true` |
| 11 | `DEBUG_COMMAND` «что ты умеешь» | **OK** — `status=PROCESSED`, capabilities summary, brain **139ms**, e2e **1792ms**, `slo1s=false` |
| 12 | `DEBUG_COMMAND` «какой сегодня день» | **OK** — `status=PROCESSED`, date reply, brain **99ms**, e2e **1032ms**, `slo1s=false` |
| 13 | `DEBUG_COMMAND` «открой настройки» | **OK** — `status=PROCESSED`, launcher SUCCESS **`verified=true`**, brain **4246ms**, e2e **4554ms**, `slo1s=false` |
| 14 | `DEBUG_COMMAND` «выключи музыку» | **PARTIAL** — `status=NEEDS_CLARIFICATION`, brain **2ms**, e2e **791ms**, `slo1s=true` |
| 15 | `DEBUG_COMMAND` «включи фонарик» (repeat) | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **73ms**, e2e **308ms**, `slo1s=true` |

### Autonomy dry-runs (`DEBUG_AUTONOMY`, `live=false`)

| Action | Result |
|--------|--------|
| `flashlight_on` | **OK** — `autonomy dry-run status=READY_TO_EXECUTE verdict=ALLOW tool=flashlight` |
| `volume_up` | **OK** — `autonomy dry-run status=READY_TO_EXECUTE verdict=ALLOW tool=volume` |

Tool paths with explicit verification on device (log `verified=true`): flashlight (×3), volume (×2), weather, launcher.

## Failures / issues

- **Non-blocking:** 14/15 text commands reached **`PROCESSED`**; **1** («выключи музыку») returned **`NEEDS_CLARIFICATION`** (expected — no music-stop capability).
- **SLO:** Weather, identity, joke (LLM), capabilities, date, settings launch missed 1s (`slo1s=false`) — latency, not Stage 18 BrowserAgent wiring failures.
- **«шутка»** LLM-heavy (brain **12220ms**) — functional but slow.
- **BrowserAgent on device:** `BrainGraph.browserAgent()` wired → **`browserAgent=on`** in `Jarvis.Planner` at VoiceService start; Stage 17 **`filesAgent=on`** and Stage 16 fields retained.

## Key numbers

- **BrowserAgentTest:** 6 / 6 passed  
- **FilesAgentTest:** 7 / 7 passed  
- **Brain aiprovider unit tests:** 22 / 22 passed (12 + 10)  
- **Core unit tests:** 377 / 377 passed  
- **Combined unit tests:** 399 / 399 passed  
- **Install:** Success  
- **`browserAgent=on` on device:** **yes** (`Jarvis.Planner` at VoiceService start)  
- **`filesAgent=on` on device:** **yes**  
- **`mcp=0` on device:** **yes**  
- **`plugins=0` on device:** **yes**  
- **`nativeTools=24` on device:** **yes**  
- **`liveSafe=3` / `liveRead=3` on device:** **yes**  
- **`verifier=on` / `agentLoop=on` / `aiPlanner=deterministic` on device:** **yes**  
- **`router=on` on device:** **yes**  
- **Device `DEBUG_COMMAND` with `PROCESSED`:** **14 / 15**  
- **Device commands with e2e audio (incl. clarification):** **15 / 15**  
- **Autonomy dry-runs:** **2 / 2** ALLOW / READY_TO_EXECUTE  
- **Capability lines with `verified=true`:** **7** (flashlight ×3, volume ×2, weather, launcher)  

## Summary

| Gate | Result |
|------|--------|
| Build + unit tests (399, 0 failures) | **PASS** |
| Install + voice warm-up + **`browserAgent=on`** + **`filesAgent=on`** + Stage 16 planner fields retained | **PASS** |
| Large device command matrix (15 DEBUG_COMMAND + 2 autonomy) | **PASS** (14 PROCESSED + 1 clarification + 2 autonomy dry-runs) |
| **Overall Stage 18 verification** | **PASS** |
