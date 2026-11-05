package com.jarvis.core.nlp

/**
 * Текст ответа → то, что синтезатор прочитает правильно: время, числа с падежами единиц,
 * проценты, градусы, названия латиницей. Эмодзи и разметка убираются: их не произносят.
 */
object SpeechTextNormalizer {
    private val ONES_M = listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val TEENS = listOf(
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
        "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    )
    private val TENS = listOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val HUNDREDS = listOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")
    private val ORDINAL_DAY = listOf(
        "", "первое", "второе", "третье", "четвёртое", "пятое", "шестое", "седьмое", "восьмое", "девятое", "десятое",
        "одиннадцатое", "двенадцатое", "тринадцатое", "четырнадцатое", "пятнадцатое", "шестнадцатое", "семнадцатое",
        "восемнадцатое", "девятнадцатое", "двадцатое", "двадцать первое", "двадцать второе", "двадцать третье",
        "двадцать четвёртое", "двадцать пятое", "двадцать шестое", "двадцать седьмое", "двадцать восьмое",
        "двадцать девятое", "тридцатое", "тридцать первое",
    )
    private val YEAR_ORDINAL = mapOf(
        0 to "", 1 to "первого", 2 to "второго", 3 to "третьего", 4 to "четвёртого", 5 to "пятого", 6 to "шестого",
        7 to "седьмого", 8 to "восьмого", 9 to "девятого", 10 to "десятого", 20 to "двадцатого", 30 to "тридцатого",
    )

    private const val MONTHS = "января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря"

    /** Род и формы «1 / 2–4 / 5+»: единица определяет окончание числа и слово. */
    private class Unit(val feminine: Boolean, val one: String, val few: String, val many: String)

    private val UNITS = listOf(
        Regex("(?:мин|минут[аыу]?)") to Unit(true, "минута", "минуты", "минут"),
        Regex("(?:сек|секунд[аыу]?)") to Unit(true, "секунда", "секунды", "секунд"),
        Regex("(?:ч|час(?:а|ов)?)") to Unit(false, "час", "часа", "часов"),
        Regex("(?:ГБ|Гб|гигабайт(?:а|ов)?)") to Unit(false, "гигабайт", "гигабайта", "гигабайт"),
        Regex("(?:МБ|Мб|мегабайт(?:а|ов)?)") to Unit(false, "мегабайт", "мегабайта", "мегабайт"),
        Regex("км/ч") to Unit(false, "километр в час", "километра в час", "километров в час"),
        Regex("км") to Unit(false, "километр", "километра", "километров"),
        Regex("м/с") to Unit(false, "метр в секунду", "метра в секунду", "метров в секунду"),
        Regex("(?:руб|₽)") to Unit(false, "рубль", "рубля", "рублей"),
    )

    private val WORDS = listOf(
        Regex("(?i)wi[‑-]?fi") to "вай-фай",
        Regex("(?i)bluetooth") to "блютуз",
        Regex("(?i)telegram") to "телеграм",
        Regex("(?i)youtube") to "ютуб",
        Regex("(?i)whatsapp") to "ватсап",
        Regex("(?i)google") to "гугл",
        Regex("(?i)android") to "андроид",
        Regex("(?i)\\bjarvis\\b") to "джарвис",
    )

    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{200D}]")
    private val MARKUP = Regex("[*_#`~]+")
    private val TIME = Regex("(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])")
    private val DATE = Regex("(?<!\\d)(\\d{1,2})\\s+($MONTHS)(?:\\s+(\\d{4})(?:\\s*(?:года|г\\.?))?)?")
    private val PERCENT = Regex("(?<![\\d,.])(-?)(\\d{1,6})(?:[,.](\\d))?\\s*%")
    private val DEGREE = Regex("(?<![\\d,.])([-−]?)(\\d{1,3})\\s*°\\s*[CС]?")
    private val NUMBER = Regex("(?<![\\p{L}\\d,.])(-|−)?(\\d{1,9})(?![\\d\\p{L}])")

    fun forSpeech(text: String): String {
        var s = EMOJI.replace(text, "")
        s = MARKUP.replace(s, "")
        s = s.replace("×", " умножить на ").replace("÷", " разделить на ").replace("≈", " примерно ").replace(" = ", " равно ")
        s = s.replace("—", ",").replace(" - ", ", ").replace("…", ".")
        for ((rx, word) in WORDS) s = rx.replace(s, word)
        s = TIME.replace(s) { time(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        s = DATE.replace(s) { date(it) }
        s = PERCENT.replace(s) { percent(it) }
        s = DEGREE.replace(s) { degrees(it) }
        for ((rx, unit) in UNITS) {
            s = Regex("(?<![\\p{L}\\d,.])(\\d{1,6})\\s*${rx.pattern}(?![\\p{L}])").replace(s) { m ->
                counted(m.groupValues[1].toLong(), unit)
            }
        }
        s = NUMBER.replace(s) { m ->
            val minus = if (m.groupValues[1].isNotEmpty()) "минус " else ""
            minus + cardinal(m.groupValues[2].toLong())
        }
        return s.replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.!?])"), "$1").replace(",,", ",").trim()
    }

    fun cardinal(n: Long, feminine: Boolean = false): String {
        if (n == 0L) return ONES_M[0]
        val parts = ArrayList<String>()
        val millions = n / 1_000_000
        val thousands = (n / 1_000) % 1_000
        val rest = n % 1_000
        if (millions > 0) parts += counted(millions, Unit(false, "миллион", "миллиона", "миллионов"))
        if (thousands > 0) parts += counted(thousands, Unit(true, "тысяча", "тысячи", "тысяч"))
        if (rest > 0) parts += under1000(rest.toInt(), feminine)
        return parts.joinToString(" ")
    }

    private fun under1000(n: Int, feminine: Boolean): String {
        val out = ArrayList<String>()
        if (n >= 100) out += HUNDREDS[n / 100]
        val r = n % 100
        when {
            r in 10..19 -> out += TEENS[r - 10]
            else -> {
                if (r >= 20) out += TENS[r / 10]
                val u = r % 10
                if (u > 0) {
                    out += when {
                        feminine && u == 1 -> "одна"
                        feminine && u == 2 -> "две"
                        else -> ONES_M[u]
                    }
                }
            }
        }
        return out.joinToString(" ")
    }

    private fun form(n: Long, unit: Unit): String {
        val r100 = n % 100
        val r10 = n % 10
        return when {
            r100 in 11..14 -> unit.many
            r10 == 1L -> unit.one
            r10 in 2..4 -> unit.few
            else -> unit.many
        }
    }

    private fun counted(n: Long, unit: Unit) = "${cardinal(n, unit.feminine)} ${form(n, unit)}"

    private fun time(hour: Int, minute: Int): String {
        val h = cardinal(hour.toLong())
        return when {
            minute == 0 -> "$h ноль ноль"
            minute < 10 -> "$h ноль ${cardinal(minute.toLong())}"
            else -> "$h ${cardinal(minute.toLong(), feminine = true)}"
        }
    }

    private fun date(m: MatchResult): String {
        val day = m.groupValues[1].toInt()
        if (day !in 1..31) return m.value
        val year = m.groupValues[3].toIntOrNull()
        val head = "${ORDINAL_DAY[day]} ${m.groupValues[2]}"
        return if (year == null) head else "$head ${yearGenitive(year)} года"
    }

    /** 2026 → «две тысячи двадцать шестого». */
    private fun yearGenitive(year: Int): String {
        val base = year - year % 100
        val r = year % 100
        val last = when {
            r in 11..19 -> TEENS[r - 10].removeSuffix("ь") + "ого"
            r % 10 == 0 -> YEAR_ORDINAL[r / 10 * 10].orEmpty()
            else -> listOf(TENS[r / 10], YEAR_ORDINAL[r % 10].orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")
        }
        return listOf(cardinal(base.toLong()), last).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun percent(m: MatchResult): String {
        val minus = if (m.groupValues[1].isNotEmpty()) "минус " else ""
        val whole = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        if (frac.isNotEmpty()) return "$minus${cardinal(whole, true)} целых ${cardinal(frac.toLong(), true)} десятых процента"
        return minus + counted(whole, Unit(false, "процент", "процента", "процентов"))
    }

    private fun degrees(m: MatchResult): String {
        val minus = if (m.groupValues[1].isNotEmpty()) "минус " else ""
        return minus + counted(m.groupValues[2].toLong(), Unit(false, "градус", "градуса", "градусов"))
    }
}
