package com.jarvis.core.proactive

import com.jarvis.core.privacy.SensitivityClassifier

/**
 * Б2: тексты проактивного предложения. Чистый Kotlin, без LLM и Android.
 *
 * Никаких patternKey / reasonCode / технических деталей в тексте: только
 * короткий маскированный reason. Обращение — одна константа [ADDRESS]
 * (по умолчанию пусто; позже можно поставить «сэр»).
 */
data class ProactiveText(
    val voice: String,
    val title: String,
    val body: String,
)

object ProactiveTextBuilder {

    /** Обращение к пользователю. Пусто = без обращения. */
    const val ADDRESS: String = ""

    /** Лимит голосовой фразы: столько же, сколько у обычного ответа (JarvisSpeaker). */
    const val MAX_VOICE_CHARS: Int = 160

    /** Сколько символов reason оставляем до подстановки в шаблон. */
    private const val MAX_REASON_CHARS: Int = 100

    fun build(type: ProactiveDecisionType, reason: String): ProactiveText {
        if (type == ProactiveDecisionType.NO_OP) {
            return ProactiveText(voice = "", title = "", body = "")
        }
        val cleaned = cleanReason(reason).ifBlank { defaultReason(type) }
        val address = if (ADDRESS.isBlank()) "" else "$ADDRESS, "
        val text = when (type) {
            ProactiveDecisionType.SUGGESTION -> ProactiveText(
                voice = "${address}Я заметил: $cleaned. Хочешь, помогу?",
                title = "Jarvis: предложение",
                body = "$cleaned. Хочешь, помогу?",
            )
            ProactiveDecisionType.QUESTION -> ProactiveText(
                voice = "${address}Можно вопрос: $cleaned?",
                title = "Jarvis: вопрос",
                body = "$cleaned?",
            )
            ProactiveDecisionType.CONFIRMATION_REQUEST -> ProactiveText(
                voice = "${address}Подтверди, пожалуйста: $cleaned.",
                title = "Jarvis: подтверждение",
                body = "$cleaned. Подтверждаешь?",
            )
            ProactiveDecisionType.NO_OP -> ProactiveText("", "", "")
        }
        return text.copy(voice = clamp(text.voice))
    }

    /**
     * reason проходит через mask() (коды/карты), схлопывается по пробелам,
     * обрезается по границе слова и теряет финальную пунктуацию — шаблон
     * добавляет свою.
     */
    private fun cleanReason(reason: String): String {
        val masked = SensitivityClassifier.mask(reason)
            .replace(Regex("\\s+"), " ")
            .trim()
        if (masked.isEmpty()) return ""
        val cut = if (masked.length <= MAX_REASON_CHARS) {
            masked
        } else {
            val head = masked.take(MAX_REASON_CHARS)
            val lastSpace = head.lastIndexOf(' ')
            (if (lastSpace > 40) head.substring(0, lastSpace) else head).trimEnd()
        }
        return cut.trimEnd('.', ',', ';', ':', '!', '?', '—', '–', '-')
    }

    private fun defaultReason(type: ProactiveDecisionType): String = when (type) {
        ProactiveDecisionType.SUGGESTION -> "похоже, тебе может пригодиться помощь"
        ProactiveDecisionType.QUESTION -> "есть кое-что, что стоит уточнить"
        ProactiveDecisionType.CONFIRMATION_REQUEST -> "нужно твоё подтверждение"
        ProactiveDecisionType.NO_OP -> ""
    }

    private fun clamp(text: String): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= MAX_VOICE_CHARS) return normalized
        return normalized.take(MAX_VOICE_CHARS - 1).trimEnd() + "…"
    }
}
