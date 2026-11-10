package com.jarvis.tools

/**
 * Насколько сказанное название похоже на название приложения, 0..100.
 * Русское и английское написание сводятся к одному звучанию («ватсапп бизнес» ≈ «WhatsApp Business»),
 * небольшие расхождения распознавания речи допускаются.
 */
internal object AppMatcher {
    const val ACCEPT = 70

    private val CYR = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c",
        'ч' to "ch", 'ш' to "sh", 'щ' to "sh", 'ъ' to "", 'ы' to "i", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )
    private val LATIN = listOf(
        "wh" to "v", "w" to "v", "ph" to "f", "ck" to "k", "q" to "k", "x" to "ks", "c" to "k",
        "ou" to "u", "ee" to "i", "oo" to "u", "y" to "i", "z" to "s", "j" to "zh",
    )

    /** Ключ звучания одного слова. */
    fun key(word: String): String {
        val lower = word.lowercase()
        var s = buildString { lower.forEach { ch -> append(CYR[ch] ?: ch.toString()) } }
        s = s.filter { it.isLetterOrDigit() }
        LATIN.forEach { (a, b) -> s = s.replace(a, b) }
        s = s.replace(Regex("(.)\\1+"), "$1")
        if (s.length > 4 && s.endsWith("e")) s = s.dropLast(1)
        return s
    }

    fun tokens(text: String): List<String> =
        text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.map { key(it) }.filter { it.isNotEmpty() }

    /** «Я.Музыка», «Я.Карты»: так Яндекс подписывает свои приложения. */
    private val YANDEX_PREFIX = Regex("^\\s*Я\\s*\\.\\s*")
    private val SPOKEN_YA = Regex("^\\s*я[\\s.]+", RegexOption.IGNORE_CASE)

    fun score(query: String, label: String): Int {
        val q = tokens(SPOKEN_YA.replace(query, "яндекс "))
        val l = tokens(YANDEX_PREFIX.replace(label, "Яндекс "))
        if (q.isEmpty() || l.isEmpty()) return 0
        val whole = similarity(q.joinToString(""), l.joinToString(""))
        // Каждое сказанное слово ищем среди слов названия.
        val perWord = q.map { t -> l.maxOf { similarity(t, it) } }.average()
        val contained = if (l.joinToString("").contains(q.joinToString("")) && q.joinToString("").length >= 3) 0.9 else 0.0
        return (maxOf(whole, perWord * (if (q.size > l.size) 0.85 else 1.0), contained) * 100).toInt()
    }

    /** 1 − расстояние Левенштейна / длина большей строки. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    private val BRANDS = listOf("yandex" to "Яндекс", "google" to "Google", "samsung" to "Samsung", "vkontakte" to "ВК", "mail.ru" to "Mail")

    /** Бренд по имени пакета, если в подписи приложения его нет. */
    fun brandOf(packageName: String): String? =
        BRANDS.firstOrNull { (key, _) -> key in packageName.lowercase() }?.second

    /** Лучшее совпадение из списка (пакет, название), если оно достаточно похоже. */
    fun <T> best(query: String, items: List<Pair<T, String>>, accept: Int = ACCEPT): T? =
        items.map { it.first to score(query, it.second) }
            .filter { it.second >= accept }
            .maxByOrNull { it.second }
            ?.first
}
