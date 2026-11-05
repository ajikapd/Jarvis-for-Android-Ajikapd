package com.jarvis.core.nlp

/** Числа словами и цифрами из распознанной речи: ASR обычно пишет «двадцать пять», а не 25. */
object RuNumbers {
    private val UNITS = mapOf(
        "ноль" to 0, "один" to 1, "одна" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17,
        "восемнадцать" to 18, "девятнадцать" to 19,
    )
    private val TENS = mapOf(
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60,
        "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90,
    )
    private val HUNDREDS = mapOf(
        "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400, "пятьсот" to 500,
    )

    /** Число, заканчивающееся словом с индексом [end], или null. */
    fun endingAt(words: List<String>, end: Int): Int? {
        val last = words.getOrNull(end) ?: return null
        last.toIntOrNull()?.let { return it }
        var i = end
        var sum = 0
        var found = false
        var lastClass = 0
        while (i >= 0) {
            val w = words[i]
            val (value, cls) = when {
                w in UNITS -> UNITS.getValue(w) to 1
                w in TENS -> TENS.getValue(w) to 2
                w in HUNDREDS -> HUNDREDS.getValue(w) to 3
                else -> break
            }
            if (cls <= lastClass) break
            sum += value
            lastClass = cls
            found = true
            i--
        }
        return if (found) sum else null
    }

    /** Значение перед первым словом из [units]: «на двадцать пять минут» → 25. */
    fun before(words: List<String>, units: Set<String>): Int? {
        val idx = words.indexOfFirst { it in units }
        if (idx <= 0) return null
        return endingAt(words, idx - 1)
    }
}
