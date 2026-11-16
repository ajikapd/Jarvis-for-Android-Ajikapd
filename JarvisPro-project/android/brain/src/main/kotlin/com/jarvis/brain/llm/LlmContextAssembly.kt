package com.jarvis.brain.llm

import com.jarvis.core.companion.PhoneContext
import com.jarvis.core.companion.Sensitivity
import com.jarvis.core.discovery.CatalogToolDiscovery
import com.jarvis.core.discovery.DiscoveryContext
import com.jarvis.core.llm.ContextBuildInput
import com.jarvis.core.llm.LlmContextBudget
import com.jarvis.core.llm.LlmContextKind
import com.jarvis.core.llm.MemorySnippet
import com.jarvis.core.memory.Memory2Store
import com.jarvis.core.memory.MemoryRecall
import com.jarvis.brain.LongTermMemoryInterface
import com.jarvis.core.common.model.ConversationContext

/**
 * V2-04: gather snippets from live stores → [ContextBuildInput].
 */
class LlmContextAssembly(
    private val longTermMemory: LongTermMemoryInterface? = null,
    private val companionRecall: MemoryRecall? = null,
    private val memory2: Memory2Store? = null,
    private val toolDiscovery: CatalogToolDiscovery? = null,
    private val phoneContext: () -> PhoneContext? = { null },
) {

    fun gatherInput(
        userText: String,
        conversationLines: List<String>,
        persona: String,
        kind: LlmContextKind = LlmContextKind.CHAT,
        budget: LlmContextBudget = LlmContextBudget.BALANCED,
        cloudMemoryAllowed: Boolean = true,
        chatProfile: String = "BALANCED",
        pendingContext: ConversationContext? = null,
    ): ContextBuildInput {
        val resolvedBudget = budgetFromProfile(chatProfile, budget)
        val found = longTermMemory?.retrieve(userText, limit = 6).orEmpty()
        val facts = if (com.jarvis.core.llm.MemoryContextRetriever.isAboutUser(userText)) {
            (found + longTermMemory?.all().orEmpty()).distinctBy { it.id }.take(ABOUT_USER_FACTS)
        } else {
            found
        }
        val lt = facts.map {
            MemorySnippet(
                text = it.text,
                source = "long_term",
                sensitivity = if (it.category.equals("private", ignoreCase = true)) "PRIVATE" else "NORMAL",
                score = 0.3f,
            )
        }
        val companion = companionRecall?.let { recall ->
            val q = userText.trim()
            val fromSearch = if (q.length >= 3) recall.textSearch(q, limit = 6) else emptyList()
            val prefs = recall.preferences().take(4)
            (fromSearch + prefs).distinctBy { it.id }.map {
                MemorySnippet(
                    text = it.content,
                    source = "companion",
                    sensitivity = it.sensitivity.name,
                    score = it.confidence,
                )
            }
        }.orEmpty()

        val m2 = memory2?.searchRelevant(userText, limit = 4).orEmpty().map {
            MemorySnippet(text = it.text, source = "memory2", score = it.score)
        }

        val caps = toolDiscovery?.discover(
            DiscoveryContext(query = userText, limit = 6),
        )?.items?.map { item ->
            "${item.toolId}: ${item.description.take(80)}"
        }.orEmpty()

        return ContextBuildInput(
            userText = userText,
            kind = kind,
            budget = resolvedBudget,
            persona = persona,
            conversationLines = conversationLines,
            longTermMemorySnippets = lt,
            companionMemorySnippets = companion.filter {
                cloudMemoryAllowed || it.sensitivity !in setOf(
                    Sensitivity.PRIVATE.name,
                    Sensitivity.SECRET.name,
                )
            },
            memory2Snippets = m2,
            phoneContext = phoneContext(),
            capabilityLines = caps,
            cloudMemoryAllowed = cloudMemoryAllowed,
            pendingAction = pendingContext?.pendingAction,
            lastToolCall = pendingContext?.lastToolCall,
        )
    }

    private companion object {
        const val ABOUT_USER_FACTS = 12
    }

    private fun budgetFromProfile(profile: String, fallback: LlmContextBudget): LlmContextBudget =
        when (profile.uppercase()) {
            "FAST" -> LlmContextBudget.FAST
            "QUALITY" -> LlmContextBudget.QUALITY
            "REASONING" -> LlmContextBudget.REASONING
            else -> fallback
        }
}
