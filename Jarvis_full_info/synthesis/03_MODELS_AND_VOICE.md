# 03 — Модели и голосовой стек (текущее)

## Где лежат модели на устройстве

Каталог: `context.getExternalFilesDir(null)/models/…`  
(удобно копировать между устройствами вместе с APK).

Скачивание/статус: `ModelManager` + UI «Модели» (`ModelCatalog`).

---

## Каталог скачиваемых моделей (`ModelCatalog`)

| id | Название | Kind | Размер (каталог) | Путь / маркер |
|----|----------|------|------------------|---------------|
| `chat_qwen3_06` | Диалог · Qwen3 0.6B Q4_K_M (рекомендуется для голоса) | CHAT_LLM | ~397 MB | `llm/chat/Qwen3-0.6B-Q4_K_M.gguf` |
| `chat_qwen05` | Диалог · Qwen 2.5 0.5B | CHAT_LLM | ~398 MB | `llm/chat/qwen2.5-0.5b-instruct-q4_k_m.gguf` |
| `chat_qwen15` | Диалог · Qwen 2.5 1.5B | CHAT_LLM | ~1.1 GB | `llm/chat/qwen2.5-1.5b-instruct-q4_k_m.gguf` |
| `tool_functiongemma` | Команды · FunctionGemma LiteRT | TOOL_LLM | ~280–300 MB | `llm/brain.litertlm` |
| `tts_dmitri` | TTS · Piper Dmitri (tar.bz2) | TTS | ~63 MB archive | `vits-piper-ru_RU-dmitri-medium/…onnx` |
| `asr_ru` | ASR · Sherpa RU streaming zipformer | ASR | ~45 MB archive | `…/encoder.int8.onnx` |

Порядок выбора chat GGUF: Qwen3 0.6B → Qwen2.5 0.5B → Qwen2.5 1.5B.

Источники URL: HuggingFace (Qwen / LiteRT FunctionGemma), GitHub k2-fsa/sherpa-onnx releases (ASR/TTS archives).

### Runtime движки моделей

| Роль | Движок |
|------|--------|
| Local chat GGUF | `llama-android` (`dev.ffmpegkit-maintained:llama-android`) через `LlamaChatLlmEngine` |
| Tool/brain LiteRT | LiteRT-LM (`litertlm-android`) / FunctionGemma path |
| Local ASR | sherpa-onnx streaming (`com.github.k2-fsa.sherpa-onnx`) |
| Local TTS | sherpa-onnx OfflineTts Piper VITS |
| Wake/KWS | openWakeWord ONNX (`onnxruntime-android`) |
| Cloud chat | NaraRouter / OpenAI-compatible HTTP (ключ в prefs) |
| Cloud ASR | Android `SpeechRecognizer` **или** OpenAI-compatible Whisper `/v1/audio/transcriptions` |
| Cloud TTS | Android `TextToSpeech` **или** OpenAI-compatible `/v1/audio/speech` |

Nara chat gateway **не** предоставляет ASR/TTS endpoint (используется только для LLM).

---

## Voice pipeline (факт)

```
AudioCapture (PCM float, 16 kHz mono)
   ↓
VoiceEngine
   ├── Wake/KWS: OwwWakeSpotter / openWakeWord (не переписывался в V2-08/09)
   ├── Command after wake: local Sherpa (+ optional refine)
   └── Live/continuous: HybridAsrController (V2-08)
          local streaming Sherpa  OR  cloud backend + mic handoff
```

Playback TTS:

```
ChatStreamPipeline (V2-07 sentence stream)
  → JarvisSpeaker
  → HybridTtsBridge / HybridTtsSynthesizer (V2-09)
  → LocalPiper  |  AndroidSystemTts  |  OpenAiSpeech
  → TtsPlayer (единственный playback owner)
```

### Режимы ASR (`JarvisSettings.asrMode`)

`AUTO` | `CLOUD_PREFERRED` | `LOCAL_ONLY`

### Режимы TTS (`JarvisSettings.ttsMode`)

`AUTO` | `CLOUD_PREFERRED` | `LOCAL_ONLY`

Политика роутеров (StreamingAsrRouter / StreamingTtsRouter): offline/privacy/cooldown → local; fallback на local при cloud fail (кроме CANCELLED).

### Streaming

- V2-07: sentence-level streaming в TTS queue (ранний старт речи).  
- V2-09: backends честно помечают `ttsStreaming=false` (полный PCM utterance); early speech — за счёт sentence queue.  
- Cloud Whisper: rolling interim windows ~900 ms.

---

## Cloud LLM (чат)

- Ключ: prefs `cloud_api_key` (router.bynara.id / совместимый).  
- `prefer_cloud_llm` (default true).  
- Профили чата → cloud model id (`NaraRouterDefaults`):
  - FAST / BALANCED → `agnes-2.5-flash`
  - QUALITY → `mistral-medium-3-5`
- Fallback: local GGUF при miss/offline.

Persona влияет на:

- `JarvisSystemPrompt.personaTone` (system);
- templates в `DialogueResponder`;
- compact role prefix в `LlamaChatLlmEngine`;
- cloud system append в `CloudLLMProvider`.

Значения персоны: `CLASSIC` | `FRIENDLY` | `MINIMAL` | `DEVELOPER`.

---

## Память

- **Live path:** `LongTermMemory` (факты, retrieve в chat context).  
- **Memory 2.0 / Memory2Store:** существует в core/BrainGraph, по аудиту **не** основной chat path.  
- Companion memory / behavior learning — отдельные stores для companion UI.

---

## Зависимости голосового/AI стека (gradle highlights)

- `sherpa-onnx`, `onnxruntime-android`
- `llama-android`
- `litertlm-android` (+ gson, kotlin-reflect)
- ML Kit text-recognition (screen-control OCR assets)
- androidx media, lifecycle-process
