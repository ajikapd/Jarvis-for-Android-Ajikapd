package com.jarvis.core.reminder

import com.jarvis.core.nlp.RuNumbers
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Разобранная просьба: [at] null — время не названо, пустой [text] — не сказано, о чём. */
data class ReminderRequest(
    val at: LocalDateTime?,
    val text: String,
    val daily: Boolean = false,
)

/** Понимает «завтра в девять», «через полчаса», «в пятницу вечером», «каждый день в 8». */
object ReminderParser {
    private enum class Part { MORNING, DAY, EVENING, NIGHT }

    fun parse(phrase: String, now: LocalDateTime): ReminderRequest = Scan(phrase, now).run()

    private class Scan(phrase: String, private val now: LocalDateTime) {
        private val tokens = phrase.lowercase().split(Regex("\\s+"))
            .map { it.trim(',', '.', '!', '?', ';', ':', '«', '»', '"') }
            .filter { it.isNotEmpty() }
        private val used = BooleanArray(tokens.size)
        private var daily = false
        private var part: Part? = null
        private var date: LocalDate? = null
        private var weekday: DayOfWeek? = null
        private var today = false
        private var clock: Pair<Int, Int>? = null

        fun run(): ReminderRequest {
            val relative = relative()
            scanDaily()
            scanDays()
            scanClock()
            scanParts()
            return ReminderRequest(relative ?: absolute(), task(), daily)
        }

        private fun take(vararg indices: Int) = indices.forEach { used[it] = true }

        private fun takePreposition(i: Int) {
            if (tokens.getOrNull(i - 1) in PREPOSITIONS) used[i - 1] = true
        }

        private fun numberAt(start: Int): Pair<Int, Int>? {
            tokens.getOrNull(start)?.toIntOrNull()?.let { return it to start + 1 }
            var value: Int? = null
            var i = start
            while (i < tokens.size) {
                if (tokens[i].toIntOrNull() != null) break
                val own = RuNumbers.endingAt(tokens.subList(i, i + 1), 0) ?: break
                val total = (value ?: 0) + own
                if (RuNumbers.endingAt(tokens.subList(start, i + 1), i - start) != total) break
                value = total
                i++
            }
            return value?.let { it to i }
        }

        /** «через час», «через два часа тридцать минут», «через полчаса». */
        private fun relative(): LocalDateTime? {
            val start = tokens.indexOf("через")
            if (start < 0) return null
            var i = start + 1
            var total = Duration.ZERO
            while (i < tokens.size) {
                if (tokens[i] == "и") {
                    i++
                    continue
                }
                if (tokens[i] == "полчаса") {
                    total += Duration.ofMinutes(30)
                    i++
                    continue
                }
                val fraction = tokens[i] == "полтора" || tokens[i] == "полторы"
                val pair = tokens[i] == "пару"
                val number = if (fraction || pair) null else numberAt(i)
                val unitAt = if (fraction || pair) i + 1 else number?.second ?: i
                val unit = unitOf(tokens.getOrNull(unitAt)) ?: break
                val minutes = when {
                    fraction -> unit.toMinutes() * 3 / 2
                    pair -> unit.toMinutes() * 2
                    else -> unit.toMinutes() * (number?.first ?: 1)
                }
                total += Duration.ofMinutes(minutes)
                if (unit < Duration.ofMinutes(1)) total += unit.multipliedBy((number?.first ?: 1).toLong())
                i = unitAt + 1
            }
            if (total.isZero) return null
            for (k in start until i) used[k] = true
            return now.plus(total).truncatedTo(ChronoUnit.SECONDS)
        }

        private fun unitOf(word: String?): Duration? = when {
            word == null -> null
            word.startsWith("минут") -> Duration.ofMinutes(1)
            word.startsWith("секунд") -> Duration.ofSeconds(1)
            word == "час" || word == "часа" || word == "часов" -> Duration.ofHours(1)
            word == "день" || word == "дня" || word == "дней" || word == "сутки" -> Duration.ofDays(1)
            word.startsWith("недел") -> Duration.ofDays(7)
            else -> null
        }

        private fun scanDaily() {
            for (i in tokens.indices) {
                if (tokens[i] == "ежедневно") {
                    daily = true
                    take(i)
                } else if (tokens[i] == "каждый" || tokens[i] == "каждое") {
                    val next = tokens.getOrNull(i + 1)
                    val everyPart = PARTS[next]
                    if (next == "день" || everyPart != null) {
                        daily = true
                        take(i, i + 1)
                        if (everyPart != null) part = everyPart
                    }
                }
            }
        }

        private fun scanDays() {
            for (i in tokens.indices) {
                if (used[i]) continue
                val word = tokens[i]
                val offset = DAY_OFFSETS[word]
                val dow = WEEKDAYS[word]
                when {
                    offset != null -> {
                        date = now.toLocalDate().plusDays(offset)
                        today = offset == 0L
                        take(i)
                        takePreposition(i)
                    }
                    dow != null && tokens.getOrNull(i - 1) in PREPOSITIONS -> {
                        weekday = dow
                        take(i)
                        takePreposition(i)
                    }
                    word in MONTHS -> calendarDate(i)
                }
            }
        }

        /** «пятого ноября», «двадцать пятого ноября», «5 ноября». */
        private fun calendarDate(month: Int) {
            val before = tokens.getOrNull(month - 1) ?: return
            var first = month - 1
            var day = before.toIntOrNull() ?: ORDINALS[before]
            if (day != null && day < 10 && tokens.getOrNull(month - 2) == "двадцать") {
                day += 20
                first--
            }
            if (day != null && day <= 1 && tokens.getOrNull(month - 2) == "тридцать") {
                day += 30
                first--
            }
            if (day == null || day !in 1..31) return
            val parsed = runCatching { now.toLocalDate().withMonth(MONTHS.getValue(tokens[month])).withDayOfMonth(day) }
                .getOrNull() ?: return
            date = if (parsed < now.toLocalDate()) parsed.plusYears(1) else parsed
            for (k in first..month) used[k] = true
            takePreposition(first)
        }

        private fun scanClock() {
            for (i in tokens.indices) {
                if (used[i]) continue
                val word = tokens[i]
                COLON.matchEntire(word)?.let { m ->
                    val h = m.groupValues[1].toInt()
                    val min = m.groupValues[2].toInt()
                    if (h <= 24 && min < 60) {
                        clock = (h % 24) to min
                        take(i)
                        takePreposition(i)
                        return
                    }
                }
                val halfOf = HALF_HOURS[word]
                if (halfOf != null) {
                    clock = (halfOf - 1) to 30
                    take(i)
                    takePreposition(i)
                    return
                }
                if (word == "половине" && tokens.getOrNull(i + 1) in ORDINALS) {
                    clock = (ORDINALS.getValue(tokens[i + 1]) - 1) to 30
                    take(i, i + 1)
                    takePreposition(i)
                    return
                }
                val prep = tokens.getOrNull(i - 1)
                if (prep == null || prep !in PREPOSITIONS || used[i - 1]) continue
                if (word == "полдень" || word == "полночь" || word == "обед") {
                    clock = if (word == "полночь") 0 to 0 else if (word == "обед") 13 to 0 else 12 to 0
                    take(i)
                    takePreposition(i)
                    return
                }
                if (word == "час" && prep == "в") {
                    clock = 1 to 0
                    take(i)
                    takePreposition(i)
                    return
                }
                if (spokenTime(i, prep)) return
            }
        }

        private fun spokenTime(i: Int, prep: String): Boolean {
            val (hour, afterHour) = numberAt(i) ?: return false
            if (hour > 24) return false
            var next = afterHour
            val hours = tokens.getOrNull(next) in HOUR_WORDS
            if (hours) next++
            // «на» и «к» часто значат количество: без явного признака времени это не часы.
            val evident = hours || tokens.getOrNull(next) in PARTS
            if (prep != "в" && !evident) return false
            val zero = tokens.getOrNull(next) == "ноль"
            val minuteAt = if (zero) next + 1 else next
            var minute = 0
            numberAt(minuteAt)?.let { (value, after) ->
                if (value in 0..59 && (zero || value >= 10 || tokens.getOrNull(after)?.startsWith("минут") == true)) {
                    minute = value
                    next = after
                    if (tokens.getOrNull(next)?.startsWith("минут") == true) next++
                }
            }
            for (k in i until next) used[k] = true
            takePreposition(i)
            clock = (hour % 24) to minute
            return true
        }

        private fun scanParts() {
            for (i in tokens.indices) {
                val found = PARTS[tokens[i]] ?: continue
                if (used[i]) continue
                part = found
                take(i)
            }
        }

        private fun absolute(): LocalDateTime? {
            val exactDay = date ?: weekday?.let { dayFor(it) }
            val time = clock
            if (time != null) return atClock(time.first, time.second, exactDay)
            val defaultHour = part?.let { DEFAULT_HOURS.getValue(it) }
            return when {
                defaultHour != null -> rollForward(exactDay ?: now.toLocalDate(), LocalTime.of(defaultHour, 0), exactDay != null)
                today -> null
                exactDay != null -> exactDay.atTime(DEFAULT_HOUR, 0)
                daily -> rollForward(now.toLocalDate(), LocalTime.of(DEFAULT_HOUR, 0), false)
                else -> null
            }
        }

        private fun dayFor(dow: DayOfWeek): LocalDate {
            val ahead = (dow.value - now.dayOfWeek.value + 7) % 7
            return now.toLocalDate().plusDays(ahead.toLong())
        }

        private fun atClock(hour: Int, minute: Int, day: LocalDate?): LocalDateTime {
            val p = part
            if (p != null) {
                val fixed = when (p) {
                    Part.MORNING -> hour % 12
                    Part.DAY, Part.EVENING -> if (hour in 1..11) hour + 12 else hour
                    Part.NIGHT -> if (hour == 12) 0 else hour
                }
                return onDay(day, fixed, minute)
            }
            if (hour !in 1..12) return onDay(day, hour, minute)
            if (day != null && !today) return day.atTime(if (hour <= LAST_AMBIGUOUS_PM) hour + 12 else hour, minute)
            // Без уточнения «в пять» — ближайшие пять часов на циферблате.
            val base = now.toLocalDate()
            val first = base.atTime(hour % 12, minute)
            val second = first.plusHours(12)
            if (hour != 12 && first > now) return first
            if (hour == 12 && second > now) return second
            if (second > now && hour != 12) return second
            return base.plusDays(1).atTime(if (hour <= LAST_AMBIGUOUS_PM) hour + 12 else hour, minute)
        }

        private fun onDay(day: LocalDate?, hour: Int, minute: Int): LocalDateTime =
            rollForward(day ?: now.toLocalDate(), LocalTime.of(hour, minute), day != null && !today)

        private fun rollForward(day: LocalDate, time: LocalTime, exact: Boolean): LocalDateTime {
            val at = day.atTime(time)
            if (exact && weekday == null) return at
            if (at > now) return at
            return at.plusDays(if (weekday != null) 7 else 1)
        }

        private fun task(): String {
            val rest = tokens.indices.filter { !used[it] }.map { tokens[it] }.toMutableList()
            while (rest.isNotEmpty() && rest.first() in LEADING_FILLERS) rest.removeAt(0)
            while (rest.size >= 2 && rest[0] == "о" && rest[1] == "том") repeat(2) { rest.removeAt(0) }
            while (rest.isNotEmpty() && rest.first() in LEADING_FILLERS) rest.removeAt(0)
            while (rest.isNotEmpty() && rest.last() in TRAILING_FILLERS) rest.removeAt(rest.lastIndex)
            return rest.joinToString(" ")
        }
    }

    private const val DEFAULT_HOUR = 9

    /** Назначено на другой день без уточнения: «завтра в пять» почти всегда значит 17:00. */
    private const val LAST_AMBIGUOUS_PM = 6

    private val COLON = Regex("""(\d{1,2})[:.](\d{2})""")
    private val PREPOSITIONS = setOf("в", "во", "на", "к")
    private val HOUR_WORDS = setOf("час", "часа", "часов")
    private val DAY_OFFSETS = mapOf("сегодня" to 0L, "завтра" to 1L, "послезавтра" to 2L)
    private val WEEKDAYS = mapOf(
        "понедельник" to DayOfWeek.MONDAY, "вторник" to DayOfWeek.TUESDAY,
        "среду" to DayOfWeek.WEDNESDAY, "среда" to DayOfWeek.WEDNESDAY,
        "четверг" to DayOfWeek.THURSDAY, "пятницу" to DayOfWeek.FRIDAY, "пятница" to DayOfWeek.FRIDAY,
        "субботу" to DayOfWeek.SATURDAY, "суббота" to DayOfWeek.SATURDAY,
        "воскресенье" to DayOfWeek.SUNDAY,
    )
    private val PARTS = mapOf(
        "утра" to Part.MORNING, "утром" to Part.MORNING, "утро" to Part.MORNING,
        "дня" to Part.DAY, "днём" to Part.DAY, "днем" to Part.DAY,
        "вечера" to Part.EVENING, "вечером" to Part.EVENING, "вечер" to Part.EVENING,
        "ночи" to Part.NIGHT, "ночью" to Part.NIGHT,
    )
    private val DEFAULT_HOURS = mapOf(Part.MORNING to 9, Part.DAY to 13, Part.EVENING to 19, Part.NIGHT to 23)
    private val MONTHS = mapOf(
        "января" to 1, "февраля" to 2, "марта" to 3, "апреля" to 4, "мая" to 5, "июня" to 6,
        "июля" to 7, "августа" to 8, "сентября" to 9, "октября" to 10, "ноября" to 11, "декабря" to 12,
    )
    private val ORDINALS = mapOf(
        "первого" to 1, "второго" to 2, "третьего" to 3, "четвёртого" to 4, "четвертого" to 4,
        "пятого" to 5, "шестого" to 6, "седьмого" to 7, "восьмого" to 8, "девятого" to 9,
        "десятого" to 10, "одиннадцатого" to 11, "двенадцатого" to 12, "тринадцатого" to 13,
        "четырнадцатого" to 14, "пятнадцатого" to 15, "шестнадцатого" to 16, "семнадцатого" to 17,
        "восемнадцатого" to 18, "девятнадцатого" to 19, "двадцатого" to 20, "тридцатого" to 30,
    )
    private val HALF_HOURS = mapOf(
        "полпервого" to 1, "полвторого" to 2, "полтретьего" to 3, "полчетвёртого" to 4, "полчетвертого" to 4,
        "полпятого" to 5, "полшестого" to 6, "полседьмого" to 7, "полвосьмого" to 8, "полдевятого" to 9,
        "полдесятого" to 10, "пол-одиннадцатого" to 11, "полодиннадцатого" to 11, "полдвенадцатого" to 12,
    )
    private val LEADING_FILLERS = setOf(
        "джарвис", "поставь", "создай", "сделай", "установи", "добавь", "напомни", "напомните", "напомнить",
        "напоминание", "напоминалку", "напоминай", "мне", "пожалуйста", "что", "чтобы", "надо", "нужно", "сэр",
    )
    private val TRAILING_FILLERS = setOf("пожалуйста", "мне", "сэр", "в", "на", "к")
}
