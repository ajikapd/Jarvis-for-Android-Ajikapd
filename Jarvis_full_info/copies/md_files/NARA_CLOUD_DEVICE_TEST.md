# NaraRouter cloud LLM — device test

**Date:** 2026-09-30  
**Device:** Xiaomi 23117RA68G (`dideztgiojzpgapn`)  
**Package:** `com.jarvis.assistant`  
**APK:** `app/build/outputs/apk/debug/app-debug.apk` (reinstalled `-r`)

## Device-only configuration

| Item | Status |
|------|--------|
| `cloud_api_key` in `jarvis_prefs.xml` | **configured** (`sk-nry-***`, not stored in repo) |
| `prefer_cloud_llm` | `true` |
| Prefs path | `/data/data/com.jarvis.assistant/shared_prefs/jarvis_prefs.xml` (via `adb shell run-as`) |

**Note:** Existing prefs were merged (theme, wake, overlay, onboarding flags preserved).  
**Profile fix (device only):** `chat_profile` was changed from `QUALITY` → `BALANCED` so Nara uses `agnes-2.5-flash`. With `QUALITY`, cloud calls used `mistral-large`, which NaraRouter rejected (`HTTP 400` / model not available) and triggered local fallback.

## Startup / warm-up

1. `adb shell am force-stop com.jarvis.assistant`
2. `DashboardActivity` started
3. `DEBUG_VOICE` `action=start`, ~16s warm-up

**Jarvis.AIProvider (after warm-up):**

```text
providers=2 local=1 cloud=1 available=2 router=on cloudKey=set preferCloud=true
```

## DEBUG_COMMAND dialogue (LLM)

| # | Command | CloudLLM | VoiceService | Sample reply |
|---|---------|----------|--------------|--------------|
| 1 | «что думаешь про космос» | **ok** `provider=cloud.llm` latency≈5093ms | `PROCESSED`, e2e≈6496ms | Cloud text about космос as infinite mystery / inspiration |
| 2 | «расскажи коротко про квантовую физику» | **ok** latency≈3522ms | `PROCESSED`, e2e≈5946ms | Cloud explanation of quantum physics at atomic scale |

**Jarvis.Dialogue:** `llm-generate` logged for both turns (`loaded=true`).

**Earlier attempt (before `chat_profile` fix):** `Jarvis.CloudLLM` logged `cloud miss status=FAILURE err=CLOUD_ERROR — local fallback` (local replies still `PROCESSED`).

## Result

| Check | Outcome |
|-------|---------|
| **Overall** | **PASS** (with `chat_profile=BALANCED` on device) |
| `cloudKey=set` | **yes** |
| `Jarvis.CloudLLM ok` | **yes** (both dialogue commands) |
| Sample reply | **cloud-generated** Russian prose, `PROCESSED` + e2e audio |

## Security

- API key was written **only** on-device shared prefs; **not** committed or placed in tracked markdown/source.
