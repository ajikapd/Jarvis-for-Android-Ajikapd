package com.jarvis.core.nlp

/** Снимок состояния телефона; [network] null, когда сети нет. */
data class SystemSnapshot(
    val batteryPercent: Int,
    val charging: Boolean,
    val network: String?,
    val freeStorageGb: Int,
    val batteryTempC: Int? = null,
)

/** Отчёт о системах голосом: сначала вывод, затем цифры. */
object SystemStatusReport {
    private const val LOW_BATTERY = 20
    private const val LOW_STORAGE_GB = 2
    private const val HOT_C = 42

    fun format(s: SystemSnapshot): String {
        val issues = buildList {
            if (s.batteryPercent <= LOW_BATTERY && !s.charging) add("низкий заряд")
            if (s.network == null) add("нет сети")
            if (s.freeStorageGb < LOW_STORAGE_GB) add("мало места")
            if (s.batteryTempC != null && s.batteryTempC >= HOT_C) add("телефон перегрет")
        }
        val head = if (issues.isEmpty()) "Все системы в норме, сэр." else "Внимание, сэр: ${issues.joinToString(", ")}."
        val battery = "Заряд ${s.batteryPercent}%" + if (s.charging) ", идёт зарядка." else "."
        val network = s.network?.let { "Сеть: $it." } ?: "Сети нет."
        val temp = s.batteryTempC?.let { " Температура $it°." }.orEmpty()
        return "$head $battery $network Свободно ${s.freeStorageGb} ГБ.$temp"
    }
}
