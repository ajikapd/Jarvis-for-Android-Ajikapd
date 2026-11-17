package com.jarvis.core.nlp

import com.jarvis.core.nlp.CinemaPhrases.Hint
import com.jarvis.core.nlp.CinemaPhrases.Part
import com.jarvis.core.nlp.CinemaPhrases.Part.Clip
import com.jarvis.core.nlp.CinemaPhrases.Part.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaPhrasesTest {
    private val done = setOf("done", "aye", "as_you_wish")

    private fun clipOnly(parts: List<Part>?): String {
        assertEquals(1, parts!!.size)
        return (parts[0] as Clip).id
    }

    @Test fun plainFactStaysText() = assertNull(CinemaPhrases.split("Сейчас 19:30, на улице плюс пять."))

    @Test fun listeningIsClip() = assertTrue(clipOnly(CinemaPhrases.split("Слушаю, сэр.")).startsWith("yes_sir"))

    @Test fun listeningClipsAreFilmOnly() =
        repeat(8) { assertTrue(CinemaPhrases.listening() in CinemaPhrases.LISTENING) }

    @Test fun greetingThenText() = assertEquals(
        listOf(Clip("good_morning"), Text("Сейчас 7:30. Погода ясная.")),
        CinemaPhrases.split("Доброе утро, сэр. Сейчас 7:30. Погода ясная."),
    )

    @Test fun confirmationKeepsMeaning() {
        val parts = CinemaPhrases.split("Хорошо, сэр, не отправляю.")!!
        assertTrue((parts[0] as Clip).id in done)
        assertEquals(Text("Не отправляю."), parts[1])
    }

    @Test fun thanksIsClip() = assertTrue(clipOnly(CinemaPhrases.split("Пожалуйста, сэр.")).contains("at_service"))

    @Test fun pleaseWithRequestStaysText() = assertNull(CinemaPhrases.split("Пожалуйста, уточните город для прогноза погоды."))

    @Test fun waitIsLoading() = assertEquals("loading", clipOnly(CinemaPhrases.split("Секунду…")))

    @Test fun notFoundIsClip() = assertEquals("no_more_info", clipOnly(CinemaPhrases.split("Ничего не нашёл по «котики».")))

    @Test fun notUnderstoodIsClip() = assertEquals("what_for", clipOnly(CinemaPhrases.split("Не понял, повторите.")))

    @Test fun cannotYetIsClip() = assertEquals("working", clipOnly(CinemaPhrases.split("Этого я пока не умею.")))

    @Test fun eveningGreetingIsClip() = assertEquals("at_service", clipOnly(CinemaPhrases.split("Добрый вечер, сэр.")))

    @Test fun batteryWithClip() = assertEquals("battery_15", clipOnly(CinemaPhrases.split("Заряд 15%.")))

    @Test fun lowBatteryNotice() = assertEquals("battery_7", clipOnly(CinemaPhrases.split("Сэр, заряд на исходе: 7%.")))

    @Test fun batteryWithoutClip() = assertNull(CinemaPhrases.split("Заряд 80%, идёт зарядка."))

    @Test fun fallingBatteryNotWhileCharging() = assertNull(CinemaPhrases.split("Заряд 48%, идёт зарядка."))

    @Test fun midSentenceNotReplaced() = assertNull(CinemaPhrases.split("Я уже говорил: готово."))

    @Test fun actionIsOnlyClip() =
        assertTrue(clipOnly(CinemaPhrases.plan("Фонарик включён, сэр.", Hint("flashlight", true))) in done)

    @Test fun actionKeepsNumbers() {
        val parts = CinemaPhrases.plan("Таймер: 10 мин, сэр.", Hint("timer", true))!!
        assertTrue((parts[0] as Clip).id in done)
        assertEquals(Text("Таймер: 10 мин."), parts[1])
    }

    @Test fun openAppIsLoading() =
        assertEquals("loading", clipOnly(CinemaPhrases.plan("Открываю Telegram, сэр.", Hint("launcher", true))))

    @Test fun statusStartsWithCheck() {
        val parts = CinemaPhrases.plan("Заряд 80%, идёт зарядка. Сеть Wi-Fi.", Hint("system.status", true))!!
        assertEquals(Clip("check_done"), parts[0])
        assertEquals(Text("Заряд 80%, идёт зарядка. Сеть Wi-Fi."), parts[1])
    }

    @Test fun confirmationQuestionIsRead() =
        assertNull(CinemaPhrases.plan("Отправить Маше «буду в семь»?", Hint("sms", null, needsConfirmation = true)))

    @Test fun infoAnswerIsRead() =
        assertNull(CinemaPhrases.plan("В Москве плюс шесть, пасмурно.", Hint("weather", true)))

    @Test fun failedSearchIsClip() =
        assertEquals("no_more_info", clipOnly(CinemaPhrases.plan("Не нашёл приложение «Пятёрочка».", Hint("launcher", false))))
}
