package com.jarvis.screencontrol

import com.jarvis.core.privacy.SensitivityClassifier

/**
 * Правила приватности для RAM-кэша уведомлений (чистый Kotlin, без Android —
 * чтобы правила можно было покрыть unit-тестами).
 *
 * Кэш живёт в памяти процесса и нужен только для показа текущих уведомлений
 * (NotificationTool, ответы/снятие). Поэтому:
 * - содержимое уведомлений чувствительных пакетов (банки, платёжные приложения,
 *   менеджеры паролей, аутентификаторы) не сохраняется вообще — вместо него
 *   остаётся маркер [MASK];
 * - любой текст проходит через [SensitivityClassifier.mask] (коды и номера карт);
 * - длина текста ограничена [MAX_TEXT_CHARS];
 * - число записей ограничено [MAX_CACHE_ENTRIES] (FIFO, вытесняется самая старая).
 */
internal object NotificationPrivacy {

    /** Замена скрытого фрагмента/содержимого. */
    const val MASK: String = SensitivityClassifier.MASK

    /** Лимит записей в RAM-кэше уведомлений. */
    const val MAX_CACHE_ENTRIES: Int = 50

    /** Лимит длины сохраняемого текста уведомления. */
    const val MAX_TEXT_CHARS: Int = 200

    /** Длина заголовка в summary наблюдения. */
    private const val SUMMARY_TITLE_CHARS: Int = 40

    /** Содержимое уведомления в том виде, в котором его можно держать в памяти. */
    fun sanitize(raw: CharSequence?, packageName: String?): String {
        val text = raw?.toString().orEmpty()
        if (SensitivityClassifier.isSensitivePackage(packageName)) {
            return if (text.isBlank()) "" else MASK
        }
        return SensitivityClassifier.mask(text).take(MAX_TEXT_CHARS)
    }

    /** Короткий заголовок для summary наблюдения (тоже без секретов). */
    fun summaryTitle(raw: CharSequence?, packageName: String?): String {
        val safe = sanitize(raw, packageName)
        return safe.take(SUMMARY_TITLE_CHARS).let { if (safe.length > SUMMARY_TITLE_CHARS) "$it…" else it }
    }
}
