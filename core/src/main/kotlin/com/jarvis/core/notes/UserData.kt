package com.jarvis.core.notes

/** Адреса пользователя: хранятся только на телефоне. */
interface PlaceBook {
    fun get(key: String): String?
    fun set(key: String, address: String)
}

/** Сценарии, заданные голосом. */
interface CustomRoutines {
    fun steps(name: String): List<String>?
    fun put(name: String, steps: List<String>)
    fun remove(name: String): Boolean
    fun names(): List<String>
}

object Places {
    const val HOME = "home"
    const val WORK = "work"
    private val SAVE_HOME = Regex("^(?:запомни |сохрани |запиши )?(?:мой )?(?:домашний адрес|адрес дома)\\s+(.+)$")
    private val SAVE_WORK = Regex("^(?:запомни |сохрани |запиши )?(?:мой )?(?:рабочий адрес|адрес работы)\\s+(.+)$")
    private val DESTINATIONS = mapOf(
        "домой" to HOME, "дом" to HOME, "дому" to HOME, "дома" to HOME,
        "работу" to WORK, "работы" to WORK, "работа" to WORK, "работе" to WORK,
    )

    /** Ключ и адрес из фразы «запомни адрес дома Ленина пять». */
    fun saveRequest(text: String): Pair<String, String>? {
        val t = text.lowercase().trim().trimEnd('.', '!')
        SAVE_HOME.find(t)?.let { return HOME to it.groupValues[1] }
        SAVE_WORK.find(t)?.let { return WORK to it.groupValues[1] }
        return null
    }

    fun keyOf(destination: String): String? = DESTINATIONS[destination.lowercase().trim()]
}
