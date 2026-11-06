package com.jarvis.core.routines

import com.jarvis.core.companion.ObservationType

/** Что запускает сценарий без голоса. По времени уже работают ежедневные напоминания-команды. */
enum class EventTrigger(val title: String) {
    HEADPHONES_ON("подключены наушники"),
    HEADPHONES_OFF("отключены наушники"),
    CHARGER_ON("поставлен на зарядку"),
    CHARGER_OFF("снят с зарядки"),
    WIFI_ON("подключён вайфай"),
    WIFI_OFF("отключён вайфай"),
    UNLOCK("телефон разблокирован"),
    APP_OPEN("открыто приложение"),
}

/** Шаги лежат в обычном сценарии с именем [name]: запускаются тем же путём, что и голосом. */
data class EventRule(val trigger: EventTrigger, val arg: String = "") {
    val name: String = listOf(trigger.title, arg).filter { it.isNotEmpty() }.joinToString(" ")
}

interface EventRoutines {
    fun rules(): List<EventRule>
    fun put(rule: EventRule)
    fun remove(rule: EventRule)
}

/** Событие телефона в виде, не зависящем от Android. */
data class PhoneEvent(
    val type: ObservationType,
    val network: String? = null,
    val packageName: String? = null,
    /** Снимок при запуске приложения: состояние, а не событие. */
    val startup: Boolean = false,
)

/** Решает, какие правила сработали. Срабатывает только на смену состояния и не чаще паузы. */
class EventMatcher(
    private val packageMatches: (appName: String, packageName: String) -> Boolean,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private var network: String? = null
    private var foreground: String? = null
    private val firedAt = HashMap<EventRule, Long>()

    fun match(event: PhoneEvent, rules: List<EventRule>, nowMs: Long): List<EventRule> {
        val triggers = triggers(event)
        if (event.startup || triggers.isEmpty()) return emptyList()
        val hit = rules.filter { it.trigger in triggers && appFits(it, event) && cooled(it, nowMs) }
        hit.forEach { firedAt[it] = nowMs }
        return hit
    }

    private fun appFits(rule: EventRule, event: PhoneEvent): Boolean =
        rule.trigger != EventTrigger.APP_OPEN || packageMatches(rule.arg, event.packageName.orEmpty())

    private fun cooled(rule: EventRule, nowMs: Long): Boolean {
        val last = firedAt[rule] ?: return true
        return nowMs - last >= cooldownMs
    }

    private fun triggers(event: PhoneEvent): Set<EventTrigger> = when (event.type) {
        ObservationType.HEADPHONES_CONNECTED -> setOf(EventTrigger.HEADPHONES_ON)
        ObservationType.HEADPHONES_DISCONNECTED -> setOf(EventTrigger.HEADPHONES_OFF)
        ObservationType.CHARGER_CONNECTED -> setOf(EventTrigger.CHARGER_ON)
        ObservationType.CHARGER_DISCONNECTED -> setOf(EventTrigger.CHARGER_OFF)
        ObservationType.DEVICE_UNLOCKED -> setOf(EventTrigger.UNLOCK)
        ObservationType.NETWORK_CHANGED -> networkChange(event.network)
        ObservationType.APP_FOREGROUND -> appChange(event.packageName)
        else -> emptySet()
    }

    /** Первое значение сети только запоминается: иначе запуск выглядел бы как подключение. */
    private fun networkChange(next: String?): Set<EventTrigger> {
        val previous = network
        network = next
        return when {
            previous == null || previous == next -> emptySet()
            next == WIFI -> setOf(EventTrigger.WIFI_ON)
            previous == WIFI -> setOf(EventTrigger.WIFI_OFF)
            else -> emptySet()
        }
    }

    private fun appChange(next: String?): Set<EventTrigger> {
        val changed = next != foreground
        foreground = next
        return if (changed && !next.isNullOrEmpty()) setOf(EventTrigger.APP_OPEN) else emptySet()
    }

    private companion object {
        const val DEFAULT_COOLDOWN_MS = 60_000L
        const val WIFI = "wifi"
    }
}
