package com.jarvis.app

/**
 * Каталог скачиваемых моделей. Все файлы живут под
 * `context.getExternalFilesDir(null)/models/…` — удобно копировать между устройствами.
 */
object ModelCatalog {

    enum class Kind { CHAT_LLM, TOOL_LLM, TTS, ASR, SPEAKER }

    enum class PackageKind {
        /** Один файл → relativePath */
        SINGLE_FILE,
        /** tar.bz2 → распаковать в relativeDir */
        TAR_BZ2,
    }

    data class Entry(
        val id: String,
        val title: String,
        val subtitle: String,
        val kind: Kind,
        val packageKind: PackageKind,
        val url: String,
        /** Для SINGLE_FILE — итоговый путь относительно models/. */
        val relativePath: String,
        /** Для TAR_BZ2 — каталог относительно models/. */
        val relativeDir: String = "",
        /** Маркер READY: файл относительно models/ (для архивов — ключевой файл внутри). */
        val readyMarker: String,
        val sizeBytes: Long,
        /** Ожидаемый размер в пределах ±15% (если >0). */
        val expectedMinBytes: Long = (sizeBytes * 0.85).toLong(),
    )

    val ALL: List<Entry> = listOf(
        Entry(
            id = "chat_qwen3_06",
            title = "Диалог · Qwen3 0.6B (быстрый, рекомендуется)",
            subtitle = "Новое поколение Qwen: ~4× быстрее 1.5B на Helio G99, ~397 MB GGUF Q4_K_M. Для голоса.",
            kind = Kind.CHAT_LLM,
            packageKind = PackageKind.SINGLE_FILE,
            url = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
            relativePath = "llm/chat/Qwen3-0.6B-Q4_K_M.gguf",
            readyMarker = "llm/chat/Qwen3-0.6B-Q4_K_M.gguf",
            sizeBytes = 396_705_472L,
        ),
        Entry(
            id = "chat_qwen05",
            title = "Диалог · Qwen 2.5 0.5B (лёгкий)",
            subtitle = "Запасной быстрый вариант (~398 MB). Чуть слабее Qwen3 0.6B.",
            kind = Kind.CHAT_LLM,
            packageKind = PackageKind.SINGLE_FILE,
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            relativePath = "llm/chat/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            readyMarker = "llm/chat/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 397_808_864L,
        ),
        Entry(
            id = "chat_qwen15",
            title = "Диалог · Qwen 2.5 1.5B (качество)",
            subtitle = "Лучшее качество, но медленно на среднем CPU (~1.1 GB, 15–30 с на фразу).",
            kind = Kind.CHAT_LLM,
            packageKind = PackageKind.SINGLE_FILE,
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            relativePath = "llm/chat/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            readyMarker = "llm/chat/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_736L,
        ),
        Entry(
            id = "tool_functiongemma",
            title = "Команды · FunctionGemma",
            subtitle = "Tool-calling LiteRT (~280 MB)",
            kind = Kind.TOOL_LLM,
            packageKind = PackageKind.SINGLE_FILE,
            url = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/main/mobile-actions_q8_ekv1024.litertlm",
            relativePath = "llm/brain.litertlm",
            readyMarker = "llm/brain.litertlm",
            sizeBytes = 300_000_000L,
            expectedMinBytes = 200_000_000L,
        ),
        Entry(
            id = "tts_dmitri",
            title = "TTS · Piper Dmitri",
            subtitle = "Русский офлайн-голос (~63 MB archive)",
            kind = Kind.TTS,
            packageKind = PackageKind.TAR_BZ2,
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-dmitri-medium.tar.bz2",
            relativePath = "vits-piper-ru_RU-dmitri-medium.tar.bz2",
            relativeDir = "vits-piper-ru_RU-dmitri-medium",
            readyMarker = "vits-piper-ru_RU-dmitri-medium/ru_RU-dmitri-medium.onnx",
            sizeBytes = 63_000_000L,
            expectedMinBytes = 40_000_000L,
        ),
        Entry(
            id = "tts_denis",
            title = "TTS · Piper Denis",
            subtitle = "Русский мужской голос (~21 MB archive)",
            kind = Kind.TTS,
            packageKind = PackageKind.TAR_BZ2,
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-denis-medium-int8.tar.bz2",
            relativePath = "vits-piper-ru_RU-denis-medium-int8.tar.bz2",
            relativeDir = "vits-piper-ru_RU-denis-medium-int8",
            readyMarker = "vits-piper-ru_RU-denis-medium-int8/ru_RU-denis-medium.onnx",
            sizeBytes = 21_100_000L,
            expectedMinBytes = 15_000_000L,
        ),
        Entry(
            id = "tts_ruslan",
            title = "TTS · Piper Ruslan",
            subtitle = "Русский мужской голос (~21 MB archive)",
            kind = Kind.TTS,
            packageKind = PackageKind.TAR_BZ2,
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-ruslan-medium-int8.tar.bz2",
            relativePath = "vits-piper-ru_RU-ruslan-medium-int8.tar.bz2",
            relativeDir = "vits-piper-ru_RU-ruslan-medium-int8",
            readyMarker = "vits-piper-ru_RU-ruslan-medium-int8/ru_RU-ruslan-medium.onnx",
            sizeBytes = 21_100_000L,
            expectedMinBytes = 15_000_000L,
        ),
        Entry(
            id = "asr_ru",
            title = "ASR · Sherpa RU",
            subtitle = "Распознавание речи RU (~45 MB archive)",
            kind = Kind.ASR,
            packageKind = PackageKind.TAR_BZ2,
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16.tar.bz2",
            relativePath = "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16.tar.bz2",
            relativeDir = "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16",
            readyMarker = "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/encoder.int8.onnx",
            sizeBytes = 45_000_000L,
            expectedMinBytes = 25_000_000L,
        ),
        Entry(
            id = VoiceIdentity.MODEL_ID,
            title = "Узнавание голоса · CAM++",
            subtitle = "Для режима «Только мой голос»",
            kind = Kind.SPEAKER,
            packageKind = PackageKind.SINGLE_FILE,
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
            relativePath = "speaker/campplus_zh_en.onnx",
            readyMarker = "speaker/campplus_zh_en.onnx",
            sizeBytes = 28_281_164L,
        ),
    )

    fun byId(id: String): Entry? = ALL.find { it.id == id }

    /**
     * Порядок выбора чат-модели: сначала быстрые (голос), потом качественные.
     * На Helio G99 1.5B даёт ~4 tok/s и 15–30 с на ответ — только как опция.
     */
    val CHAT_MODEL_CANDIDATES: List<String> = listOf(
        "llm/chat/Qwen3-0.6B-Q4_K_M.gguf",
        "llm/chat/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "llm/chat/qwen2.5-1.5b-instruct-q4_k_m.gguf",
    )

    @Deprecated("Use CHAT_MODEL_CANDIDATES", ReplaceWith("CHAT_MODEL_CANDIDATES.first()"))
    const val CHAT_MODEL_REL_PATH = "llm/chat/Qwen3-0.6B-Q4_K_M.gguf"
}
