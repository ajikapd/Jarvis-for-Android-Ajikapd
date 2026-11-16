package com.jarvis.app

import android.content.Context
import android.os.SystemClock
import com.jarvis.core.common.model.RequestSource
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneContextBus
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.routines.EventMatcher
import com.jarvis.core.routines.EventRule
import com.jarvis.core.routines.PhoneEvent
import com.jarvis.tools.LauncherTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Запускает сценарии по событиям телефона. Подтверждения работают как у голосовых команд. */
class EventRoutineRunner(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = PrefsEventRoutines(context)
    private val routines = PrefsCustomRoutines(context)
    private val launcher = LauncherTool(context)
    private val packages = HashMap<String, String?>()
    private val matcher = EventMatcher({ name, pkg -> packageOf(name) == pkg })

    fun start() {
        scope.launch {
            PhoneContextBus.observations.collect { observation ->
                val rules = store.rules()
                if (rules.isEmpty()) return@collect
                matcher.match(event(observation), rules, SystemClock.elapsedRealtime()).forEach(::launchRule)
            }
        }
    }

    private fun packageOf(name: String): String? = packages.getOrPut(name) { launcher.resolvePackage(name) }

    private fun event(observation: PhoneObservation) = PhoneEvent(
        type = observation.type,
        network = observation.entities[NETWORK],
        packageName = observation.packageName,
        startup = observation.entities[STARTUP] == "true",
    )

    private fun launchRule(rule: EventRule) {
        scope.launch {
            if (routines.steps(rule.name) == null) return@launch
            // Не перебиваем разговор: ждём, пока Джарвис договорит и дослушает.
            repeat(WAIT_POLLS) {
                if (VoiceController.isIdle) return@repeat
                delay(WAIT_POLL_MS)
            }
            val ready = VoiceController.isRunning && VoiceController.isIdle
            VoiceHealthLog.write(context, "event rule ${rule.trigger} run=$ready")
            if (ready) VoiceController.notifyRequest(UserRequest(rule.name, source = RequestSource.AUTOMATION))
        }
    }

    companion object {
        const val NETWORK = "network"
        const val STARTUP = "startup"
        private const val WAIT_POLLS = 15
        private const val WAIT_POLL_MS = 2000L
    }
}
