# Stage 16 — MCP integration / device verification

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
| Mtime | 2026-09-30 17:46 (+0300) |

## Install / permissions

| Item | Result |
|------|--------|
| `adb devices` | 1 device: `dideztgiojzpgapn` **device** |
| `adb install -r …/app-debug.apk` | **Success** (Streamed Install) |
| `pm grant … RECORD_AUDIO` | **granted** |
| `am start … DashboardActivity` | **OK** |
| Cold start + `DEBUG_VOICE` `action=start` | **OK** — VoiceService start; planner log at service init |
| **Stage 16 wiring — planner** | `Jarvis.Planner`: **`aiPlanner=deterministic`** `discovery=on` **`agentLoop=on`** **`verifier=on`** **`nativeTools=24`** **`liveSafe=3`** **`liveRead=3`** **`plugins=0`** **`mcp=0`** |
| **Stage 16 wiring — router** | `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=0` **`router=on`** |

Startup log excerpt (install + `DEBUG_VOICE`):

```
Jarvis.Planner: aiPlanner=deterministic discovery=on agentLoop=on verifier=on nativeTools=24 liveSafe=3 liveRead=3 plugins=0 mcp=0
Jarvis.AIProvider: providers=2 local=1 cloud=1 available=0 router=on
```

## Unit tests (JUnit XML under `*/build/test-results/`)

| Suite | Status | Counts (from XML) |
|-------|--------|-------------------|
| `:brain:testDebugUnitTest` (`com.jarvis.brain.aiprovider.*`) | **PASS** | **22** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `AIProviderTest` | **PASS** | 12 |
| ↳ `AIRouterTest` | **PASS** | 10 |
| `:core:testDebugUnitTest` | **PASS** | **364** tests, **0** failures, **0** errors, **0** skipped (36 suites) |
| ↳ `McpRegistryTest` | **PASS** | **5** tests, **0** failures |
| ↳ `PluginRegistryTest` | **PASS** | **5** tests, **0** failures |
| ↳ `NativeAndroidToolCatalogTest` | **PASS** | **6** tests, **0** failures |
| **Combined (core + brain filter)** | **PASS** | **386** / **386** passed |

XML sources: `brain/build/test-results/testDebugUnitTest/TEST-com.jarvis.brain.aiprovider.*.xml`, `core/build/test-results/testDebugUnitTest/TEST-com.jarvis.core.mcp.McpRegistryTest.xml`, `core/build/test-results/testDebugUnitTest/TEST-com.jarvis.core.plugin.PluginRegistryTest.xml`, + 34 other core suite XMLs under `core/build/test-results/testDebugUnitTest/`.

### McpRegistryTest (Stage 16)

| Test | Result |
|------|--------|
| `providerExposesMcpSourceDefinitions` | pass |
| `importIntoCatalogLooksLikeNormalTools` | pass |
| `discoveryAndPlannerTreatMcpAsTools` | pass |
| `unregisterRemovesProvider` | pass |
| `unconfiguredMarkedUnavailable` | pass |

### PluginRegistryTest (unchanged from Stage 15)

| Test | Result |
|------|--------|
| `registerStartExposesDefinitions` | pass |
| `stopHidesProvider` | pass |
| `failedStartDoesNotExposeTools` | pass |
| `unregisterRemoves` | pass |
| `coreDoesNotDependOnConcretePluginTypes` | pass |

## Device commands (debug broadcasts)

Quoting used: `adb shell "am broadcast … --es command \"…\""` so full multi-word Russian phrases were delivered.

After install and voice start, sequential `DEBUG_COMMAND` runs waited for `Jarvis.VoiceService` **e2e first-audio** (or clarification + e2e) before the next command. Log excerpt: `md_files/.stage16_device.log`. Runner: `scripts/stage16_device_run.sh`.

| # | Command | Result |
|---|---------|--------|
| 1 | `DEBUG_COMMAND` «включи фонарик» | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **191ms**, e2e **441ms**, `slo1s=true` |
| 2 | `DEBUG_COMMAND` «выключи фонарик» | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **26ms**, e2e **337ms**, `slo1s=true` |
| 3 | `DEBUG_COMMAND` «громче» | **OK** — `status=PROCESSED`, volume SUCCESS **`verified=true`**, brain **120ms**, e2e **334ms**, `slo1s=true` |
| 4 | `DEBUG_COMMAND` «тише» | **OK** — `status=PROCESSED`, volume SUCCESS **`verified=true`**, brain **42ms**, e2e **280ms**, `slo1s=true` |
| 5 | `DEBUG_COMMAND` «погода» | **OK** — `status=PROCESSED`, weather SUCCESS **`verified=true`**, brain **810ms**, e2e **4218ms**, `slo1s=false` |
| 6 | `DEBUG_COMMAND` «который час» | **OK** — `status=PROCESSED`, time reply, brain **56ms**, e2e **531ms**, `slo1s=true` |
| 7 | `DEBUG_COMMAND` «сколько времени» | **OK** — `status=PROCESSED`, time reply, brain **88ms**, e2e **514ms**, `slo1s=true` |
| 8 | `DEBUG_COMMAND` «кто ты» | **OK** — `status=PROCESSED`, identity reply, brain **68ms**, e2e **1044ms**, `slo1s=false` |
| 9 | `DEBUG_COMMAND` «шутка» | **OK** — `status=PROCESSED`, joke (LLM path), brain **16303ms**, e2e **16859ms**, `slo1s=false` |
| 10 | `DEBUG_COMMAND` «привет» | **OK** — `status=PROCESSED`, greeting, brain **133ms**, e2e **633ms**, `slo1s=true` |
| 11 | `DEBUG_COMMAND` «что ты умеешь» | **OK** — `status=PROCESSED`, capabilities summary, brain **50ms**, e2e **1586ms**, `slo1s=false` |
| 12 | `DEBUG_COMMAND` «какой сегодня день» | **OK** — `status=PROCESSED`, date reply, brain **86ms**, e2e **1002ms**, `slo1s=false` |
| 13 | `DEBUG_COMMAND` «открой настройки» | **OK** — `status=PROCESSED`, launcher SUCCESS **`verified=true`**, brain **4753ms**, e2e **5156ms**, `slo1s=false` |
| 14 | `DEBUG_COMMAND` «выключи музыку» | **PARTIAL** — `status=NEEDS_CLARIFICATION`, brain **18ms**, e2e **854ms**, `slo1s=true` |
| 15 | `DEBUG_COMMAND` «включи фонарик» (repeat) | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **101ms**, e2e **337ms**, `slo1s=true` |

### Autonomy dry-runs (`DEBUG_AUTONOMY`, `live=false`)

| Action | Result |
|--------|--------|
| `flashlight_on` | **OK** — `autonomy dry-run status=READY_TO_EXECUTE verdict=ALLOW tool=flashlight` |
| `volume_up` | **OK** — `autonomy dry-run status=READY_TO_EXECUTE verdict=ALLOW tool=volume` |

Tool paths with explicit verification on device (log `verified=true`): flashlight (×3), volume (×2), weather, launcher.

## Failures / issues

- **Non-blocking:** 14/15 text commands reached **`PROCESSED`**; **1** («выключи музыку») returned **`NEEDS_CLARIFICATION`** (expected — no music-stop capability).
- **SLO:** Weather, identity, joke (LLM), capabilities, date, settings launch missed 1s (`slo1s=false`) — latency, not Stage 16 MCP wiring failures.
- **«шутка»** LLM-heavy (brain **16303ms**) — functional but slow.
- **MCP registry on device:** empty registry wired in `BrainGraph` → **`mcp=0`** as required; plugin registry still **`plugins=0`**; native catalog unchanged at **24** tools.

## Key numbers

- **McpRegistryTest:** 5 / 5 passed  
- **PluginRegistryTest:** 5 / 5 passed  
- **Brain aiprovider unit tests:** 22 / 22 passed (12 + 10)  
- **Core unit tests:** 364 / 364 passed  
- **Combined unit tests:** 386 / 386 passed  
- **Install:** Success  
- **`mcp=0` on device:** **yes** (`Jarvis.Planner` at VoiceService start)  
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
| Build + unit tests (386, 0 failures) | **PASS** |
| Install + voice warm-up + **`mcp=0`** + **`plugins=0`** + Stage 14/15 planner fields retained | **PASS** |
| Large device command matrix (15 DEBUG_COMMAND + 2 autonomy) | **PASS** (14 PROCESSED + 1 clarification + 2 autonomy dry-runs) |
| **Overall Stage 16 verification** | **PASS** |
