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
 *
 * Слова сравниваются не только точно, но и «приближённо» (см. [wordsMatch]):
 * «люблю»~«любит», «чай»~«чая», «живу»~«живёт». Без библиотек и словарей.
 */
object FactScoring {

    fun tokenize(text: String): List<String> = SkillRetriever.defaultNormalize(text)

    /**
     * Считаются ли слова одной леммой. Правило:
     * 1. точное совпадение;
     * 2. более короткое слово — префикс более длинного, и оно не короче 3 букв
     *    («чай»/«чая», «кофе»/«кофе», «зелёный»/«зелёного»);
     * 3. иначе нужен общий префикс >= 3 букв и >= 60% длины короткого слова
     *    («люблю»/«любит»: 3/5 = 0.6; «музыка»/«музыку»: 5/6; «живу»/«живёт»: 3/4).
     *
     * Чередование «люблю/любит» (б/бл) отдельным словарём не лечим: общего
     * префикса «люб» достаточно, чтобы пара прошла правило 3.
     *
     * Слова короче 3 букв совпадают только точно или как полный префикс:
     * «кот»/«код» (2/3), «сон»/«сын» (1/3), «она»/«оно» (2/3) не совпадают.
     */
    fun wordsMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val minLen = minOf(a.length, b.length)
        if (minLen < 3) return false
        var prefix = 0
        while (prefix < minLen && a[prefix] == b[prefix]) prefix++
        if (prefix == minLen) return true // короткое слово целиком входит в длинное
        // «чай»→«чая»/«чаю»: краткая форма на «й» теряет й перед окончанием.
        val yWord = when {
            a.endsWith('й') && a.length >= 3 -> a
            b.endsWith('й') && b.length >= 3 -> b
            else -> null
        }
        if (yWord != null && prefix == yWord.length - 1) {
            val other = if (yWord == a) b else a
            if (other.substring(prefix) in WORD_ENDINGS) return true
        }
        // «чая»→«чаем», «музыка»→«музыку»: короткая основа + окончание в длинном слове.
        // Работает только при разной длине, поэтому «кот»/«код» и «она»/«оно» не совпадают.
        if (a.length != b.length) {
            val longer = if (a.length > b.length) a else b
            val shorter = if (a.length > b.length) b else a
            if (prefix >= 2 && prefix == shorter.length - 1) {
                if (longer.substring(prefix) in WORD_ENDINGS) return true
            }
        }
        if (minLen <= 3) return false
        return prefix >= 3 && prefix.toFloat() / minLen >= 0.6f
    }

    /** Окончания, которые могут стоять после общей основы. */
    private val WORD_ENDINGS = setOf(
        "а", "я", "у", "ю", "е", "ы", "и", "о",
        "ой", "ей", "ом", "ем", "ам", "ям", "ах", "ях", "ов", "ев",
        "ий", "ия", "ию", "ье", "ья",
    )

    /** Доля слов запроса, найденных в факте (0..1). */
    fun score(queryWords: List<String>, factWords: List<String>): Float {
        if (queryWords.isEmpty() || factWords.isEmpty()) return 0f
        val hits = queryWords.count { q -> factWords.any { f -> wordsMatch(q, f) } }
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
