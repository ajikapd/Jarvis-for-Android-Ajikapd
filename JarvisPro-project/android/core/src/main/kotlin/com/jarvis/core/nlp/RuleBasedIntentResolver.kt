package com.jarvis.core.nlp

import com.jarvis.core.common.IntentResolver
import com.jarvis.core.common.model.ConfidenceLevel
import com.jarvis.core.common.model.Intent
import com.jarvis.core.common.model.IntentType
import com.jarvis.core.common.model.Slot
import com.jarvis.core.common.model.UserRequest

/**
 * Детерминированный разбор намерения по ключевым словам.
 *
 * Быстрый слой для простых команд (deterministic-first, AGENT_START раздел 6).
 * Сейчас покрывает вертикальный срез «включи/выключи фонарик»;
 * расширяется по мере появления новых инструментов.
 */
class RuleBasedIntentResolver(
    private val normalize: (String) -> List<String> = RussianNormalizer::normalizeWords
) : IntentResolver {

    private val lightNouns = setOf(
        "фонарик", "фонарика", "фонарику", "фонариком", "фонарике",
        "фонарь", "фонаря", "фонарю",
        "свет",
        "подсветка", "подсветку", "подсветки", "подсветкой",
        "освещение", "освещения"
    )

    private val onVerbs = setOf(
        "включи", "включить", "включите", "включил",
        "зажги", "зажечь", "зажигай",
        "дай", "дайте", "сделай", "сделаете", "включить"
    )

    private val offVerbs = setOf(
        "выключи", "выключить", "выключите",
        "погаси", "погасить", "потуши", "потушить",
        "отключи", "отключить"
    )

    override fun resolve(request: UserRequest): Intent {
        val words = normalize(request.text)
        if (words.isEmpty()) return Intent.unknown("")

        val nounMatch = words.any { it in lightNouns }
        if (!nounMatch) return Intent.unknown(words.joinToString(" "))

        val wantOn = words.any { it in onVerbs }
        val wantOff = words.any { it in offVerbs }

        val action = when {
            wantOn && !wantOff -> "on"
            wantOff && !wantOn -> "off"
            else -> null // оба глагола или ни одного — неоднозначно
        }

        return if (action != null) {
            Intent(
                type = IntentType.FLASHLIGHT,
                normalizedText = words.joinToString(" "),
                slots = mapOf("action" to Slot(name = "action", value = action)),
                confidence = 0.95f,
                confidenceLevel = ConfidenceLevel.HIGH
            )
        } else {
            Intent.unknown(words.joinToString(" "))
        }
    }
}