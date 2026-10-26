# Stage 15 — Plugin architecture / device verification

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
| Mtime | 2026-09-30 17:39 |

## Install / permissions

| Item | Result |
|------|--------|
| `adb devices` | 1 device: `dideztgiojzpgapn` **device** |
| `adb install -r …/app-debug.apk` | **Success** (Streamed Install) |
| `pm grant … RECORD_AUDIO` | **granted** |
| `am start … DashboardActivity` | **OK** |
| Cold start + `DEBUG_VOICE` `action=start` | **OK** — VoiceService start; planner log at service init |
| **Stage 15 wiring — planner** | `Jarvis.Planner`: **`aiPlanner=deterministic`** `discovery=on` **`agentLoop=on`** **`verifier=on`** **`nativeTools=24`** **`liveSafe=3`** **`liveRead=3`** **`plugins=0`** |
| **Stage 15 wiring — router** | `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=0` **`router=on`** (LLM warm-up timing; Stage 14 often showed `available=1` after full load) |

Startup log excerpt (install + `DEBUG_VOICE`):

```
Jarvis.Planner: aiPlanner=deterministic discovery=on agentLoop=on verifier=on nativeTools=24 liveSafe=3 liveRead=3 plugins=0
Jarvis.AIProvider: providers=2 local=1 cloud=1 available=0 router=on
```

## Unit tests (JUnit XML under `*/build/test-results/`)

| Suite | Status | Counts (from XML) |
|-------|--------|-------------------|
| `:brain:testDebugUnitTest` (`com.jarvis.brain.aiprovider.*`) | **PASS** | **22** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `AIProviderTest` | **PASS** | 12 |
| ↳ `AIRouterTest` | **PASS** | 10 |
| `:core:testDebugUnitTest` | **PASS** | **359** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `PluginRegistryTest` | **PASS** | **5** tests, **0** failures |
| ↳ `NativeAndroidToolCatalogTest` | **PASS** | **6** tests, **0** failures |
| ↳ `ProposedActionMapperTest` | **PASS** | **5** tests, **0** failures |
| ↳ `DefaultResultVerifierTest` | **PASS** | **9** tests, **0** failures |
| ↳ `AgentExecutionLoopTest` | **PASS** | **9** tests, **0** failures |
| ↳ `DeterministicAIPlannerTest` | **PASS** | 10 tests, **0** failures |
| **Combined (core + brain filter)** | **PASS** | **381** / **381** passed |

XML sources: `brain/build/test-results/testDebugUnitTest/TEST-com.jarvis.brain.aiprovider.*.xml`, `core/build/test-results/testDebugUnitTest/TEST-com.jarvis.core.plugin.PluginRegistryTest.xml`, `core/build/test-results/testDebugUnitTest/TEST-com.jarvis.core.nativeandroid.NativeAndroidToolCatalogTest.xml`, + 33 other core suite XMLs under `core/build/test-results/testDebugUnitTest/`.

### PluginRegistryTest (Stage 15)

| Test | Result |
|------|--------|
| `registerStartExposesDefinitions` | pass |
| `stopHidesProvider` | pass |
| `failedStartDoesNotExposeTools` | pass |
| `unregisterRemoves` | pass |
| `coreDoesNotDependOnConcretePluginTypes` | pass |

### NativeAndroidToolCatalogTest (unchanged from Stage 14)

| Test | Result |
|------|--------|
| `catalogCoversFirstSafe` | pass |
| `readOnlyLiveAllowed` | pass |
| `sensitiveBlockedFromLive` | pass |
| `gatedNotLive` | pass |
| `mapperCoversCatalogIds` | pass |
| `sizeIsStable` | pass (catalog size **24**, ≥ 20) |

## Device commands (debug broadcasts)

Quoting used: `adb shell "am broadcast … --es command \"…\""` so full multi-word Russian phrases were delivered.

After install and voice start, sequential `DEBUG_COMMAND` runs waited for `Jarvis.VoiceService` **e2e first-audio** (or clarification + e2e) before the next command. Log excerpt: `/tmp/stage15_device.log`. Runner: `scripts/stage14_device_run.sh` (log paths redirected to `stage15` via sed) + manual autonomy append.

| # | Command | Result |
|---|---------|--------|
| 1 | `DEBUG_COMMAND` «включи фонарик» | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **311ms**, e2e **667ms**, `slo1s=true` |
| 2 | `DEBUG_COMMAND` «выключи фонарик» | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **14ms**, e2e **242ms**, `slo1s=true` |
| 3 | `DEBUG_COMMAND` «громче» | **OK** — `status=PROCESSED`, volume SUCCESS **`verified=true`**, brain **86ms**, e2e **260ms**, `slo1s=true` |
| 4 | `DEBUG_COMMAND` «тише» | **OK** — `status=PROCESSED`, volume SUCCESS **`verified=true`**, brain **76ms**, e2e **264ms**, `slo1s=true` |
| 5 | `DEBUG_COMMAND` «погода» | **OK** — `status=PROCESSED`, weather SUCCESS **`verified=true`**, brain **4159ms**, e2e **6975ms**, `slo1s=false` |
| 6 | `DEBUG_COMMAND` «который час» | **OK** — `status=PROCESSED`, time reply, brain **69ms**, e2e **411ms**, `slo1s=true` |
| 7 | `DEBUG_COMMAND` «сколько времени» | **OK** — `status=PROCESSED`, time reply, brain **106ms**, e2e **417ms**, `slo1s=true` |
| 8 | `DEBUG_COMMAND` «кто ты» | **OK** — `status=PROCESSED`, identity reply, brain **69ms**, e2e **1030ms**, `slo1s=false` |
| 9 | `DEBUG_COMMAND` «шутка» | **OK** — `status=PROCESSED`, joke (LLM path), brain **20880ms**, e2e **21826ms**, `slo1s=false` |
| 10 | `DEBUG_COMMAND` «привет» | **OK** — `status=PROCESSED`, greeting, brain **73ms**, e2e **562ms**, `slo1s=true` |
| 11 | `DEBUG_COMMAND` «что ты умеешь» | **OK** — `status=PROCESSED`, capabilities summary, brain **163ms**, e2e **1939ms**, `slo1s=false` |
| 12 | `DEBUG_COMMAND` «какой сегодня день» | **OK** — `status=PROCESSED`, date reply, brain **74ms**, e2e **975ms**, `slo1s=true` |
| 13 | `DEBUG_COMMAND` «открой настройки» | **OK** — `status=PROCESSED`, launcher SUCCESS **`verified=true`**, brain **4082ms**, e2e **4628ms**, `slo1s=false` |
| 14 | `DEBUG_COMMAND` «выключи музыку» | **PARTIAL** — `status=NEEDS_CLARIFICATION`, brain **2ms**, e2e **938ms**, `slo1s=true` |
| 15 | `DEBUG_COMMAND` «включи фонарик» (repeat) | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **122ms**, e2e **353ms**, `slo1s=true` |

### Autonomy dry-runs (`DEBUG_AUTONOMY`, `live=false`)

| Action | Result |
|--------|--------|
| `flashlight_on` | **OK** — `autonomy dry-run status=READY_TO_EXECUTE verdict=ALLOW tool=flashlight` |
| `volume_up` | **OK** — `autonomy dry-run status=READY_TO_EXECUTE verdict=ALLOW tool=volume` |

Tool paths with explicit verification on device (log `verified=true`): flashlight (×3), volume (×2), weather, launcher.

## Failures / issues

- **Non-blocking:** 14/15 text commands reached **`PROCESSED`**; **1** («выключи музыку») returned **`NEEDS_CLARIFICATION`** (expected — no music-stop capability).
- **SLO:** Weather, identity, joke (LLM), capabilities, settings launch missed 1s (`slo1s=false`) — latency, not Stage 15 plugin wiring failures.
- **«шутка»** LLM-heavy (brain **20880ms**) — functional but slow.
- **Plugin registry on device:** empty registry wired in `BrainGraph` → **`plugins=0`** as required; native catalog unchanged at **24** tools.

## Key numbers

- **PluginRegistryTest:** 5 / 5 passed  
- **NativeAndroidToolCatalogTest:** 6 / 6 passed  
- **Brain aiprovider unit tests:** 22 / 22 passed (12 + 10)  
- **Core unit tests:** 359 / 359 passed  
- **Combined unit tests:** 381 / 381 passed  
- **Install:** Success  
- **`plugins=0` on device:** **yes** (`Jarvis.Planner` at VoiceService start)  
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
| Build + unit tests (381, 0 failures) | **PASS** |
| Install + voice warm-up + **`plugins=0`** + **`nativeTools=24`** + Stage 14 planner fields retained | **PASS** |
| Large device command matrix (15 DEBUG_COMMAND + 2 autonomy) | **PASS** (14 PROCESSED + 1 clarification + 2 autonomy dry-runs) |
| **Overall Stage 15 verification** | **PASS** |
