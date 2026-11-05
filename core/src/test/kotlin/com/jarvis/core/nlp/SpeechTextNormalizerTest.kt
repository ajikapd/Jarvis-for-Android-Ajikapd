package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextNormalizerTest {
    private fun say(s: String) = SpeechTextNormalizer.forSpeech(s)

    @Test fun time() {
        assertEquals("семнадцать сорок две", say("17:42"))
        assertEquals("семь ноль пять", say("7:05"))
        assertEquals("ноль ноль ноль", say("0:00"))
        assertEquals("девятнадцать двадцать семь", say("19:27"))
        assertEquals("Будильник на семь тридцать.", say("Будильник на 7:30."))
    }

    @Test fun percentAgreement() {
        assertEquals("один процент", say("1%"))
        assertEquals("шестьдесят шесть процентов", say("66 %"))
        assertEquals("двадцать два процента", say("22 %"))
        assertEquals("одиннадцать процентов", say("11%"))
    }

    @Test fun degrees() {
        assertEquals("Москва, сейчас четыре градуса, ощущается как один градус.", say("Москва, сейчас 4°, ощущается как 1°."))
        assertEquals("минус пять градусов", say("-5°"))
    }

    @Test fun unitsWithGender() {
        assertEquals("две минуты", say("2 мин"))
        assertEquals("Таймер: одна минута пятьдесят восемь секунд.", say("Таймер: 1 мин 58 сек."))
        assertEquals("двадцать одна секунда", say("21 секунда"))
        assertEquals("пять часов", say("5 часов"))
        assertEquals("двенадцать гигабайт", say("12 ГБ"))
        assertEquals("три метра в секунду", say("3 м/с"))
    }

    @Test fun plainNumbers() {
        assertEquals("сто двадцать пять", say("125"))
        assertEquals("две тысячи двадцать шесть", say("2026"))
        assertEquals("одна тысяча шестьсот десять", say("1610"))
        assertEquals("минус семь", say("-7"))
    }

    @Test fun dates() {
        assertEquals("Сегодня пятое ноября.", say("Сегодня 5 ноября."))
        assertEquals("пятое ноября две тысячи двадцать шестого года", say("5 ноября 2026 года"))
        assertEquals("двадцать третье февраля", say("23 февраля"))
    }

    @Test fun cleanup() {
        assertEquals("Таймер запущен!", say("Таймер запущен! 🚀"))
        assertEquals("Жирный текст", say("**Жирный** текст"))
        assertEquals("двести тридцать умножить на семь равно одна тысяча шестьсот десять", say("230 × 7 = 1610"))
    }

    @Test fun latinNames() {
        assertEquals("Открыл настройки вай-фай.", say("Открыл настройки Wi‑Fi."))
        assertEquals("Открыл телеграм.", say("Открыл Telegram."))
    }

    @Test fun plainTextUntouched() = assertEquals("Добрый вечер, сэр.", say("Добрый вечер, сэр."))

    /** Номера в словах и тексте не должны ломать слова. */
    @Test fun wordsWithDigitsKept() = assertEquals("модель gpt4", say("модель gpt4"))
}
