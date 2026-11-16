package com.jarvis.core.event

import com.jarvis.core.common.model.Intent
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.model.AssistantState

/**
 * События ассистента на шине JarvisEvents.
 * Sealed-набор расширяется по мере роста конвейера, не ломая подписчиков:
 * WakeWordDetected → SpeechCaptured → Transcribed → IntentResolved
 * → ToolCalled/ToolExecuted → ResponseGenerated → Spoken
 */
sealed interface AssistantEvent {

    data class StateChanged(val state: AssistantState) : AssistantEvent

    data class TranscriptUpdated(val text: String) : AssistantEvent

    data object WakeDetected : AssistantEvent

    data class RequestReceived(val text: String) : AssistantEvent

    data class ResponseGenerated(val text: String) : AssistantEvent

    data class ServiceStarted(val uptimeTag: String) : AssistantEvent

    data class IntentResolved(val intent: Intent) : AssistantEvent

    data class ToolExecuted(val result: ToolResult) : AssistantEvent

    data class Error(
        val source: String,
        val message: String,
        val throwable: Throwable? = null
    ) : AssistantEvent
}