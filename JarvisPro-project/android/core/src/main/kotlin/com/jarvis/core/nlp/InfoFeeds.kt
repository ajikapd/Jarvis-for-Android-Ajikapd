package com.jarvis.core.nlp

import kotlin.math.roundToInt

/** Разбор внешних источников: курсы к доллару и заголовки RSS. */
object InfoFeeds {
    private val ITEM = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private val TITLE = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
    private val ENTITIES = mapOf(
        "&quot;" to "\"", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&#39;" to "'", "&apos;" to "'",
    )

    fun headlines(rss: String, limit: Int): List<String> = ITEM.findAll(rss)
        .mapNotNull { TITLE.find(it.groupValues[1])?.groupValues?.get(1) }
        .map(::clean)
        .filter { it.isNotBlank() }
        .take(limit)
        .toList()

    private fun clean(raw: String): String {
        var text = raw.replace("<![CDATA[", "").replace("]]>", "").trim()
        ENTITIES.forEach { (entity, char) -> text = text.replace(entity, char) }
        return text
    }

    /** Значение валюты из ответа вида `"rates":{"RUB":81.6,...}`, единиц за один доллар. */
    fun rate(json: String, code: String): Double? =
        Regex("\"$code\"\\s*:\\s*([0-9.]+)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()

    /** «81 рубль 61 копейка». */
    fun rubles(value: Double): String {
        val kopecks = (value * 100).roundToInt()
        val rub = kopecks / 100
        val kop = kopecks % 100
        return "$rub ${plural(rub, "рубль", "рубля", "рублей")} $kop ${plural(kop, "копейка", "копейки", "копеек")}"
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val tens = n % 100
        val units = n % 10
        return when {
            tens in 11..14 -> many
            units == 1 -> one
            units in 2..4 -> few
            else -> many
        }
    }

    /** Одна валюта: «Евро 91 рубль 65 копеек, сэр.» */
    fun rateReport(json: String, code: String): String? {
        val rub = rate(json, "RUB") ?: return null
        val value = if (code == "USD") rub else rub / (rate(json, code)?.takeIf { it > 0 } ?: return null)
        return "${CURRENCY_NAMES[code] ?: return null} ${rubles(value)}, сэр."
    }

    private val CURRENCY_NAMES = mapOf("USD" to "Доллар", "EUR" to "Евро", "CNY" to "Юань")

    fun ratesReport(json: String): String? {
        val rub = rate(json, "RUB") ?: return null
        val eur = rate(json, "EUR")?.takeIf { it > 0 } ?: return null
        val cny = rate(json, "CNY")?.takeIf { it > 0 } ?: return null
        return "Доллар ${rubles(rub)}. Евро ${rubles(rub / eur)}. Юань ${rubles(rub / cny)}."
    }
}
