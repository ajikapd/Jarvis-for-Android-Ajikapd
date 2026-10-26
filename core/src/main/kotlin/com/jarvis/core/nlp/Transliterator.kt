package com.jarvis.core.nlp

/**
 * Приводит русские и латинские названия к общей форме сравнения:
 * нижний регистр + транслитерация кириллицы в латиницу + удаление
 * разделителей. Позволяет матчить «телеграм» ↔ «Telegram».
 */
object Transliterator {

    fun toLatin(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value.lowercase()) {
            sb.append(CYRILLIC_TO_LATIN[ch] ?: ch)
        }
        return sb.toString()
    }

    /** Нормализованная форма для нечёткого сравнения имён. */
    fun normalize(value: String): String =
        toLatin(value).replace(Regex("[^\\p{L}\\p{N}]"), "")

    /**
     * Ключ сравнения: нормализует, отсекает распространённые русские
     * падежные окончания, транслитерирует и приводит распространённые
     * бренды к канонической латинской форме («ютуб» → «youtube»).
     */
    fun compareKey(value: String): String {
        val base = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val normalized = normalize(stripEnding(base))
        return BRAND_ALIASES[normalized] ?: normalized
    }

    private fun stripEnding(base: String): String {
        for (e in RU_ENDINGS) {
            if (base.length > e.length + 2 && base.endsWith(e)) {
                return base.dropLast(e.length)
            }
        }
        return base
    }

    private val RU_ENDINGS = listOf(
        "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими",
        "ей", "ой", "ам", "ям", "ах", "ях", "ом", "ем", "ов", "ев",
        "ий", "ый", "ая", "ое", "ые",
        "а", "я", "е", "и", "у", "ю", "ы", "о", "ь"
    )

    /**
     * Транслитерированные стемы русских названий → канонический вид.
     * Решает несовпадения вроде «ютуб» ↔ «YouTube», «ватсап» ↔ «WhatsApp».
     */
    private val BRAND_ALIASES: Map<String, String> = mapOf(
        "yutub" to "youtube",
        "vatsap" to "whatsapp",
        "votsap" to "whatsapp",
        "feysbuk" to "facebook",
        "vkontakte" to "vk",
        "gugl" to "google",
        "tvitter" to "twitter",
        "kamer" to "camera",
        "galere" to "gallery",
        "nastroyk" to "settings",
        "nastroek" to "settings"
    )

    private val CYRILLIC_TO_LATIN: Map<Char, String> = buildMap {
        put('а', "a"); put('б', "b"); put('в', "v"); put('г', "g"); put('д', "d")
        put('е', "e"); put('ё', "e"); put('ж', "zh"); put('з', "z"); put('и', "i")
        put('й', "y"); put('к', "k"); put('л', "l"); put('м', "m"); put('н', "n")
        put('о', "o"); put('п', "p"); put('р', "r"); put('с', "s"); put('т', "t")
        put('у', "u"); put('ф', "f"); put('х', "h"); put('ц', "ts"); put('ч', "ch")
        put('ш', "sh"); put('щ', "sch"); put('ъ', ""); put('ы', "y"); put('ь', "")
        put('э', "e"); put('ю', "yu"); put('я', "ya")
    }
}
