package com.jarvis.core.memory

/**
 * Типы памяти (заложены в Stage 5; не все механизмы реализованы).
 * Не смешивать с PhoneContext / PhoneContextBus.
 */
enum class MemoryKind {
    WORKING,
    EPISODIC,
    SEMANTIC,
    PROCEDURAL,
    PREFERENCE,
    ROUTINE,
}

/** Provenance кандидата в память — не путать с ObservationSource. */
enum class MemoryProvenance {
    /** Выведено из наблюдений телефона. */
    OBSERVED,
    /** Пользователь явно сказал. */
    USER_STATED,
    /** Вывод модели / эвристики (пока не используем LLM). */
    INFERRED,
}

enum class MemoryDisposition {
    REJECT,
    TEMPORARY,
    STORE,
}
