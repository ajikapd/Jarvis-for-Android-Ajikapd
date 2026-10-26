# Stage 17 — Files agent / device verification

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
| Mtime | 2026-09-30 17:51 (+0300) |

## Install / permissions

| Item | Result |
|------|--------|
| `adb devices` | 1 device: `dideztgiojzpgapn` **device** |
| `adb install -r …/app-debug.apk` | **Success** (Streamed Install) |
| `pm grant … RECORD_AUDIO` | **granted** |
| `am start … DashboardActivity` | **OK** |
| Cold start + `DEBUG_VOICE` `action=start` | **OK** — VoiceService start; planner log at service init |
| **Stage 17 wiring — planner** | `Jarvis.Planner`: **`aiPlanner=deterministic`** `discovery=on` **`agentLoop=on`** **`verifier=on`** **`nativeTools=24`** **`liveSafe=3`** **`liveRead=3`** **`plugins=0`** **`mcp=0`** **`filesAgent=on`** |
| **Stage 16 wiring — router** | `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=0` **`router=on`** |

Startup log excerpt (install + `DEBUG_VOICE`):

```
Jarvis.Planner: aiPlanner=deterministic discovery=on agentLoop=on verifier=on nativeTools=24 liveSafe=3 liveRead=3 plugins=0 mcp=0 filesAgent=on
Jarvis.AIProvider: providers=2 local=1 cloud=1 available=0 router=on
```

## Unit tests (JUnit XML under `*/build/test-results/`)

| Suite | Status | Counts (from XML) |
|-------|--------|-------------------|
| `:brain:testDebugUnitTest` (`com.jarvis.brain.aiprovider.*`) | **PASS** | **22** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `AIProviderTest` | **PASS** | 12 |
| ↳ `AIRouterTest` | **PASS** | 10 |
| `:core:testDebugUnitTest` | **PASS** | **371** tests, **0** failures, **0** errors, **0** skipped (37 suites) |
| ↳ `FilesAgentTest` | **PASS** | **7** tests, **0** failures |
| ↳ `McpRegistryTest` | **PASS** | **5** tests, **0** failures |
| ↳ `PluginRegistryTest` | **PASS** | **5** tests, **0** failures |
| ↳ `NativeAndroidToolCatalogTest` | **PASS** | **6** tests, **0** failures |
| **Combined (core + brain filter)** | **PASS** | **393** / **393** passed |

XML sources: `brain/build/test-results/testDebugUnitTest/TEST-com.jarvis.brain.aiprovider.*.xml`, `core/build/test-results/testDebugUnitTest/TEST-com.jarvis.core.filesagent.FilesAgentTest.xml`, + 36 other core suite XMLs under `core/build/test-results/testDebugUnitTest/`.

### FilesAgentTest (Stage 17)

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

After install and voice start, sequential `DEBUG_COMMAND` runs waited for `Jarvis.VoiceService` **e2e first-audio** (or clarification + e2e) before the next command. Log excerpt: `md_files/.stage17_device.log`. Runner: `scripts/stage17_device_run.sh`.

| # | Command | Result |
|---|---------|--------|
| 1 | `DEBUG_COMMAND` «включи фонарик» | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **205ms**, e2e **522ms**, `slo1s=true` |
| 2 | `DEBUG_COMMAND` «выключи фонарик» | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **26ms**, e2e **340ms**, `slo1s=true` |
| 3 | `DEBUG_COMMAND` «громче» | **OK** — `status=PROCESSED`, volume SUCCESS **`verified=true`**, brain **110ms**, e2e **325ms**, `slo1s=true` |
| 4 | `DEBUG_COMMAND` «тише» | **OK** — `status=PROCESSED`, volume SUCCESS **`verified=true`**, brain **81ms**, e2e **299ms**, `slo1s=true` |
| 5 | `DEBUG_COMMAND` «погода» | **OK** — `status=PROCESSED`, weather SUCCESS **`verified=true`**, brain **877ms**, e2e **4236ms**, `slo1s=false` |
| 6 | `DEBUG_COMMAND` «который час» | **OK** — `status=PROCESSED`, time reply, brain **91ms**, e2e **653ms**, `slo1s=true` |
| 7 | `DEBUG_COMMAND` «сколько времени» | **OK** — `status=PROCESSED`, time reply, brain **69ms**, e2e **493ms**, `slo1s=true` |
| 8 | `DEBUG_COMMAND` «кто ты» | **OK** — `status=PROCESSED`, identity reply, brain **69ms**, e2e **1080ms**, `slo1s=false` |
| 9 | `DEBUG_COMMAND` «шутка» | **OK** — `status=PROCESSED`, joke (LLM path), brain **16962ms**, e2e **17508ms**, `slo1s=false` |
| 10 | `DEBUG_COMMAND` «привет» | **OK** — `status=PROCESSED`, greeting, brain **62ms**, e2e **572ms**, `slo1s=true` |
| 11 | `DEBUG_COMMAND` «что ты умеешь» | **OK** — `status=PROCESSED`, capabilities summary, brain **139ms**, e2e **1658ms**, `slo1s=false` |
| 12 | `DEBUG_COMMAND` «какой сегодня день» | **OK** — `status=PROCESSED`, date reply, brain **72ms**, e2e **999ms**, `slo1s=true` |
| 13 | `DEBUG_COMMAND` «открой настройки» | **OK** — `status=PROCESSED`, launcher SUCCESS **`verified=true`**, brain **5635ms**, e2e **5857ms**, `slo1s=false` |
| 14 | `DEBUG_COMMAND` «выключи музыку» | **PARTIAL** — `status=NEEDS_CLARIFICATION`, brain **12ms**, e2e **1001ms**, `slo1s=false` |
| 15 | `DEBUG_COMMAND` «включи фонарик» (repeat) | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **94ms**, e2e **321ms**, `slo1s=true` |

### Autonomy dry-runs (`DEBUG_AUTONOMY`, `live=false`)

| Action | Result |
|--------|--------|
| `flashlight_on` | **OK** — `autonomy dry-run status=READY_TO_EXECUTE verdict=ALLOW tool=flashlight` |
| `volume_up` | **OK** — `autonomy dry-run status=READY_TO_EXECUTE verdict=ALLOW tool=volume` |

Tool paths with explicit verification on device (log `verified=true`): flashlight (×3), volume (×2), weather, launcher.

## Failures / issues

- **Non-blocking:** 14/15 text commands reached **`PROCESSED`**; **1** («выключи музыку») returned **`NEEDS_CLARIFICATION`** (expected — no music-stop capability).
- **SLO:** Weather, identity, joke (LLM), capabilities, settings launch missed 1s (`slo1s=false`) — latency, not Stage 17 FilesAgent wiring failures.
- **«шутка»** LLM-heavy (brain **16962ms**) — functional but slow.
- **FilesAgent on device:** `BrainGraph.filesAgent()` wired → **`filesAgent=on`** in `Jarvis.Planner` at VoiceService start; Stage 16 fields retained (`mcp=0`, `plugins=0`, `nativeTools=24`).

## Key numbers

- **FilesAgentTest:** 7 / 7 passed  
- **Brain aiprovider unit tests:** 22 / 22 passed (12 + 10)  
- **Core unit tests:** 371 / 371 passed  
- **Combined unit tests:** 393 / 393 passed  
- **Install:** Success  
- **`filesAgent=on` on device:** **yes** (`Jarvis.Planner` at VoiceService start)  
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
| Build + unit tests (393, 0 failures) | **PASS** |
| Install + voice warm-up + **`filesAgent=on`** + Stage 16 planner fields retained | **PASS** |
| Large device command matrix (15 DEBUG_COMMAND + 2 autonomy) | **PASS** (14 PROCESSED + 1 clarification + 2 autonomy dry-runs) |
| **Overall Stage 17 verification** | **PASS** |
