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
     * 2. более короткое слово — префикс более длинного и не короче 3 букв
     *    («чай»/«чая», «кофе»/«кофе», «зелёный»/«зелёного»);
     * 3. для слов из 3 букв: «чай/чаем», «чая/чаем» (короткая основа + длинное
     *    окончание) и пара «чая/чаю»; «она/оно» и «кот/код» сюда не попадают;
     * 4. для слов из 4–5 букв: после общего префикса (>= 3) хотя бы один хвост
     *    должен быть окончанием из [WORD_ENDINGS]. Поэтому «люблю/любит»
     *    (хвост «ит»), «живу/живёт» (хвост «ёт») совпадают, а «стол/стон»
     *    («л»/«н»), «банк/бант» («к»/«т») и «город/горох» («д»/«х») — нет;
     * 5. для слов длиннее 5 букв — общий префикс >= 3 и >= 60% длины короткого
     *    («музыка/музыку» 5/6, «зелёный/зелёного» 5/7).
     *
     * Чередование «люблю/любит» (б/бл) отдельным словарём не лечим: общего
     * префикса «люб» и окончания «ит» достаточно.
     *
     * Слова короче 3 букв совпадают только точно.
     */
    fun wordsMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val minLen = minOf(a.length, b.length)
        if (minLen < 3) return false
        var prefix = 0
        while (prefix < minLen && a[prefix] == b[prefix]) prefix++
        if (prefix == minLen) return true // короткое слово целиком входит в длинное

        if (minLen == 3) {
            if (prefix != 2) return false
            if (a.length == b.length) {
                // «чай/чая/чаю», «чая/чаю» — падежные формы одной основы.
                // «она/оно» (а/о) и «кот/код» (т/д) сюда не попадают.
                val ta = a.substring(2)
                val tb = b.substring(2)
                return ta in SHORT_CASE_TAILS && tb in SHORT_CASE_TAILS
            }
            val longer = if (a.length > b.length) a else b
            val tail = longer.substring(2)
            return tail.length >= 2 && tail in WORD_ENDINGS
        }

        if (prefix < 3) return false
        if (minLen <= 5) {
            // Короткие слова: недостаточно общего префикса, нужен морфологический
            // хвост. Иначе «стол/стон», «банк/бант», «город/горох» совпадут.
            return a.substring(prefix) in WORD_ENDINGS ||
                b.substring(prefix) in WORD_ENDINGS
        }
        return prefix.toFloat() / minLen >= 0.6f
    }

    /**
     * Падежные хвосты односложных основ: «чай/чая/чаю», «бой/боя/бою».
     * Намеренно узкий набор — чтобы «она/оно» и «кот/код» не совпадали.
     */
    private val SHORT_CASE_TAILS = setOf("й", "я", "ю")

    /** Окончания, которые могут стоять после общей основы. */
    private val WORD_ENDINGS = setOf(        // существительные/прилагательные
        "а", "я", "о", "е", "ё", "ы", "и", "у", "ю",
        "ой", "ей", "ом", "ем", "ём", "ам", "ям", "ах", "ях", "ов", "ев",
        "ий", "ия", "ию", "ье", "ья",
        "ого", "его", "ому", "ему", "ым", "им", "ых", "их", "ую", "юю",
        "ая", "яя", "ое", "ее",
        // глаголы
        "ешь", "ёшь", "ишь", "шь", "ет", "ёт", "ит", "ем", "им",
        "ете", "ёте", "ите", "ут", "ют", "ат", "ят",
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
