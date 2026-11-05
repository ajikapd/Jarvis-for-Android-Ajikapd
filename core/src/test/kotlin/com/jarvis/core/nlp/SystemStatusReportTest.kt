package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemStatusReportTest {
    @Test fun allFine() = assertEquals(
        "Все системы в норме, сэр. Заряд 80%. Сеть: Wi-Fi. Свободно 12 ГБ. Температура 31°.",
        SystemStatusReport.format(SystemSnapshot(80, false, "Wi-Fi", 12, 31)),
    )

    @Test fun lowBatteryWarns() {
        val r = SystemStatusReport.format(SystemSnapshot(15, false, "Wi-Fi", 12))
        assertTrue(r, r.startsWith("Внимание, сэр: низкий заряд."))
    }

    @Test fun chargingIsNotALowBatteryIssue() {
        val r = SystemStatusReport.format(SystemSnapshot(15, true, "Wi-Fi", 12))
        assertTrue(r, r.startsWith("Все системы в норме") && r.contains("идёт зарядка"))
    }

    @Test fun severalIssuesListed() {
        val r = SystemStatusReport.format(SystemSnapshot(10, false, null, 1, 45))
        assertTrue(r, r.contains("низкий заряд, нет сети, мало места, телефон перегрет"))
        assertTrue(r, r.contains("Сети нет."))
    }
}
