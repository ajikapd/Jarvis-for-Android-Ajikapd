package com.jarvis.core.llm

import com.jarvis.core.companion.PhoneContext

object DeviceContextGating {

    private val deviceMarkers = listOf(
        "громк", "звук", "музык", "слыш", "наушник", "батар", "заряд",
        "экран", "приложен", "открыт", "сейчас", "телефон", "интернет",
        "сеть", "wifi", "wi-fi", "уведомлен",
    )

    fun isRelevant(query: String): Boolean {
        val q = query.lowercase()
        return deviceMarkers.any { q.contains(it) }
    }

    fun format(phone: PhoneContext?, query: String, maxChars: Int): Pair<String, Boolean> {
        if (phone == null || !isRelevant(query)) return "" to false
        val lines = mutableListOf<String>()
        phone.currentApp?.let { lines += "текущее приложение: $it" }
        phone.batteryPercent?.let { lines += "батарея: $it%" }
        if (phone.charging) lines += "зарядка: да"
        if (phone.headphonesConnected) lines += "наушники: подключены"
        phone.networkType?.let { lines += "сеть: $it" }
        if (!phone.screenOn) lines += "экран: выключен"
        val block = lines.joinToString("; ").take(maxChars)
        val appIncluded = phone.currentApp != null
        return block to (block.isNotBlank())
    }
}
