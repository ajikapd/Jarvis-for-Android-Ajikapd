# Stage 9 — Device / build / test results

**Date:** 2026-09-30  
**JAVA_HOME:** `/usr/lib/jvm/java-17-openjdk-amd64`  
**Device:** `dideztgiojzpgapn` (Xiaomi 23117RA68G / emerald_global)  
**Package:** `com.jarvis.assistant`

## Build

| Item | Result |
|------|--------|
| `./gradlew :app:assembleDebug` | **SUCCESS** (UP-TO-DATE, ~5s) |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Size | ~200 MB (209,807,425 bytes) |
| Mtime | 2026-09-30 16:37 |

## Install / permissions

| Item | Result |
|------|--------|
| `adb devices` | 1 device connected |
| `adb install -r` | **Success** |
| `pm grant … RECORD_AUDIO` | **granted=true** |

## Unit tests

| Suite | Status | Counts |
|-------|--------|--------|
| `:core:testDebugUnitTest` | **PASS** | **320** tests, **0** failures, **0** errors, **0** skipped |
| `:app:testDebugUnitTest` | Task exists; **NO-SOURCE** | **0** tests (no app unit-test sources) |

## Smoke (`scripts/smoke_phase0.sh`)

| Item | Result |
|------|--------|
| Script exists | Yes |
| Exit code | **0** |
| Behavior | Writes Phase 0 checklist only (manual PASS/FAIL fill-in). Not an automated executor. |
| Output | Checklist written to `/tmp/jarvis_smoke_phase0_results.md` |

Automated voice/phrase latency SLO checks were **not** run by this script (by design).

## Device commands (debug broadcasts)

Launched `DashboardActivity`, started voice via `DEBUG_VOICE`, then:

| # | Action | Result |
|---|--------|--------|
| 1 | `DEBUG_COMMAND` «включи фонарик» | **OK** — `status=PROCESSED`, reply «Включён.», e2e first-audio **507ms**, `slo1s=true` |
| 2 | `DEBUG_COMMAND` «какая погода» | **OK** — `status=PROCESSED`, Moscow weather reply (~16°), brain **795ms**, e2e first-audio **3462ms**, `slo1s=false` (TTS-heavy) |
| 3 | `DEBUG_AUTONOMY` `flashlight_on` `live=false` | **OK** — dry-run `status=READY_TO_EXECUTE`, `verdict=ALLOW`, `tool=flashlight` |
| 4 | `DEBUG_COMMAND` «выключи фонарик» | **OK** — `status=PROCESSED`, reply «Выключен.», e2e first-audio **419ms**, `slo1s=true` |

**Note:** First attempt used incorrect shell quoting (multi-word Russian commands truncated to first word → `NEEDS_CLARIFICATION`). Retried with `adb shell "… --es command \"…\""` — full phrases delivered correctly.

## Failures / issues

- None blocking. Build, install, core unit tests, and correctly-quoted device commands all succeeded.
- `:app:testDebugUnitTest` has no sources (not a failure).
- `smoke_phase0.sh` only scaffolds a manual checklist.
- Weather e2e missed 1s SLO due to TTS (~2.7s), not brain latency.

## Key numbers

- **Core unit tests:** 320 / 320 passed  
- **App unit tests:** 0 (NO-SOURCE)  
- **Flashlight on first-audio:** 507 ms (slo1s=true)  
- **Flashlight off first-audio:** 419 ms (slo1s=true)  
- **Weather brain:** 795 ms; **e2e first-audio:** 3462 ms  
- **Autonomy dry-run:** ALLOW / READY_TO_EXECUTE  
