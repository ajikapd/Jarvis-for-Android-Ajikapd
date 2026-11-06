package com.jarvis.core.nlp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {
    @After fun reset() { Translator.active = null }

    private fun once(phrase: String) = Translator.parse(phrase) as Translator.Request.Once

    @Test fun onceBefore() {
        val r = once("переведи на английский доброе утро")
        assertEquals("en-US", r.target.locale)
        assertEquals("доброе утро", r.text)
    }

    @Test fun onceAfter() {
        val r = once("как будет спасибо на немецком")
        assertEquals("de-DE", r.target.locale)
        assertEquals("спасибо", r.text)
    }

    @Test fun start() = assertEquals("fr-FR", (Translator.parse("включи переводчик на французский") as Translator.Request.Start).target.locale)
    @Test fun stop() = assertEquals(Translator.Request.Stop, Translator.parse("стоп перевод"))
    @Test fun unrelated() = assertNull(Translator.parse("включи фонарик"))
    @Test fun unknownLanguage() = assertNull(Translator.parse("переведи на клингонский привет"))
    @Test fun russianIsNotForeign() = assertNull(Translator.speechLocale("Сеть: Wi-Fi, сэр."))
    @Test fun englishIsForeign() = assertEquals("en-US", Translator.speechLocale("Good morning"))

    @Test fun modeChoosesTheVoice() {
        Translator.active = TranslationTarget("немецкий", "de-DE")
        assertEquals("de-DE", Translator.speechLocale("Guten Morgen"))
    }

    @Test fun promptNamesLanguage() = assertTrue(Translator.prompt(TranslationTarget("немецкий", "de-DE"), "x").contains("немецкий"))
}
