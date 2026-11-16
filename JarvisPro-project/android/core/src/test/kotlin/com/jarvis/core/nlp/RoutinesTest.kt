package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RoutinesTest {
    @Test fun wholePhrase() = assertEquals("Спокойной ночи, сэр.", Routines.find("Я ложусь спать.")?.closing)
    @Test fun addressed() = assertNotNull(Routines.find("Джарвис, режим кино"))
    @Test fun partOfSentenceIsNot() = assertNull(Routines.find("я ухожу через час"))
    @Test fun everyStepIsAPlainCommand() = assertEquals(
        listOf("останови музыку", "выключи фонарик"), Routines.find("я ушёл")?.steps,
    )
}
