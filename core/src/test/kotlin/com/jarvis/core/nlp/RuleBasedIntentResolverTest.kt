package com.jarvis.core.nlp

import com.jarvis.core.common.model.ConfidenceLevel
import com.jarvis.core.common.model.IntentType
import com.jarvis.core.common.model.UserRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedIntentResolverTest {

    private val resolver = RuleBasedIntentResolver()

    private fun resolve(text: String) = resolver.resolve(UserRequest(text))

    @Test
    fun `включи фонарик resolves to FLASHLIGHT on`() {
        val intent = resolve("включи фонарик")
        assertEquals(IntentType.FLASHLIGHT, intent.type)
        assertEquals("on", intent.slots["action"]?.value)
        assertEquals(ConfidenceLevel.HIGH, intent.confidenceLevel)
        assertTrue(intent.confidence > 0.9f)
    }

    @Test
    fun `выключи фонарь resolves to FLASHLIGHT off`() {
        val intent = resolve("выключи фонарь")
        assertEquals(IntentType.FLASHLIGHT, intent.type)
        assertEquals("off", intent.slots["action"]?.value)
    }

    @Test
    fun `включи свет resolves to FLASHLIGHT`() {
        val intent = resolve("дай свет")
        assertEquals(IntentType.FLASHLIGHT, intent.type)
        assertEquals("on", intent.slots["action"]?.value)
    }

    @Test
    fun `irrelevant text resolves UNKNOWN`() {
        val intent = resolve("расскажи мне про иронию судьбы")
        assertEquals(IntentType.UNKNOWN, intent.type)
    }

    @Test
    fun `empty text resolves UNKNOWN`() {
        assertEquals(IntentType.UNKNOWN, resolve("   ").type)
    }
}