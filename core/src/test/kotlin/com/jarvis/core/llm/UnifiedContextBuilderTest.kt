package com.jarvis.core.llm

import com.jarvis.core.companion.PhoneContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedContextBuilderTest {

    private val builder = UnifiedContextBuilder()

    @Test
    fun generalChatOmitsMemoryWhenIrrelevant() {
        val out = builder.build(
            ContextBuildInput(
                userText = "Объясни, почему небо голубое",
                budget = LlmContextBudget.BALANCED,
                longTermMemorySnippets = listOf(
                    MemorySnippet("VPN Proton", source = "long_term", score = 0.1f),
                ),
            ),
        )
        assertFalse(out.diagnostics.contextSections.contains("memory"))
    }

    @Test
    fun memoryQuestionIncludesRelevantMemory() {
        val out = builder.build(
            ContextBuildInput(
                userText = "Какой VPN я обычно использую?",
                budget = LlmContextBudget.BALANCED,
                longTermMemorySnippets = listOf(
                    MemorySnippet("обычно использую VPN Proton", source = "long_term", score = 0.5f),
                ),
            ),
        )
        assertTrue(out.diagnostics.contextSections.contains("memory"))
        assertTrue(out.untrustedDataBlock.contains("VPN", ignoreCase = true))
    }

    @Test
    fun cloudPrivacyFiltersPrivateCompanionMemory() {
        val ranked = MemoryContextRetriever.rank(
            "как меня зовут",
            listOf(
                MemorySnippet("зовут Алекс", source = "companion", sensitivity = "PRIVATE", score = 0.9f),
                MemorySnippet("любит кофе", source = "companion", sensitivity = "NORMAL", score = 0.5f),
            ),
            limit = 4,
            cloudAllowed = false,
        )
        assertTrue(ranked.none { it.text.contains("Алекс") })
        assertTrue(ranked.any { it.text.contains("кофе") })
    }

    @Test
    fun deviceContextGatedForAudioQuestion() {
        val (block, included) = DeviceContextGating.format(
            PhoneContext(currentApp = "com.spotify.music", headphonesConnected = true, batteryPercent = 40),
            "почему не слышно музыку",
            maxChars = 200,
        )
        assertTrue(included)
        assertTrue(block.contains("spotify"))
        assertTrue(block.contains("наушники"))
    }

    @Test
    fun followUpIncludesReferenceSection() {
        val out = builder.build(
            ContextBuildInput(
                userText = "сравни первый и третий вариант",
                conversationLines = listOf(
                    "user: найди билеты",
                    "assistant: 1) 100€ 2) 120€ 3) 90€",
                ),
                budget = LlmContextBudget.BALANCED,
            ),
        )
        assertTrue(out.diagnostics.contextSections.contains("references"))
    }

    /** Golden multi-turn: follow-up «а первый?» after priced list. */
    @Test
    fun goldenMultiTurn_firstVariantReference() {
        val hist = listOf(
            "user: Найди билеты в Берлин",
            "assistant: 1) 100€ утром 2) 120€ днём 3) 90€ вечером",
            "user: а первый?",
        )
        val out = builder.build(
            ContextBuildInput(
                userText = "а первый?",
                conversationLines = hist,
                budget = LlmContextBudget.BALANCED,
            ),
        )
        assertTrue(out.diagnostics.contextSections.contains("references"))
        assertTrue(out.untrustedDataBlock.contains("100") || out.systemPrompt.contains("100"))
    }
}

class LlmOutputPolicyTest {
    @Test
    fun sentenceAwareTruncatePreservesCompleteSentences() {
        val raw = "Первое предложение. Второе предложение. Третье очень длинное предложение которое не должно полностью попасть."
        val out = LlmOutputPolicy.apply(raw, LlmContextBudget.BALANCED)
        assertTrue(out.length <= LlmOutputPolicy.maxChars(LlmContextBudget.BALANCED))
        assertFalse(out.startsWith("RESULT="))
    }
}
