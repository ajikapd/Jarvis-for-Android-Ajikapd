package com.jarvis.core.llm

import java.security.MessageDigest

class UnifiedContextBuilder {

    fun build(input: ContextBuildInput): AssembledLlmContext {
        val t0 = System.nanoTime()
        val sections = mutableListOf<String>()
        val memSources = mutableListOf<String>()

        val memLimit = when (input.budget) {
            LlmContextBudget.FAST -> 2
            LlmContextBudget.BALANCED -> 4
            LlmContextBudget.QUALITY -> 6
            LlmContextBudget.REASONING -> 8
        }
        val allMem = buildList {
            addAll(input.longTermMemorySnippets.map { it.copy(source = it.source.ifBlank { "long_term" }) })
            addAll(input.companionMemorySnippets.map { it.copy(source = it.source.ifBlank { "companion" }) })
            addAll(input.memory2Snippets.map { it.copy(source = it.source.ifBlank { "memory2" }) })
        }
        val rankedMem = MemoryContextRetriever.rank(
            input.userText,
            allMem,
            if (MemoryContextRetriever.isAboutUser(input.userText)) ABOUT_USER_LIMIT else memLimit,
            input.cloudMemoryAllowed,
        )
        rankedMem.mapTo(memSources) { it.source }
        if (input.longTermMemorySnippets.isNotEmpty()) memSources += "long_term"
        if (input.companionMemorySnippets.isNotEmpty()) memSources += "companion"
        if (input.memory2Snippets.isNotEmpty()) memSources += "memory2"

        val history = ConversationHistorySelector.select(
            input.conversationLines,
            input.userText,
            input.budget,
        )
        val lastAsst = history.lastOrNull { it.startsWith("assistant:", ignoreCase = true) }
            ?.substringAfter(':')?.trim().orEmpty()
        val prevUser = history.mapNotNull { line ->
            if (line.startsWith("user:", ignoreCase = true)) line.substringAfter(':').trim() else null
        }.dropLast(1).lastOrNull().orEmpty()
        val refHint = input.referenceHint
            ?: ConversationHistorySelector.referenceHint(input.userText, lastAsst, prevUser)

        val maxDataChars = when (input.budget) {
            LlmContextBudget.FAST -> 420
            LlmContextBudget.BALANCED -> 900
            LlmContextBudget.QUALITY -> 1600
            LlmContextBudget.REASONING -> 2400
        }

        val memBlock = MemoryContextRetriever.formatBlock(rankedMem, maxDataChars / 3)
        if (memBlock.isNotBlank()) sections += "memory"

        val (deviceBlock, deviceIncluded) = DeviceContextGating.format(
            input.phoneContext,
            input.userText,
            maxDataChars / 4,
        )
        if (deviceIncluded) sections += "device"
        val appIncluded = input.phoneContext?.currentApp != null && deviceIncluded

        val capLines = if (CapabilityContextSelector.needsCapabilities(input.userText, input.kind)) {
            CapabilityContextSelector.formatLines(
                input.capabilityLines,
                maxLines = when (input.budget) {
                    LlmContextBudget.FAST -> 3
                    else -> 6
                },
                maxChars = maxDataChars / 3,
            )
        } else {
            emptyList()
        }
        if (capLines.isNotEmpty()) sections += "capabilities"

        val taskLines = buildList {
            input.taskGoal?.takeIf { it.isNotBlank() }?.let { add("цель: ${it.take(80)}") }
            if (input.taskConstraints.isNotEmpty()) {
                add("ограничения: ${input.taskConstraints.take(4).joinToString()}")
            }
            input.pendingAction?.let { add("ожидает уточнения: ${it.phase.name}") }
            input.lastToolCall?.let {
                add("последнее действие (статус не подтверждён): ${it.toolId}")
            }
            refHint?.let { add("ссылки: $it") }
        }
        if (taskLines.isNotEmpty() && input.kind != LlmContextKind.CHAT) sections += "task"
        if (refHint != null && input.kind == LlmContextKind.CHAT) sections += "references"

        val untrusted = buildString {
            if (memBlock.isNotBlank()) {
                appendLine("[Память]")
                appendLine(memBlock)
            }
            if (deviceBlock.isNotBlank()) {
                appendLine("[Устройство]")
                appendLine(deviceBlock)
            }
            if (capLines.isNotEmpty()) {
                appendLine("[Возможности (справочно)]")
                capLines.forEach { appendLine("- $it") }
            }
            if (taskLines.isNotEmpty()) {
                appendLine("[Задача]")
                taskLines.forEach { appendLine("- $it") }
            }
        }.trim().take(maxDataChars)

        val system = JarvisSystemPrompt.build(input.persona, input.kind, input.budget)
        val legacyMem = rankedMem.joinToString("; ") { it.text.take(48) }.take(120)

        val fullSystem = if (untrusted.isBlank()) system else {
            "$system\n\n--- Контекст ---\n$untrusted\n---"
        }.take(when (input.budget) {
            LlmContextBudget.FAST -> 900
            LlmContextBudget.BALANCED -> 1800
            LlmContextBudget.QUALITY -> 3200
            LlmContextBudget.REASONING -> 4500
        })

        val diagChars = fullSystem.length + input.userText.length +
            history.sumOf { it.length }
        val diag = ContextBuildDiagnostics(
            buildMs = (System.nanoTime() - t0) / 1_000_000,
            systemPromptVersion = JarvisSystemPrompt.VERSION,
            promptVersion = "ctx-${JarvisSystemPrompt.VERSION}",
            contextSections = sections.distinct(),
            contextChars = diagChars,
            estimatedInputTokens = LlmTokenEstimate.charsToTokens(diagChars),
            memoryCount = rankedMem.size,
            memorySources = memSources.distinct(),
            historyTurns = history.size,
            capabilityCount = capLines.size,
            toolSchemaCount = capLines.size,
            deviceContextIncluded = deviceIncluded,
            currentAppIncluded = appIncluded,
            systemPromptHash = sha256Prefix(system),
        )

        return AssembledLlmContext(
            systemPrompt = fullSystem,
            untrustedDataBlock = untrusted,
            userMessage = input.userText,
            historyLines = history,
            legacyMemoriesSnippet = legacyMem,
            diagnostics = diag,
            outputBudget = input.budget,
        )
    }

    private fun sha256Prefix(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return d.take(4).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ABOUT_USER_LIMIT = 8
    }
}
