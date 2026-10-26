package com.jarvis.core.common

import com.jarvis.core.common.model.Intent
import com.jarvis.core.common.model.UserRequest

/**
 * Превращает UserRequest в структурированный Intent.
 * Детерминированный resolver — первый быстрый слой; LLM подключается позже
 * для сложных формулировок (deterministic-first, раздел 6 AGENT_START).
 */
fun interface IntentResolver {
    fun resolve(request: UserRequest): Intent
}