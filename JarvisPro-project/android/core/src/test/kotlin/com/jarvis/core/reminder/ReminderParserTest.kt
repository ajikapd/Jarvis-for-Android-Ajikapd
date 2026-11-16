package com.jarvis.core.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ReminderParserTest {
    // Четверг, 14:10.
    private val now = LocalDateTime.of(2026, 11, 5, 14, 10)

    private fun check(phrase: String, at: LocalDateTime?, text: String) {
        val r = ReminderParser.parse(phrase, now)
        assertEquals(phrase, at, r.at)
        assertEquals(phrase, text, r.text)
    }

    private fun day(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    @Test fun relativeMinutes() = check("напомни через 20 минут выключить плиту", now.plusMinutes(20), "выключить плиту")
    @Test fun relativeWords() = check("напомни через двадцать пять минут проверить духовку", now.plusMinutes(25), "проверить духовку")
    @Test fun halfHour() = check("напомни мне через полчаса позвонить маме", now.plusMinutes(30), "позвонить маме")
    @Test fun anHour() = check("напомни через час забрать посылку", now.plusHours(1), "забрать посылку")
    @Test fun hourAndHalf() = check("напомни через полтора часа выйти", now.plusMinutes(90), "выйти")
    @Test fun couple() = check("напомни через пару часов поесть", now.plusHours(2), "поесть")
    @Test fun composite() = check("напомни через два часа тридцать минут встать", now.plusMinutes(150), "встать")
    @Test fun aDay() = check("напомни через день полить цветы", now.plusDays(1), "полить цветы")
    @Test fun threeDays() = check("напомни через 3 дня оплатить связь", now.plusDays(3), "оплатить связь")
    @Test fun aWeek() = check("напомни через неделю сдать отчёт", now.plusDays(7), "сдать отчёт")

    @Test fun tomorrowAtNine() = check("напомни завтра в 9 позвонить маме", day(6, 9), "позвонить маме")
    @Test fun tomorrowWords() = check("напомни завтра в девять утра позвонить маме", day(6, 9), "позвонить маме")
    @Test fun tomorrowEvening() = check("напомни завтра в семь вечера про встречу", day(6, 19), "про встречу")
    @Test fun tomorrowAmbiguousEveningHour() = check("напомни завтра в 5 забрать сына", day(6, 17), "забрать сына")
    @Test fun colon() = check("напомни в 18:30 купить хлеб", day(5, 18, 30), "купить хлеб")
    @Test fun spaced() = check("напомни в 18 30 купить хлеб", day(5, 18, 30), "купить хлеб")
    @Test fun wordsWithMinutes() = check("напомни в девять тридцать вечера выгулять собаку", day(5, 21, 30), "выгулять собаку")
    @Test fun hoursAndMinutes() = check("напомни в 9 часов 15 минут вечера позвонить", day(5, 21, 15), "позвонить")
    @Test fun leadingZeroMinutes() = check("напомни завтра в семь ноль пять утра встать", day(6, 7, 5), "встать")

    @Test fun nearestDialHour() = check("напомни в 5 забрать посылку", day(5, 17), "забрать посылку")
    @Test fun nearestDialHourLaterToday() = check("напомни в 3 позвонить", day(5, 15), "позвонить")
    @Test fun pastHourGoesToTomorrow() = check("напомни в 14:00 позвонить", day(6, 14), "позвонить")
    @Test fun pastMorningWithPart() = check("напомни в 8 утра встать", day(6, 8), "встать")

    @Test fun partOnly() = check("напомни вечером купить молоко", day(5, 19), "купить молоко")
    @Test fun partOnlyPassed() = check("напомни утром купить молоко", day(6, 9), "купить молоко")
    @Test fun tomorrowMorning() = check("напомни завтра утром принять таблетки", day(6, 9), "принять таблетки")
    @Test fun tomorrowOnly() = check("напомни завтра заплатить за квартиру", day(6, 9), "заплатить за квартиру")
    @Test fun noon() = check("напомни завтра в полдень поесть", day(6, 12), "поесть")
    @Test fun lunch() = check("напомни завтра в обед поесть", day(6, 13), "поесть")
    @Test fun halfSeven() = check("напомни завтра в половине седьмого утра встать", day(6, 6, 30), "встать")
    @Test fun halfSevenShort() = check("напомни в полседьмого вечера выйти", day(5, 18, 30), "выйти")
    @Test fun oneOClock() = check("напомни завтра в час дня обед", day(6, 13), "обед")

    @Test fun weekdayLater() = check("напомни в пятницу в десять утра сдать отчёт", day(6, 10), "сдать отчёт")
    @Test fun weekdayNext() = check("напомни во вторник вечером позвонить", day(10, 19), "позвонить")
    @Test fun sameWeekdayPassed() = check("напомни в четверг в 9 утра позвонить", day(12, 9), "позвонить")
    @Test fun sameWeekdayAhead() = check("напомни в четверг в 8 вечера позвонить", day(5, 20), "позвонить")

    @Test fun dateDigits() = check("напомни 20 ноября в 10 утра продлить полис", day(20, 10), "продлить полис")
    @Test fun dateOrdinal() = check("напомни двадцать пятого ноября оплатить", day(25, 9), "оплатить")
    @Test fun pastDateNextYear() = check("напомни 1 марта поздравить", LocalDateTime.of(2027, 3, 1, 9, 0), "поздравить")

    @Test fun noTime() = check("напомни купить хлеб", null, "купить хлеб")
    @Test fun todayWithoutTime() = check("напомни сегодня купить хлеб", null, "купить хлеб")
    @Test fun noText() = check("напомни завтра в 9", day(6, 9), "")
    @Test fun relativeNoText() = check("напомни через 10 минут", now.plusMinutes(10), "")

    @Test fun thatFiller() = check("напомни завтра в 9 о том что надо позвонить врачу", day(6, 9), "позвонить врачу")
    @Test fun numbersInTaskStay() = check("напомни завтра в 9 купить 2 литра молока", day(6, 9), "купить 2 литра молока")
    @Test fun amountNotClock() = check("напомни завтра купить еду на 5 человек", day(6, 9), "купить еду на 5 человек")
    @Test fun sumNotClock() = check("напомни завтра оплатить на 500 рублей", day(6, 9), "оплатить на 500 рублей")
    @Test fun noun() = check("поставь напоминание на завтра в 10 утра позвонить", day(6, 10), "позвонить")
    @Test fun inWordsTaskPreserved() = check("напомни сходить в магазин завтра в 9", day(6, 9), "сходить в магазин")

    @Test fun dailyMorning() {
        val r = ReminderParser.parse("напоминай каждый день в 8 утра пить таблетки", now)
        assertTrue(r.daily)
        assertEquals(day(6, 8), r.at)
    }

    @Test fun everyEvening() {
        val r = ReminderParser.parse("напомни каждый вечер почистить зубы", now)
        assertTrue(r.daily)
        assertEquals(day(5, 19), r.at)
        assertEquals("почистить зубы", r.text)
    }

    @Test fun timeOnlyAnswer() {
        val r = ReminderParser.parse("завтра в девять", now)
        assertEquals(day(6, 9), r.at)
        assertEquals("", r.text)
        assertNull(ReminderParser.parse("хлеб", now).at)
    }

    @Test fun speech() {
        assertEquals("через 20 мин", ReminderSpeech.whenPhrase(now.plusMinutes(20), now))
        assertEquals("сегодня в 18:30", ReminderSpeech.whenPhrase(day(5, 18, 30), now))
        assertEquals("завтра в 9:00", ReminderSpeech.whenPhrase(day(6, 9), now))
        assertEquals("во вторник в 19:00", ReminderSpeech.whenPhrase(day(10, 19), now))
        assertTrue(ReminderSpeech.whenPhrase(day(25, 9), now).startsWith("25 "))
    }
}
