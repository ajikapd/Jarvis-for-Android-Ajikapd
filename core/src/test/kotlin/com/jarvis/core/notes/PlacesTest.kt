package com.jarvis.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesTest {
    @Test fun home() = assertEquals("home" to "ленина 5", Places.saveRequest("Запомни адрес дома Ленина 5."))
    @Test fun work() = assertEquals("work" to "тверская 1", Places.saveRequest("мой рабочий адрес тверская 1"))
    @Test fun other() = assertNull(Places.saveRequest("запомни что я люблю кофе"))
    @Test fun destination() = assertEquals("work", Places.keyOf("работы"))
    @Test fun plainPlace() = assertNull(Places.keyOf("вокзала"))
}
