package com.jarvis.core.storage

import com.jarvis.core.companion.Sensitivity
import com.jarvis.core.privacy.SensitivityClassifier
import com.jarvis.core.skills.SkillRetriever

/**
 * Правила записи фактов на диск. Чистый Kotlin — проверяется JVM-тестами.
 *
 * Инвариант Task 4: в БД не попадает ничего, для чего
 * [SensitivityClassifier.classify] == SECRET, и любой текст перед записью
 * проходит [SensitivityClassifier.mask].
 */
object FactPolicies {

    /** Минимальная длина осмысленного факта (как было в LongTermMemory). */
    const val MIN_LENGTH: Int = 3

    /** Верхняя граница хранимого факта. */
    const val MAX_LENGTH: Int = 240

    /** Флаг одноразовой миграции старой `jarvis_memory.db`. */
    const val MIGRATED_FLAG: String = "migrated_facts_v1"

    /** Имя старой БД (остаётся на диске как бэкап). */
    const val LEGACY_DB_NAME: String = "jarvis_memory.db"

    /** Source демо-фактов: их НЕ переносим и не считаем пользовательскими. */
    const val SEED_SOURCE: String = "seed"

    /**
     * Ключ дедупликации: регистр, повторные/краевые пробелы и конечная
     * пунктуация не делают факт новым.
     */
    fun normalizeForDedup(text: String): String {
        var s = text.lowercase().replace(Regex("\\s+"), " ").trim()
        s = s.trimEnd('.', ',', '!', '?', ';', ':', '…', ' ')
        return s.replace('ё', 'е')
    }

    /** Факт проходит по длине и не пустой. */
    fun isStorable(text: String): Boolean {
        val t = text.trim()
        return t.length in MIN_LENGTH..MAX_LENGTH
    }

    /** Строки source='seed' — демо-данные, мигрировать их нельзя. */
    fun shouldMigrateSource(source: String?): Boolean =
        source?.trim()?.lowercase() != SEED_SOURCE

    /** Текст, который реально уйдёт на диск: всегда masked. */
    fun diskText(text: String): String = SensitivityClassifier.mask(text)

    fun sensitivityOf(text: String): Sensitivity = SensitivityClassifier.classify(text)

    /** Секрет нельзя хранить ни как факт, ни как историю. */
    fun isSecret(text: String): Boolean = sensitivityOf(text) == Sensitivity.SECRET
}

/**
 * Лексический скоринг фактов — прежняя логика LongTermMemory.retrieve,
 * вынесенная в чистую функцию ради JVM-тестов.
 */
object FactScoring {

    fun tokenize(text: String): List<String> = SkillRetriever.defaultNormalize(text)

    /** Доля слов запроса, найденных в факте (0..1). */
    fun score(queryWords: List<String>, factWords: List<String>): Float {
        if (queryWords.isEmpty() || factWords.isEmpty()) return 0f
        val factSet = factWords.toSet()
        val hits = queryWords.count { it in factSet }
        return hits.toFloat() / queryWords.size
    }

    /** Отбирает top-N по score > 0, сохраняя порядок при равном скоре. */
    fun <T> topN(query: String, items: List<T>, limit: Int, textOf: (T) -> String): List<T> {
        val qWords = tokenize(query)
        if (qWords.isEmpty() || limit <= 0) return emptyList()
        return items
            .map { it to score(qWords, tokenize(textOf(it))) }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}
