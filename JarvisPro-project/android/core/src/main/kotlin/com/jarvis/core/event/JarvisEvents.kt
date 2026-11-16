package com.jarvis.core.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Событийная шина Jarvis. Все модули общаются только через неё
 * (см. раздел 5 мастер-промпта), избегая прямых ссылок друг на друга.
 *
 * Конвейер ассистента:
 * WakeWordDetected → SpeechCaptured → Transcribed → IntentResolved
 * → ActionExecuted/ToolCalled → ResponseGenerated → Spoken
 */
object JarvisEvents {

    private val _events = MutableSharedFlow<AssistantEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private val _assistantState = MutableStateFlow(com.jarvis.core.model.AssistantState.OFFLINE)
    val assistantState = _assistantState.asStateFlow()

    /** Отправить событие в шину. Never suspends thanks to extraBufferCapacity. */
    fun publish(event: AssistantEvent) {
        if (event is AssistantEvent.StateChanged) {
            _assistantState.value = event.state
        }
        _events.tryEmit(event)
    }
}