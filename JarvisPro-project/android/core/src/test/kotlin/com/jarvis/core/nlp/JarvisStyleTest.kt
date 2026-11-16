package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class JarvisStyleTest {
    @Test fun knownPhrase() = assertEquals("Фонарик включён, сэр.", JarvisStyle.confirmation("Включён."))
    @Test fun shortAddsAddress() = assertEquals("Таймер: 2 мин, сэр.", JarvisStyle.confirmation("Таймер: 2 мин."))
    @Test fun alreadyAddressed() = assertEquals("Готово, сэр.", JarvisStyle.confirmation("Готово, сэр."))
    @Test fun questionUntouched() = assertEquals("Включить фонарик?", JarvisStyle.confirmation("Включить фонарик?"))

    @Test fun longReplyUntouched() {
        val long = "Москва, сейчас четыре градуса, ощущается как один, малооблачно, ветер."
        assertEquals(long, JarvisStyle.confirmation(long))
    }

    @Test fun listeningVaries() = assertNotEquals(JarvisStyle.listening(0), JarvisStyle.listening(1))
    @Test fun listeningNegativeTurnSafe() = assertEquals(JarvisStyle.listening(3), JarvisStyle.listening(-1))
}
