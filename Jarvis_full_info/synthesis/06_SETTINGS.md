# 06 — Настройки (`JarvisSettings`, prefs `jarvis_prefs`)

| Ключ / API | Default | Смысл |
|------------|---------|-------|
| theme | CLASSIC | CLASSIC / ARC / STEALTH / LIGHT |
| persona | CLASSIC | CLASSIC / FRIENDLY / MINIMAL / DEVELOPER |
| wake_enabled | true | Вейк-ворд |
| wake_sensitivity | 2 (high) | 0..2 |
| brain_threshold | 0.6 | Порог уверенности Brain 0.3..0.9 |
| llm_enabled | true | LLM brain |
| adaptive_listening | true | Adaptive mic mode |
| low_power | false | Low power |
| memory_enabled | true | Память |
| chat_profile | BALANCED | FAST / BALANCED / QUALITY |
| chat_preload | true | Preload chat model |
| cloud_api_key | "" | Nara/OpenAI-compatible chat |
| cloud_base_url | (default Nara) | Base URL chat |
| prefer_cloud_llm | true | Prefer cloud when online |
| asr_mode | AUTO | AUTO / CLOUD_PREFERRED / LOCAL_ONLY |
| asr_cloud_base_url / asr_cloud_api_key / asr_cloud_model | optional | Whisper-compatible |
| tts_mode | AUTO | AUTO / CLOUD_PREFERRED / LOCAL_ONLY |
| tts_cloud_* / tts_cloud_voice | optional | Speech API |
| confirm_sensitive | true | Confirm risky actions |

Флаги онбординга/гида: `usage_guide_done_v1` и связанные prefs в `UsageGuideActivity` / Onboarding.

## Где настраивается в UI

- Control Center (More): persona, chat profile, theme, adaptive/low power/sensitive, voice stack stats.  
- Advanced settings / Models / Companion tabs: wake levels, brain slider, cloud keys, ASR/TTS spinners & URLs, model download cards, companion diagnostics.
