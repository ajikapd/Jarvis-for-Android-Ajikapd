# Stages 19–27 — full device verification (roadmap closure)

**Date:** 2026-10-01  
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
| Mtime | 2026-10-01 00:41 (+0300) |

## Unit tests (JUnit XML totals)

| Suite | Status | Counts (from XML `testsuite` attributes) |
|-------|--------|------------------------------------------|
| `:core:testDebugUnitTest` | **PASS** | **387** tests, **0** failures, **0** errors, **0** skipped (39 suites) |
| ↳ `RoadmapStages19to27Test` | **PASS** | 10 |
| `:brain:testDebugUnitTest` (`com.jarvis.brain.aiprovider.*`) | **PASS** | **28** tests, **0** failures, **0** errors, **0** skipped (3 suites) |
| ↳ `AIProviderTest` | **PASS** | 12 |
| ↳ `AIRouterTest` | **PASS** | 10 |
| ↳ `CloudLLMProviderNaraTest` | **PASS** | 6 |
| **Combined (core + brain aiprovider filter)** | **PASS** | **415** / **415** passed |

XML sources: `core/build/test-results/testDebugUnitTest/TEST-*.xml`, `brain/build/test-results/testDebugUnitTest/TEST-com.jarvis.brain.aiprovider.*.xml`.

## Install / permissions / warm-up

| Item | Result |
|------|--------|
| `adb devices` | 1 device: `dideztgiojzpgapn` **device** |
| `adb install -r …/app-debug.apk` | **Success** (Streamed Install) |
| `pm grant … RECORD_AUDIO` | **granted** |
| `am start … DashboardActivity` | **OK** |
| `DEBUG_VOICE` `action=start` + **16s** warm-up | **OK** — VoiceService start |
| **Planner wiring (Stage 19–27 markers)** | **OK** — see log excerpt below |
| **AI router** | `providers=2 local=1 cloud=1 available=1 router=on cloudKey=set preferCloud=true` |

Startup log (`md_files/.stage19_27_startup.log`):

```text
Jarvis.Planner: aiPlanner=deterministic discovery=on agentLoop=on verifier=on nativeTools=24 liveSafe=3 liveRead=3 plugins=0 mcp=0 filesAgent=on browserAgent=on routines=on memory2=on voiceHybrid=on vision=on proactiveAgent=on remote=on
Jarvis.AIProvider: providers=2 local=1 cloud=1 available=1 router=on cloudKey=set preferCloud=true
```

Earlier Stage 16–18 markers retained: `agentLoop=on`, `verifier=on`, `filesAgent=on`, `browserAgent=on`.

## Companion panel / diagnostics

| Item | Result |
|------|--------|
| UI path | Dashboard bottom nav **«ЕЩЁ»** → settings sub-tab **«Companion»** (`tabSettingsCompanion` → `setupCompanionPanel`) |
| Automated tap | **Not run** — `uiautomator` dump captured MIUI accessibility warning overlay (not Jarvis foreground) |
| Runtime diagnostics API | `BrainGraph.diagnosticsSnapshot()` → `SystemDiagnosticsSnapshot` + `DiagnosticsFormatter` (Stage 27); companion panel also shows `ObservationDiagnosticsBuffer` on refresh |

## Device commands (debug broadcasts)

Quoting: `adb shell "am broadcast … --es command \"…\""` for multi-word Russian phrases.

Runner: `scripts/stage19_27_device_run.sh`. Sequential commands waited for `Jarvis.VoiceService` **e2e first-audio** (or clarification + e2e). Full log: `md_files/.stage19_27_device.log`. TSV: `md_files/.stage19_27_results.tsv`.

| # | Command | Result | status | brain (ms) | e2e (ms) | slo1s |
|---|---------|--------|--------|------------|----------|-------|
| 1 | «включи фонарик» | **OK** | PROCESSED | 175 | 476 | true |
| 2 | «выключи фонарик» | **OK** | PROCESSED | 16 | 267 | true |
| 3 | «громче» | **OK** | PROCESSED | 107 | 294 | true |
| 4 | «тише» | **OK** | PROCESSED | 23 | 227 | true |
| 5 | «погода» | **OK** | PROCESSED | 2694 | 5006 | false |
| 6 | «который час» | **OK** | PROCESSED | 58 | 398 | true |
| 7 | «сколько времени» | **OK** | PROCESSED | 56 | 378 | true |
| 8 | «кто ты» | **OK** | PROCESSED | 100 | 842 | true |
| 9 | «шутка» | **OK** | PROCESSED | 4648 | 5256 | false |
| 10 | «привет» | **OK** | PROCESSED | 41 | 528 | true |
| 11 | «что ты умеешь» | **OK** | PROCESSED | 54 | 1372 | false |
| 12 | «какой сегодня день» | **OK** | PROCESSED | 81 | 903 | true |
| 13 | «какая сегодня дата» | **OK** | PROCESSED | 3020 | 3999 | false |
| 14 | «открой настройки» | **OK** | PROCESSED | 4722 | 5267 | false |
| 15 | «выключи музыку» | **PARTIAL** | NEEDS_CLARIFICATION | 5 | 747 | true |
| 16 | «спасибо» | **OK** | PROCESSED | 49 | 304 | true |
| 17 | «расскажи коротко про квантовую физику» | **OK** | PROCESSED (cloud/LLM path) | 6267 | 7345 | false |
| 18 | «что думаешь про космос» | **OK** | PROCESSED (cloud/LLM path) | 4101 | 7220 | false |
| 19 | «включи фонарик» (repeat) | **OK** | PROCESSED | 75 | 1524 | false |

### Autonomy dry-runs (`DEBUG_AUTONOMY`, `live=false`)

| Action | Result |
|--------|--------|
| `flashlight_on` | **OK** — `autonomy dry-run status=READY_TO_EXECUTE verdict=ALLOW tool=flashlight` |
| `volume_up` | **OK** — `autonomy dry-run status=READY_TO_EXECUTE verdict=ALLOW tool=volume` |

Tool paths with `verified=true` on device (log): flashlight (×3), volume (×2), weather, launcher.

## Command statistics

| Metric | Value |
|--------|-------|
| `DEBUG_COMMAND` runs | **19** |
| `PROCESSED` | **18** |
| `NEEDS_CLARIFICATION` | **1** («выключи музыку» — expected) |
| `FAIL` / timeout | **0** |
| `DEBUG_AUTONOMY` dry-runs | **2** / **2** OK |
| **Total automated checks** | **21** (19 voice + 2 autonomy) |
| e2e `slo1s=true` | **11** / 19 |
| Cloud dialogue (device `cloudKey=set`) | **2** / **2** reached `PROCESSED` |

## Failures / notes

- **Non-blocking:** music stop clarification; weather/settings/LLM/joke exceed 1s SLO — latency, not wiring failures.
- **«расскажи коротко про квантовую физику»:** capability `knowledge` logged `FAILED verified=false` but utterance still **PROCESSED** via dialogue/LLM fallback.
- **Wake pipeline:** unchanged (no edits in this closure run).

## Verdict

| Gate | Result |
|------|--------|
| Unit tests (415) | **PASS** |
| APK install + RECORD_AUDIO + voice warm-up | **PASS** |
| `Jarvis.Planner` Stage 19–27 markers | **PASS** |
| Large device matrix (19 + 2 autonomy) | **PASS** |

**Overall: PASS**
