package com.jarvis.core.skills

/**
 * Реестр манифестов скиллов. Наполняется при старте приложения из JSON-манифестов.
 * Хранит сорсинг манифестов; сама топ-K выборка — в [SkillRetriever].
 */
class SkillManifestRegistry {

    private val byId = LinkedHashMap<String, SkillManifest>()

    fun register(manifest: SkillManifest) {
        byId[manifest.id] = manifest
    }

    fun unregister(id: String) {
        byId.remove(id)
    }

    fun find(id: String): SkillManifest? = byId[id]

    fun all(): List<SkillManifest> = byId.values.toList()

    fun clear() {
        byId.clear()
    }
}

/**
 * Выбирает top-K релевантных скиллов по простой семантической близости к запросу
 * (JARVIS_BRAIN_ARCHITECTURE §3). Быстрый лёгкий слой: поиск по совпадающим словам
 * (normalized) с весами. Позже заменяется на эмбеддинги (multilingual-e5-small через
 * onnxruntime), но контракт остаётся тем же.
 */
class SkillRetriever(
    private val registry: SkillManifestRegistry,
    private val k: Int = 5,
    private val normalize: (String) -> List<String> = ::defaultNormalize
) {

    private data class Scored(val manifest: SkillManifest, val score: Float)

    /** Возвращает top-K манифестов по убыванию близости. */
    fun retrieve(query: String): List<SkillManifest> = runCatching {
        val qWords = normalize(query)
        if (qWords.isEmpty()) return emptyList()

        val scored = registry.all().map { m ->
            Scored(m, similarity(qWords, normalize(m.searchableText)))
        }
        scored.filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(k)
            .map { it.manifest }
    }.getOrDefault(emptyList())

    private fun similarity(qWords: List<String>, skillWords: List<String>): Float {
        if (skillWords.isEmpty()) return 0f
        // Голые глаголы («включи») не должны давать score=1.0 у фонарика —
        // иначе LLM исполняет неполную команду.
        val contentWords = qWords.filterNot { it in COMMAND_STOPWORDS }
        if (contentWords.isEmpty()) return 0f
        val skillSet = skillWords.toSet()
        var hits = 0
        for (w in contentWords) if (w in skillSet) hits++
        // уникальные совпадения / размер запроса (без стоп-глаголов)
        return hits.toFloat() / contentWords.size
    }

    companion object {
        /** Общие глаголы команд — сами по себе не идентифицируют скилл. */
        private val COMMAND_STOPWORDS = setOf(
            "включи", "включить", "включите", "выключи", "выключить", "выключите",
            "открой", "открыть", "закрой", "закрыть", "запусти", "запустить",
            "сделай", "сделайте", "поставь", "найди", "покажи", "пожалуйста",
        )

        fun defaultNormalize(text: String): List<String> =
            text.lowercase()
                .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 && it.isNotBlank() }
    }
}