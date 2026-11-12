package com.jarvis.core.nlp

import com.jarvis.core.nlp.CinemaPhrases.Part.Clip
import com.jarvis.core.nlp.CinemaPhrases.Part.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaPhrasesTest {
    @Test fun noMatchStaysText() = assertNull(CinemaPhrases.split("Сейчас 19:30, на улице плюс пять."))

    @Test fun listeningIsClip() {
        val parts = CinemaPhrases.split("Слушаю, сэр.")!!
        assertEquals(1, parts.size)
        assertTrue((parts[0] as Clip).id.startsWith("yes_sir"))
    }

    @Test fun greetingThenText() {
        val parts = CinemaPhrases.split("Доброе утро, сэр. Сейчас 7:30. Погода ясная.")!!
        assertEquals(listOf(Clip("good_morning"), Text("Сейчас 7:30. Погода ясная.")), parts)
    }

    @Test fun restOfSentenceKept() {
        val parts = CinemaPhrases.split("Хорошо, сэр, не отправляю.")!!
        assertEquals(listOf(Clip("as_you_wish"), Text("Не отправляю.")), parts)
    }

    @Test fun batteryWithClip() {
        val parts = CinemaPhrases.split("Заряд 15%.")!!
        assertEquals(listOf<CinemaPhrases.Part>(Clip("battery_15")), parts)
    }

    @Test fun batteryWithoutClip() = assertNull(CinemaPhrases.split("Заряд 80%, идёт зарядка."))

    @Test fun fallingBatteryNotWhileCharging() = assertNull(CinemaPhrases.split("Заряд 48%, идёт зарядка."))

    @Test fun midSentenceNotReplaced() = assertNull(CinemaPhrases.split("Я уже говорил: готово."))
}
