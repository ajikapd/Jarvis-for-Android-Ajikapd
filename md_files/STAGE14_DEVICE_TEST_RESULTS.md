# Stage 14 — Device / build / test results (first slice)

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
| Size | 209,807,602 bytes (~200 MB) |
| Mtime | 2026-09-30 17:34 |

## Install / permissions

| Item | Result |
|------|--------|
| `adb devices` | 1 device: `dideztgiojzpgapn` **device** |
| `adb install -r …/app-debug.apk` | **Success** (Streamed Install) |
| `pm grant … RECORD_AUDIO` | **granted** |
| `am start … DashboardActivity` | **OK** |
| Cold start + `DEBUG_VOICE` `action=start` | **OK** — `voice: start requested`; ASR loaded **5453ms**; KWS listening |
| **Stage 14 wiring — planner** | `Jarvis.Planner`: **`aiPlanner=deterministic`** `discovery=on` **`agentLoop=on`** **`verifier=on`** **`nativeTools=24`** **`liveSafe=3`** **`liveRead=3`** |
| **Stage 14 wiring — router** | `Jarvis.AIProvider`: `providers=2 local=1 cloud=1 available=1` **`router=on`** |

Startup log excerpt (`/tmp/stage14_startup_capture` via install run):

```
Jarvis.Planner: aiPlanner=deterministic discovery=on agentLoop=on verifier=on nativeTools=24 liveSafe=3 liveRead=3
Jarvis.AIProvider: providers=2 local=1 cloud=1 available=1 router=on
```

## Unit tests (JUnit XML under `*/build/test-results/`)

| Suite | Status | Counts (from XML) |
|-------|--------|-------------------|
| `:brain:testDebugUnitTest` (`com.jarvis.brain.aiprovider.*`) | **PASS** | **22** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `AIProviderTest` | **PASS** | 12 |
| ↳ `AIRouterTest` | **PASS** | 10 |
| `:core:testDebugUnitTest` | **PASS** | **354** tests, **0** failures, **0** errors, **0** skipped |
| ↳ `NativeAndroidToolCatalogTest` | **PASS** | **6** tests, **0** failures |
| ↳ `ProposedActionMapperTest` | **PASS** | **5** tests, **0** failures |
| ↳ `DefaultResultVerifierTest` | **PASS** | **9** tests, **0** failures |
| ↳ `AgentExecutionLoopTest` | **PASS** | **9** tests, **0** failures |
| ↳ `DeterministicAIPlannerTest` | **PASS** | 10 tests, **0** failures |
| **Combined (core + brain filter)** | **PASS** | **376** / **376** passed |

XML sources: `brain/build/test-results/testDebugUnitTest/TEST-com.jarvis.brain.aiprovider.*.xml`, `core/build/test-results/testDebugUnitTest/TEST-com.jarvis.core.nativeandroid.NativeAndroidToolCatalogTest.xml`, + 33 other core suite XMLs under `core/build/test-results/testDebugUnitTest/`.

### NativeAndroidToolCatalogTest (Stage 14)

| Test | Result |
|------|--------|
| `catalogCoversFirstSafe` | pass |
| `readOnlyLiveAllowed` | pass |
| `sensitiveBlockedFromLive` | pass |
| `gatedNotLive` | pass |
| `mapperCoversCatalogIds` | pass |
| `sizeIsStable` | pass (catalog size **24**, ≥ 20) |

Catalog live buckets (unit + device log): **3** `LIVE_DEVICE_SAFE` (`flashlight`, `volume`, `brightness`), **3** `LIVE_READ_ONLY` (`weather`, `knowledge`, `clipboard`).

## Device commands (debug broadcasts)

Quoting used: `adb shell "am broadcast … --es command \"…\""` so full multi-word Russian phrases were delivered.

After install and voice start, sequential `DEBUG_COMMAND` runs waited for `Jarvis.VoiceService` **e2e first-audio** (or clarification + e2e) before the next command. Log excerpt: `/tmp/stage14_device.log`. Runner: `scripts/stage14_device_run.sh`.

| # | Command | Result |
|---|---------|--------|
| 1 | `DEBUG_COMMAND` «включи фонарик» | **OK** — `status=PROCESSED`, cap=`DEVICE_LIGHT`/`flashlight` SUCCESS **`verified=true`**, brain **233ms**, e2e **468ms**, `slo1s=true` |
| 2 | `DEBUG_COMMAND` «выключи фонарик» | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **21ms**, e2e **448ms**, `slo1s=true` |
| 3 | `DEBUG_COMMAND` «громче» | **OK** — `status=PROCESSED`, cap=`DEVICE_AUDIO`/`volume` SUCCESS **`verified=true`**, brain **147ms**, e2e **360ms**, `slo1s=true` |
| 4 | `DEBUG_COMMAND` «тише» | **OK** — `status=PROCESSED`, volume SUCCESS **`verified=true`**, brain **59ms**, e2e **271ms**, `slo1s=true` |
| 5 | `DEBUG_COMMAND` «погода» | **OK** — `status=PROCESSED`, weather SUCCESS **`verified=true`**, brain **992ms**, e2e **4625ms**, `slo1s=false` |
| 6 | `DEBUG_COMMAND` «который час» | **OK** — `status=PROCESSED`, time reply, brain **62ms**, e2e **495ms**, `slo1s=true` |
| 7 | `DEBUG_COMMAND` «сколько времени» | **OK** — `status=PROCESSED`, time reply, brain **108ms**, e2e **485ms**, `slo1s=true` |
| 8 | `DEBUG_COMMAND` «кто ты» | **OK** — `status=PROCESSED`, identity reply, brain **143ms**, e2e **1125ms**, `slo1s=false` |
| 9 | `DEBUG_COMMAND` «шутка» | **OK** — `status=PROCESSED`, joke (LLM path), brain **14158ms**, e2e **14736ms**, `slo1s=false` |
| 10 | `DEBUG_COMMAND` «привет» | **OK** — `status=PROCESSED`, greeting, brain **111ms**, e2e **612ms**, `slo1s=true` |
| 11 | `DEBUG_COMMAND` «что ты умеешь» | **OK** — `status=PROCESSED`, capabilities summary, brain **99ms**, e2e **1669ms**, `slo1s=false` |
| 12 | `DEBUG_COMMAND` «какой сегодня день» | **OK** — `status=PROCESSED`, date reply, brain **88ms**, e2e **1076ms**, `slo1s=false` |
| 13 | `DEBUG_COMMAND` «открой настройки» | **OK** — `status=PROCESSED`, cap=`APP_LAUNCH`/`launcher` SUCCESS **`verified=true`**, brain **6519ms**, e2e **7022ms**, `slo1s=false` |
| 14 | `DEBUG_COMMAND` «выключи музыку» | **PARTIAL** — `status=NEEDS_CLARIFICATION`, brain **2ms**, e2e **869ms**, `slo1s=true` |
| 15 | `DEBUG_COMMAND` «включи фонарик» (repeat) | **OK** — `status=PROCESSED`, flashlight SUCCESS **`verified=true`**, brain **125ms**, e2e **357ms**, `slo1s=true` |

### Autonomy dry-runs (`DEBUG_AUTONOMY`, `live=false`)

| Action | Result |
|--------|--------|
| `flashlight_on` | **OK** — `autonomy dry-run status=READY_TO_EXECUTE verdict=ALLOW tool=flashlight` |
| `volume_up` | **OK** — `autonomy dry-run status=READY_TO_EXECUTE verdict=ALLOW tool=volume` |

Tool paths with explicit verification on device (log `verified=true`): flashlight (×3), volume (×2), weather, launcher.

## Failures / issues

- **Non-blocking:** 14/15 text commands reached **`PROCESSED`**; **1** («выключи музыку») returned **`NEEDS_CLARIFICATION`** (expected — no music-stop capability).
- **SLO:** Weather, identity, joke (LLM), capabilities, date, settings launch missed 1s (`slo1s=false`) — latency, not Stage 14 catalog wiring failures.
- **«шутка»** LLM-heavy (brain **14158ms**) — functional but slow.

## Key numbers

- **NativeAndroidToolCatalogTest:** 6 / 6 passed  
- **ProposedActionMapperTest:** 5 / 5 passed  
- **Brain aiprovider unit tests:** 22 / 22 passed (12 + 10)  
- **Core unit tests:** 354 / 354 passed  
- **Combined unit tests:** 376 / 376 passed  
- **Install:** Success  
- **`nativeTools=24` on device:** **yes** (`Jarvis.Planner` at VoiceService start)  
- **`liveSafe=3` / `liveRead=3` on device:** **yes**  
- **`verifier=on` on device:** **yes**  
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
| Build + unit tests (376, 0 failures) | **PASS** |
| Install + voice warm-up + **`nativeTools=24`** **`liveSafe=3`** **`liveRead=3`** + **`verifier=on`** + **`agentLoop=on`** + **`router=on`** | **PASS** |
| Large device command matrix (15 DEBUG_COMMAND + 2 autonomy) | **PASS** (14 PROCESSED + 1 clarification + 2 autonomy dry-runs) |
| **Overall Stage 14 first-slice verification** | **PASS** |
