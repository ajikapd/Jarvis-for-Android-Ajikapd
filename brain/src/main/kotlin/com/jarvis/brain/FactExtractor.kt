package com.jarvis.brain

import com.jarvis.core.privacy.SensitivityClassifier

/**
 * Лёгкий экстрактор фактов (JARVIS_BRAIN_ARCHITECTURE §4.2): решает, стоит ли
 * запомнить устойчивый факт о пользователе из фразы, и очищает его от вводных
 * конструкции («запомни, что …»).
 *
 * Правила намеренно простые и проверяемые:
 * 1. Маркеры факта — прежний список ([factMarkers]); дополнительно явная
 *    команда «запомни/запиши/не забудь/имей в виду/remember …» считается
 *    намерением запомнить.
 * 2. Вводная конструкция срезается вместе с «что/that».
 * 3. Узкий набор шаблонов переводит «я люблю X» в «Пользователь любит X»;
 *    всё остальное сохраняется очищенной фразой как есть.
 * 4. Если в фразе есть пароль/код/пин/cvv/ключ/токен/secret или
 *    [SensitivityClassifier.classify] == SECRET — отказ ([REFUSAL]),
 *    без записи на диск.
 */
object FactExtractor {

    /** Короткий вежливый отказ вместо подтверждения «запомнил». */
    const val REFUSAL: String = "Такое я не запоминаю."

    sealed interface Decision {
        data class Save(val text: String) : Decision
        data object Refused : Decision
    }

    private val factMarkers = listOf(
        "люблю", "предпочитаю", "ненавижу", "не люблю", "мне нравится",
        "мой", "моя", "моё", "мои", "у меня", "я обычно", "обычно я",
        "всегда", "никогда", "не хочу", "хочу", "напомни", "мой будильник",
        "мне не нравится", "поставь на", "я встаю в", "я ложусь в",
        "день рождения", "родился", "живу в", "работаю", "учусь в"
    )

    /** «запомни», «запомни, что», «не забудь что», «имей в виду», «remember that». */
    private val leadInRegex = Regex(
        "^(?:запомни|запиши|не\\s+забудь|имей\\s+в\\s+виду|remember)" +
            "(?:\\s*,?\\s*(?:что|о\\s+том,?\\s*что|that))?[\\s,:;.!—–-]*",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Вопросительные слова. Проверяется только ПЕРВОЕ слово фразы (после
     * вводной «запомни …»): «запомни что я люблю когда идёт дождь» и
     * «я живу там, где тепло» вопросами не считаются.
     */
    private val questionWords = setOf(
        // RU
        "что", "кто", "где", "когда", "почему", "зачем", "как",
        "какой", "какая", "какое", "какие", "какого", "какому", "каким",
        "каком", "какую", "каких",
        "чей", "чья", "чьё", "чье", "чьи", "сколько", "куда", "откуда",
        "ли", "ль",
        // EN
        "what", "who", "whom", "whose", "where", "when", "why", "how", "which",
        "is", "are", "am", "was", "were", "do", "does", "did",
        "can", "could", "will", "would", "should", "shall", "may", "might",
        "have", "has", "had",
    )

    /** Слова, которые превращают «запомни …» в отказ. */
    private val secretWordRegex = Regex(
        "(?<![а-яёa-z0-9])(?:парол[а-яё]*|код[а-яё]*|пин[а-яё]*|cvv|cvc|" +
            "ключ[а-яё]*|токен[а-яё]*|secret|password|passcode|otp|pin|token|api[- ]?key)" +
            "(?![а-яёa-z0-9])",
        RegexOption.IGNORE_CASE,
    )

    private class Template(val regex: Regex, val build: (String) -> String)

    /** Только надёжные шаблоны; порядок важен («не люблю» раньше «люблю»). */
    private val templates: List<Template> = listOf(
        Template(Regex("^(?:я\\s+)?не\\s+люблю\\s+(.+)$", RegexOption.IGNORE_CASE)) {
            "Пользователь не любит $it"
        },
        Template(Regex("^(?:я\\s+)?люблю\\s+(.+)$", RegexOption.IGNORE_CASE)) {
            "Пользователь любит $it"
        },
        Template(Regex("^(?:я\\s+)?предпочитаю\\s+(.+)$", RegexOption.IGNORE_CASE)) {
            "Пользователь предпочитает $it"
        },
        Template(Regex("^(?:я\\s+)?живу\\s+в\\s+(.+)$", RegexOption.IGNORE_CASE)) {
            "Пользователь живёт в $it"
        },
        Template(Regex("^(?:я\\s+)?работаю\\s+в\\s+(.+)$", RegexOption.IGNORE_CASE)) {
            "Пользователь работает в $it"
        },
        Template(Regex("^(?:я\\s+)?работаю\\s+(.+)$", RegexOption.IGNORE_CASE)) {
            "Пользователь работает $it"
        },
        Template(Regex("^меня\\s+зовут\\s+(.+)$", RegexOption.IGNORE_CASE)) {
            "Пользователя зовут $it"
        },
    )

    /**
     * @return решение по фразе: сохранить (с очищенным текстом), отказать
     *         (секрет) или null — запоминать нечего.
     */
    fun decide(requestText: String): Decision? {
        val raw = requestText.trim()
        if (raw.isEmpty()) return null

        val lead = leadInRegex.find(raw)
        val hadLeadIn = lead != null
        var body = if (lead != null) raw.substring(lead.range.last + 1) else raw
        body = body.trim().trim(',', ':', ';', '.', '!', '?', '—', '–', '-', ' ')

        val lower = raw.lowercase()
        val hasMarker = factMarkers.any { lower.contains(it) }
        val hasTemplate = templates.any { it.regex.matches(body) }
        // Вопрос — не факт: сохранять нечего, но и отказом это не считаем.
        if (isQuestionForm(raw, body)) return null
        if (!hadLeadIn && !hasMarker && !hasTemplate) return null
        if (body.length < 3) return null

        if (isSecretPhrase(body) || isSecretPhrase(raw)) return Decision.Refused

        val text = capitalize(applyTemplate(body))
        return if (text.length in 3..240) Decision.Save(text) else null
    }

    /** Совместимость с прежним API: текст факта или null, если не сохраняем. */
    fun extract(requestText: String): String? =
        (decide(requestText) as? Decision.Save)?.text

    /** true — фразу нужно отклонить как секрет. */
    fun isSecretPhrase(text: String): Boolean =
        secretWordRegex.containsMatchIn(text) ||
            SensitivityClassifier.classify(text) == com.jarvis.core.companion.Sensitivity.SECRET

    /**
     * Вопрос ли это: либо исходная фраза кончается на «?», либо первое слово
     * тела (после срезанной вводной) — вопросительное. Проверяется именно
     * первое слово: «люблю когда идёт дождь» и «живу там, где тепло» — факты.
     */
    fun isQuestionForm(raw: String, body: String = raw): Boolean {
        if (raw.trimEnd().endsWith("?")) return true
        val tokens = body.trim()
            .split(Regex("\\s+"))
            .map { it.lowercase().trim(',', ':', ';', '.', '!', '?', '—', '–', '-', '«', '»', '"', '\'') }
            .filter { it.isNotEmpty() }
        val first = tokens.firstOrNull() ?: return false
        // «ли»/«ль» — частица, она может стоять не первым словом («люблю ли я чай»).
        if (tokens.any { it == "ли" || it == "ль" }) return true
        return first in questionWords
    }

    private fun applyTemplate(body: String): String {
        for (t in templates) {
            val m = t.regex.find(body) ?: continue
            val tail = m.groupValues[1].trim()
            if (tail.isEmpty()) continue
            return t.build(tail)
        }
        return body
    }

    private fun capitalize(text: String): String =
        if (text.isEmpty()) text else text[0].uppercaseChar() + text.substring(1)
}
