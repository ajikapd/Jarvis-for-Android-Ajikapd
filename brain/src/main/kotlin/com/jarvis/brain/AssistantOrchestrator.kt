package com.jarvis.brain

import com.jarvis.core.common.ToolExecutor
import com.jarvis.core.common.ToolPolicy
import com.jarvis.core.common.ToolRegistry
import com.jarvis.core.common.model.AssistantResponse
import com.jarvis.core.common.model.AssistantResponseStatus
import com.jarvis.core.common.model.ConversationContext
import com.jarvis.core.common.model.PendingAction
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.event.AssistantEvent
import com.jarvis.core.event.JarvisEvents
import com.jarvis.core.model.AssistantState
import com.jarvis.core.nlp.AsrPhraseNormalizer
import com.jarvis.core.nlp.Transliterator
import com.jarvis.core.privacy.SensitivityClassifier
import com.jarvis.core.storage.ConversationJournal
import com.jarvis.core.runtime.traceLabel
import com.jarvis.core.runtime.applyStructured
import com.jarvis.core.runtime.applyLatency
import com.jarvis.core.skills.SkillRetriever

/**
 * Оркестратор ассистента. Поддерживает отложенные действия:
 * подтверждение («да»/«нет») и выбор контакта при неоднозначности.
 */
class AssistantOrchestrator(
    private val brainRouter: BrainRouter,
    private val skillRetriever: SkillRetriever,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val toolPolicy: ToolPolicy,
    private val context: ConversationContext,
    private val longTermMemory: LongTermMemoryInterface,
    private val dialogue: DialogueResponder,
    private val executeThreshold: Float = 0.75f,
    private val clarifyThreshold: Float = 0.4f,
    private val memoryEnabled: Boolean = true,
    private val memoryProvider: (String) -> String = { "" },
    /** Поиск контактов (name → number). Пустой список = нет доступа/совпадений. */
    private val resolveContacts: (String) -> List<PendingAction.Choice> = { emptyList() },
    /** Increment B: discovery subset возможностей (опционально). */
    private val capabilityDiscovery: com.jarvis.core.capability.CapabilityDiscovery? = null,
    /**
     * V2-01: единый execution/safety path.
     * null → создаётся из [toolExecutor] (RiskBasedPolicy → AutonomyActionPipeline).
     */
    executionOrchestrator: com.jarvis.core.runtime.ExecutionOrchestrator? = null,
    /** V2-01: PLAN boundary (optional). */
    private val planPathCoordinator: com.jarvis.core.runtime.PlanPathCoordinator? = null,
    /** V2-01: catalog discovery for PLAN path. */
    private val catalogToolDiscovery: com.jarvis.core.discovery.CatalogToolDiscovery? = null,
    /** V2-02: fast local semantic understanding. */
    private val fastSemantic: com.jarvis.core.runtime.semantic.SemanticInterpreter =
        com.jarvis.core.runtime.semantic.FastSemanticClassifier(),
    /** V2-02: optional LLM structured semantic fallback. */
    private val llmSemantic: com.jarvis.core.runtime.semantic.SemanticInterpreter? = null,
    /** V2-04: unified LLM context assembly. */
    private val unifiedContextBuilder: com.jarvis.core.llm.UnifiedContextBuilder =
        com.jarvis.core.llm.UnifiedContextBuilder(),
    private val llmContextAssembly: com.jarvis.brain.llm.LlmContextAssembly? = null,
    private val chatPersona: () -> String = { "CLASSIC" },
    private val chatProfile: () -> String = { "BALANCED" },
    private val cloudMemoryAllowed: () -> Boolean = { true },
    /** V2-07: real CHAT streaming pipeline (optional). */
    private val chatStreamPipeline: com.jarvis.brain.aiprovider.ChatStreamPipeline? = null,
    private val isOnlineForAi: () -> Boolean = { false },
    private val preferCloudForAi: () -> Boolean = { false },
    /** Манера коротких подтверждений; в тестах ответ инструмента остаётся как есть. */
    private val confirmationStyle: (String) -> String = { it },
) {
    private val execution: com.jarvis.core.runtime.ExecutionOrchestrator =
        executionOrchestrator
            ?: com.jarvis.core.runtime.ExecutionOrchestrator(toolExecutor = toolExecutor)

    /** Last request trace (diagnostics / tests). */
    @Volatile
    var lastTrace: com.jarvis.core.runtime.RequestTrace? = null
        private set

    @Volatile
    var lastChatStreamResult: com.jarvis.brain.aiprovider.ChatStreamResult? = null
        private set

    /**
     * V2-07: if semantic route is CHAT and streaming pipeline is available,
     * stream sentences to [onSentence] and return final [AssistantResponse].
     * Returns null when streaming should not be used (FAST_SKILL/TEMPLATE/etc.).
     */
    fun processStreamingChat(
        request: UserRequest,
        generationId: Long,
        isGenerationStale: () -> Boolean,
        onSentence: (sentence: String, first: Boolean) -> Boolean,
    ): AssistantResponse? {
        val pipeline = chatStreamPipeline ?: return null
        if (!isOnlineForAi() || !preferCloudForAi()) return null

        val startedAt = System.nanoTime()
        val wallStart = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        val latency = com.jarvis.core.runtime.InteractionLatency(
            requestId = request.requestId,
            anchorElapsedMs = wallStart,
        )
        val trace = com.jarvis.core.runtime.RequestTrace(
            requestId = request.requestId,
            timestampMillis = request.timestampMillis,
            inputType = request.source.name,
            inputPreview = request.text.take(80),
            interactionLatency = latency,
        )
        lastTrace = trace
        latency.normalizeStartAt = wallStart
        val normalizedText = AsrPhraseNormalizer.normalize(request.text)
        latency.normalizeEndAt = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        val normalizedRequest = if (normalizedText == request.text) request
            else request.copy(text = normalizedText)

        if (context.pendingAction != null) return null

        // Do not mutate conversation until CHAT streaming is confirmed
        val earlyFollowUp = FollowUpResolver.resolve(normalizedRequest.text, context.lastToolCall)
        if (earlyFollowUp != null) return null

        val semantic = understandSemantic(request.text, normalizedRequest.text, trace) ?: return null
        val route = com.jarvis.core.runtime.semantic.SemanticRouteSelector.select(semantic)
        applySemanticToTrace(trace, semantic, route)
        if (route != com.jarvis.core.runtime.RouteDecision.CHAT) return null

        rememberUser(normalizedRequest.text)
        state(AssistantState.THINKING)

        val assembled = if (memoryEnabled && llmContextAssembly != null) {
            latency.contextStartAt = com.jarvis.core.runtime.LatencyClock.elapsedMs()
            val input = llmContextAssembly.gatherInput(
                userText = normalizedRequest.text,
                conversationLines = context.messages,
                persona = chatPersona(),
                cloudMemoryAllowed = cloudMemoryAllowed(),
                chatProfile = chatProfile(),
                pendingContext = context,
            )
            val a = unifiedContextBuilder.build(input)
            latency.contextEndAt = com.jarvis.core.runtime.LatencyClock.elapsedMs()
            a
        } else {
            null
        }

        latency.llmRequestStartAt = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        val profile = com.jarvis.brain.aiprovider.AiTaskProfile.forChat(
            isOnline = true,
            preferCloud = true,
            complexity = com.jarvis.brain.aiprovider.AIRequestComplexity.SIMPLE,
            requestId = request.requestId,
            userTextLen = normalizedRequest.text.length,
        )
        val aiReq = com.jarvis.brain.aiprovider.AICompletionRequest(
            requestId = request.requestId,
            userText = normalizedRequest.text,
            history = assembled?.historyLines ?: context.messages,
            memories = assembled?.legacyMemoriesSnippet.orEmpty(),
            persona = chatPersona(),
            assembledContext = assembled,
        )
        val streamResult = pipeline.streamChat(
            profile = profile,
            request = aiReq,
            generationId = generationId,
            isGenerationStale = isGenerationStale,
            sink = com.jarvis.brain.aiprovider.ChatSentenceSink { sentence, first ->
                onSentence(sentence, first)
            },
        )
        lastChatStreamResult = streamResult
        latency.llmCompleteAt = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        latency.streamingUsed = streamResult.streamed
        latency.ttftKind = streamResult.ttftKind
        if (streamResult.ttftKind == com.jarvis.core.runtime.TtftKind.REAL && streamResult.ttftMs != null) {
            latency.llmFirstTokenAt = latency.llmRequestStartAt!! + streamResult.ttftMs
            latency.llmFirstByteAt = latency.llmFirstTokenAt
        }
        latency.fallback = streamResult.fallbackUsed

        if (streamResult.cancelled || isGenerationStale()) {
            latency.cancelled = true
            rememberReply(streamResult.fullText.takeIf { it.isNotBlank() }?.let { "$it…" } ?: "…")
            return finalizeTrace(
                AssistantResponse(
                    requestId = request.requestId,
                    replyText = streamResult.fullText,
                    status = AssistantResponseStatus.PROCESSED,
                    durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                    route = com.jarvis.core.runtime.RouteDecision.CHAT.name,
                    routeReason = "stream_cancelled",
                    provider = streamResult.providerId,
                    model = streamResult.modelId,
                    fallbackUsed = streamResult.fallbackUsed,
                ),
                trace,
                startedAt,
            ).also {
                trace.streamingUsed = streamResult.streamed
                trace.streamRequested = true
                trace.streamSupported = true
                trace.streamFallback = streamResult.fallbackUsed
                trace.streamFallbackReason = streamResult.fallbackReason
                trace.cancelled = true
                trace.aiTtftKind = streamResult.ttftKind.name
                trace.aiTtftMs = streamResult.ttftMs
                trace.aiCompletionMs = streamResult.latencyMs
            }
        }

        cloudToolResponse(normalizedRequest, streamResult.fullText, startedAt, trace)?.let {
            return finalizeTrace(styled(it), trace, startedAt)
        }

        if (streamResult.failed && streamResult.fullText.isBlank()) {
            // Stay on this turn — use classic non-stream CHAT (user already recorded)
            return finalizeTrace(
                respondChat(normalizedRequest, startedAt, trace),
                trace,
                startedAt,
            )
        }

        val text = streamResult.fullText.ifBlank { "Готово." }
        val policyText = com.jarvis.core.llm.LlmOutputPolicy.apply(
            text,
            com.jarvis.core.llm.LlmContextBudget.BALANCED,
        )
        val factRefused = rememberIfFact(normalizedRequest)
        val finalText = if (factRefused) FactExtractor.REFUSAL else policyText
        rememberReply(finalText)
        state(AssistantState.READY)
        trace.route = com.jarvis.core.runtime.RouteDecision.CHAT
        trace.routeReason = if (streamResult.streamed) "chat_stream" else "chat_stream_fallback"
        trace.provider = streamResult.providerId
        trace.model = streamResult.modelId
        trace.fallback = streamResult.fallbackUsed
        trace.streamingUsed = streamResult.streamed
        trace.streamRequested = true
        trace.streamSupported = true
        trace.streamFallback = streamResult.fallbackUsed
        trace.streamFallbackReason = streamResult.fallbackReason
        trace.aiTtftKind = streamResult.ttftKind.name
        trace.aiTtftMs = streamResult.ttftMs
        trace.aiCompletionMs = streamResult.latencyMs
        trace.aiLatencyMs = streamResult.latencyMs
        latency.firstUsefulAt = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        return finalizeTrace(
            AssistantResponse(
                requestId = request.requestId,
                replyText = finalText,
                status = AssistantResponseStatus.PROCESSED,
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                route = com.jarvis.core.runtime.RouteDecision.CHAT.name,
                routeReason = trace.routeReason,
                provider = streamResult.providerId,
                model = streamResult.modelId,
                fallbackUsed = streamResult.fallbackUsed,
            ),
            trace,
            startedAt,
        )
    }

    fun cancelActiveChatStream() {
        chatStreamPipeline?.cancelActive()
    }

    fun process(request: UserRequest): AssistantResponse = styled(processRequest(request))

    /** Стиль только у выполненного действия: разговор и вопросы модель формулирует сама. */
    private fun styled(response: AssistantResponse): AssistantResponse {
        val executed = response.toolResult?.status == com.jarvis.core.common.model.ToolResultStatus.SUCCESS
        if (!executed || response.status != AssistantResponseStatus.PROCESSED) return response
        return response.copy(replyText = confirmationStyle(response.replyText))
    }

    private fun processRequest(request: UserRequest): AssistantResponse {
        val startedAt = System.nanoTime()
        val wallStart = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        val latency = com.jarvis.core.runtime.InteractionLatency(
            requestId = request.requestId,
            anchorElapsedMs = wallStart,
        )
        val trace = com.jarvis.core.runtime.RequestTrace(
            requestId = request.requestId,
            timestampMillis = request.timestampMillis,
            inputType = request.source.name,
            inputPreview = request.text.take(80),
            interactionLatency = latency,
        )
        lastTrace = trace
        latency.normalizeStartAt = wallStart
        val normalizedText = AsrPhraseNormalizer.normalize(request.text)
        latency.normalizeEndAt = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        val normalizedRequest = if (normalizedText == request.text) request
            else request.copy(text = normalizedText)

        val pending = context.pendingAction
        if (pending != null) {
            val text = normalizedRequest.text.lowercase().trim()
            val fresh = !isYes(text) && !isNo(text) && looksLikeFreshCommand(text, pending)
            if (!fresh) {
                rememberUser(normalizedRequest.text)
                val resp = handlePending(normalizedRequest, pending, startedAt, trace)
                return finalizeTrace(resp, trace, startedAt)
            }
            context.clearPending()
        }

        rememberUser(normalizedRequest.text)
        state(AssistantState.THINKING)

        // Follow-up («выключи», «тише», «а в Донецке») — до semantic / incomplete
        val earlyFollowUp = FollowUpResolver.resolve(normalizedRequest.text, context.lastToolCall)
        if (earlyFollowUp != null) {
            return finalizeTrace(
                runTool(
                    normalizedRequest,
                    earlyFollowUp,
                    startedAt,
                    trace = trace,
                    routeHint = com.jarvis.core.runtime.RouteDecision.FAST_SKILL,
                    routeReason = "follow_up",
                ),
                trace,
                startedAt,
            )
        }

        // V2-02: semantic-first understanding → RouteDecision
        val semantic = understandSemantic(request.text, normalizedRequest.text, trace)
        if (semantic != null) {
            val route = com.jarvis.core.runtime.semantic.SemanticRouteSelector.select(semantic)
            applySemanticToTrace(trace, semantic, route)
            when (route) {
                com.jarvis.core.runtime.RouteDecision.TEMPLATE ->
                    return finalizeTrace(
                        respondChat(normalizedRequest, startedAt, trace),
                        trace,
                        startedAt,
                    )
                com.jarvis.core.runtime.RouteDecision.CHAT ->
                    return finalizeTrace(
                        respondChat(normalizedRequest, startedAt, trace),
                        trace,
                        startedAt,
                    )
                com.jarvis.core.runtime.RouteDecision.CLARIFY ->
                    return finalizeTrace(
                        respondClarify(
                            normalizedRequest,
                            semantic.clarifyQuestion
                                ?: buildClarifyFromCandidates(semantic),
                            startedAt,
                            trace,
                        ),
                        trace,
                        startedAt,
                    )
                com.jarvis.core.runtime.RouteDecision.PLAN ->
                    return finalizeTrace(
                        processSemanticPlan(normalizedRequest, semantic, startedAt, trace),
                        trace,
                        startedAt,
                    )
                com.jarvis.core.runtime.RouteDecision.FAST_SKILL -> {
                    val rewritten = semantic.rewrittenCommand
                    // Переписывание берёт одну команду: цепочка ниже потеряла бы остальные шаги.
                    val isChain = CompoundCommandSplitter.isCompound(request.text.trim())
                    if (!isChain && !rewritten.isNullOrBlank() && rewritten != normalizedRequest.text) {
                        val rewrittenReq = normalizedRequest.copy(text = rewritten)
                        return finalizeTrace(
                            processSingle(
                                rewrittenReq,
                                startedAt,
                                preferUiType = false,
                                addUser = false,
                                allowReact = true,
                                trace = trace,
                            ),
                            trace,
                            startedAt,
                        )
                    }
                    // Fall through to BrainRouter with same text (skill path)
                }
                com.jarvis.core.runtime.RouteDecision.UNKNOWN -> Unit
            }
        }

        if (normalizedRequest.text.isNotBlank() &&
            AsrPhraseNormalizer.isIncompleteCommand(normalizedRequest.text)
        ) {
            return finalizeTrace(
                respondClarify(
                    normalizedRequest,
                    "Что именно сделать? Скажи полностью, например «включи свет».",
                    startedAt,
                    trace,
                ),
                trace,
                startedAt,
            )
        }

        // Compound: subordinate to semantic — if already PLAN handled above.
        // Remaining comma-splits still go through processCompound (compat) as PLAN.
        val rawSteps = CompoundCommandSplitter.split(request.text.trim())
        val steps = rawSteps
            .map { AsrPhraseNormalizer.normalize(it).trim() }
            .filter { it.isNotEmpty() }
        if (steps.size > 1) {
            return finalizeTrace(
                processCompound(normalizedRequest, steps, startedAt, trace),
                trace,
                startedAt,
            )
        }

        return finalizeTrace(
            processSingle(normalizedRequest, startedAt, preferUiType = false, trace = trace),
            trace,
            startedAt,
        )
    }

    private fun understandSemantic(
        raw: String,
        normalized: String,
        trace: com.jarvis.core.runtime.RequestTrace,
    ): com.jarvis.core.runtime.semantic.SemanticUnderstanding? {
        val lat = trace.interactionLatency
        val tSem0 = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        lat?.semanticStartAt = tSem0
        lat?.routeStartAt = tSem0
        val prevUser = context.messages
            .mapNotNull { line ->
                if (line.startsWith("user:", ignoreCase = true)) {
                    line.substringAfter(':').trim()
                } else {
                    null
                }
            }
            .dropLast(1)
            .lastOrNull()
        val prevAsst = context.messages.lastOrNull { it.startsWith("assistant:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val input = com.jarvis.core.runtime.semantic.SemanticInput(
            rawText = raw,
            normalizedText = normalized,
            previousUserText = prevUser,
            previousAssistantText = prevAsst,
            lastToolId = context.lastToolCall?.toolId,
            lastToolArgs = context.lastToolCall?.arguments
                ?.mapValues { it.value.toString() }
                .orEmpty(),
            hasPendingAction = context.pendingAction != null,
            pendingPhase = context.pendingAction?.phase?.name,
        )
        val fast = fastSemantic.interpret(input)
        val result = if (fast != null) {
            // Decisive local routes must not be overridden by LLM (esp. CLARIFY/TEMPLATE/PLAN/FAST).
            // LLM is fallback when local understanding is weak / unknown — not when we already
            // know we need a targeted clarification or an obvious action.
            if (!needsLlmSemanticFallback(fast)) {
                fast
            } else {
                trace.llmRequired = true
                interpretWithLlm(input) ?: fast
            }
        } else {
            // No fast hit → try LLM semantic once (optional); else null → legacy BrainRouter
            trace.llmRequired = true
            interpretWithLlm(input)
        }
        val tSem1 = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        lat?.semanticEndAt = tSem1
        lat?.routeEndAt = tSem1
        return result
    }

    /**
     * Семантика идёт на локальной модели (5+ с на устройстве). При доступном облаке она не
     * стоит этой задержки: legacy-маршрут разбирает запрос за миллисекунды, ответ даст облако.
     */
    private fun interpretWithLlm(
        input: com.jarvis.core.runtime.semantic.SemanticInput,
    ): com.jarvis.core.runtime.semantic.SemanticUnderstanding? {
        if (isOnlineForAi() && preferCloudForAi()) return null
        return llmSemantic?.interpret(input)
    }

    private fun needsLlmSemanticFallback(
        fast: com.jarvis.core.runtime.semantic.SemanticUnderstanding,
    ): Boolean {
        when (fast.suggestedRoute) {
            com.jarvis.core.runtime.RouteDecision.CLARIFY,
            com.jarvis.core.runtime.RouteDecision.TEMPLATE,
            com.jarvis.core.runtime.RouteDecision.FAST_SKILL,
            com.jarvis.core.runtime.RouteDecision.PLAN,
            -> return false
            com.jarvis.core.runtime.RouteDecision.CHAT -> {
                // Local conversational marker is enough; LLM chat runs later on CHAT path.
                return false
            }
            com.jarvis.core.runtime.RouteDecision.UNKNOWN -> Unit
        }
        return fast.clarity == com.jarvis.core.runtime.semantic.SemanticClarity.UNCLEAR ||
            fast.confidence < 0.45f
    }

    private fun applySemanticToTrace(
        trace: com.jarvis.core.runtime.RequestTrace,
        semantic: com.jarvis.core.runtime.semantic.SemanticUnderstanding,
        route: com.jarvis.core.runtime.RouteDecision,
    ) {
        trace.rawInput = semantic.rawInput.take(120)
        trace.normalizedInput = semantic.normalizedInput.take(120)
        trace.semanticGoal = semantic.goal
        trace.semanticIntent = semantic.intent
        trace.semanticConfidence = semantic.confidence
        trace.ambiguity = semantic.ambiguity.name
        trace.llmInterpretationUsed = semantic.llmInterpretationUsed
        trace.route = route
        trace.routeReason = semantic.routeReason
        (llmSemantic as? com.jarvis.brain.semantic.LlmStructuredSemanticInterpreter)
            ?.lastStructuredDiagnostics
            ?.let { diag ->
                trace.applyStructured(diag)
            }
            ?: run {
                // When notes indicate structured path without interpreter instance fields
                if (semantic.notes.any { it.startsWith("structured_type=") }) {
                    trace.structured = true
                    trace.structuredSchemaVersion = semantic.notes
                        .firstOrNull { it.startsWith("schema=") }
                        ?.substringAfter('=')
                    trace.structuredResponseType = semantic.notes
                        .firstOrNull { it.startsWith("structured_type=") }
                        ?.substringAfter('=')
                    trace.structuredParse = "SUCCESS"
                    trace.structuredValidation = "SUCCESS"
                }
            }
    }

    private fun buildClarifyFromCandidates(
        semantic: com.jarvis.core.runtime.semantic.SemanticUnderstanding,
    ): String {
        val c = semantic.candidateInterpretations
        return if (c.size >= 2) {
            "Ты имеешь в виду «${c[0]}» или «${c[1]}»?"
        } else {
            "Уточни, пожалуйста, что сделать."
        }
    }

    /**
     * Ответ модели оказался вызовом инструмента: выполняем обычным путём с риском и подтверждением.
     * Испорченный вызов нельзя озвучивать: пользователь услышал бы JSON.
     */
    private fun cloudToolResponse(
        request: UserRequest,
        reply: String,
        startedAt: Long,
        trace: com.jarvis.core.runtime.RequestTrace?,
        allowFollowUp: Boolean = true,
    ): AssistantResponse? {
        if (!com.jarvis.core.aioutput.CloudToolAction.isAction(reply)) return null
        val call = com.jarvis.core.aioutput.CloudToolAction.parse(reply, request.requestId)
            ?: return respond(
                request,
                "Не получилось разобрать команду. Скажи иначе.",
                AssistantResponseStatus.FAILED,
                startedAt,
                route = com.jarvis.core.runtime.RouteDecision.CHAT.name,
                routeReason = "cloud_tool_invalid",
            )
        val done = runTool(request, call, startedAt, trace = trace, routeReason = "cloud_tool")
        val data = done.toolResult?.takeIf {
            it.status == com.jarvis.core.common.model.ToolResultStatus.SUCCESS
        }?.message
        if (!allowFollowUp || call.toolId !in DATA_TOOLS || data.isNullOrBlank()) return done
        // Инструмент вернул данные, а не действие: отвечает модель, один шаг, без новых вызовов.
        val followUp = request.copy(
            text = "${request.text}\n\nДанные: $data\nОтветь сэру по этим данным коротко, без вызова инструментов.",
        )
        return respondChat(followUp, startedAt, trace, allowFollowUp = false).let { reply ->
            if (com.jarvis.core.aioutput.CloudToolAction.isAction(reply.replyText)) done else reply
        }
    }

    private fun processSemanticPlan(
        request: UserRequest,
        semantic: com.jarvis.core.runtime.semantic.SemanticUnderstanding,
        startedAt: Long,
        trace: com.jarvis.core.runtime.RequestTrace,
    ): AssistantResponse {
        trace.route = com.jarvis.core.runtime.RouteDecision.PLAN
        trace.routeReason = semantic.routeReason
        trace.planner = planPathCoordinator != null
        // Цепочка команд выполняется, а не пересказывается вслух.
        val commandSteps = CompoundCommandSplitter.split(request.text.trim())
            .map { AsrPhraseNormalizer.normalize(it).trim() }
            .filter { it.isNotEmpty() }
        if (commandSteps.size > 1) return processCompound(request, commandSteps, startedAt, trace)
        // Свободную цель разберёт облачная модель, выбрав инструмент.
        if (isOnlineForAi() && preferCloudForAi()) return respondChat(request, startedAt, trace)
        // Prefer PlanPathCoordinator for free-form goals
        if (planPathCoordinator != null && catalogToolDiscovery != null) {
            val discovery = catalogToolDiscovery!!.discover(request.text, limit = 8)
            val planRun = planPathCoordinator!!.planAndRun(
                goalText = request.text,
                discovery = discovery,
                requestId = request.requestId,
                live = false,
            )
            trace.agent = true
            android.util.Log.i(
                "Jarvis.PlanPath",
                "semantic PLAN goal=${SensitivityClassifier.mask(semantic.goal)} " +
                    "status=${planRun.plan.status} " +
                    "steps=${planRun.plan.steps.size} completed=${planRun.loopResult?.completed} " +
                    "dry=${planRun.dryRun}",
            )
            val summary = buildString {
                append("Понял цель: ${semantic.goal}. ")
                if (semantic.constraints.isNotEmpty()) {
                    append("Ограничения: ${semantic.constraints.joinToString()}. ")
                }
                append("План из ${planRun.plan.steps.size} шагов")
                if (planRun.plan.steps.isNotEmpty()) {
                    append(" (")
                    append(
                        planRun.plan.steps.take(4).joinToString { s ->
                            s.displaySummary.ifBlank { s.toolId ?: s.kind.name }
                        },
                    )
                    append(")")
                }
                append(". Пока в режиме планирования — скажи, с какого шага начать.")
            }
            return respond(
                request,
                summary,
                AssistantResponseStatus.PROCESSED,
                startedAt,
                route = com.jarvis.core.runtime.RouteDecision.PLAN.name,
                routeReason = semantic.routeReason,
            )
        }
        // No planner wired — clarify next step
        return respondClarify(
            request,
            "Это похоже на многошаговую задачу. С чего начать?",
            startedAt,
            trace,
        )
    }

    private fun finalizeTrace(
        response: AssistantResponse,
        trace: com.jarvis.core.runtime.RequestTrace,
        startedAt: Long,
    ): AssistantResponse {
        val now = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        val lat = trace.interactionLatency
        if (lat != null) {
            if (lat.firstUsefulAt == null) lat.firstUsefulAt = now
            lat.interactionCompleteAt = now
            lat.fallback = lat.fallback || response.fallbackUsed || trace.fallback
            if (trace.provider != null || response.provider != null) {
                // warm heuristic: local loaded → WARM/HOT; else UNKNOWN
                lat.warmState = when {
                    response.provider == "local.llm" || trace.provider == "local.llm" ->
                        com.jarvis.core.runtime.WarmState.WARM
                    else -> lat.warmState
                }
            }
            trace.applyLatency(lat)
        }
        trace.totalLatencyMs = (System.nanoTime() - startedAt) / 1_000_000
        if (trace.e2eMs == null) trace.e2eMs = trace.totalLatencyMs
        if (trace.firstUsefulMs == null) trace.firstUsefulMs = trace.totalLatencyMs
        if (trace.result.isNullOrBlank()) {
            trace.result = response.status.name
        }
        if (trace.route == com.jarvis.core.runtime.RouteDecision.UNKNOWN && !response.route.isNullOrBlank()) {
            runCatching {
                trace.route = com.jarvis.core.runtime.RouteDecision.valueOf(response.route!!)
            }
        }
        if (trace.routeReason.isBlank()) {
            trace.routeReason = response.routeReason.orEmpty()
        }
        com.jarvis.core.runtime.RequestTraceLog.emit(trace)
        return if (response.route == null) {
            response.copy(
                route = trace.route.name,
                routeReason = trace.routeReason,
                provider = trace.provider,
                model = trace.model,
                fallbackUsed = trace.fallback,
                verification = trace.verification,
                durationMs = trace.totalLatencyMs,
            )
        } else {
            response.copy(durationMs = trace.totalLatencyMs)
        }
    }

    private fun processCompound(
        request: UserRequest,
        steps: List<String>,
        startedAt: Long,
        trace: com.jarvis.core.runtime.RequestTrace,
    ): AssistantResponse {
        trace.route = com.jarvis.core.runtime.RouteDecision.PLAN
        trace.routeReason = "compound_steps=${steps.size}"
        trace.planner = planPathCoordinator != null
        // V2-01: optional PlanPathCoordinator dry-run for telemetry / future live wiring.
        if (planPathCoordinator != null && catalogToolDiscovery != null) {
            runCatching {
                val discovery = catalogToolDiscovery!!.discover(request.text, limit = 8)
                val planRun = planPathCoordinator!!.planAndRun(
                    goalText = request.text,
                    discovery = discovery,
                    requestId = request.requestId,
                    live = false,
                )
                trace.agent = true
                android.util.Log.i(
                    "Jarvis.PlanPath",
                    "compound plan status=${planRun.plan.status} steps=${planRun.plan.steps.size} " +
                        "loopCompleted=${planRun.loopResult?.completed} " +
                        "reason=${planRun.loopResult?.stoppedReason} dry=${planRun.dryRun}",
                )
            }
        }
        val replies = mutableListOf<String>()
        var lastStatus = AssistantResponseStatus.PROCESSED
        var lastIntent: com.jarvis.core.common.model.Intent? = null
        var preferUiType = false
        var lastTool: String? = null

        for ((index, step) in steps.withIndex()) {
            val stepReq = request.copy(text = step, requestId = request.requestId)
            trace.childStepId(index + 1)
            // Не дублируем user в контекст для каждого шага — уже добавили полный текст.
            // ReAct внутри шага не нужен: шаги уже заданы сплиттером.
            val response = processSingle(
                stepReq,
                startedAt,
                preferUiType = preferUiType,
                addUser = false,
                allowReact = false,
                trace = trace,
            )
            replies += response.replyText
            lastStatus = response.status
            lastIntent = response.intent ?: lastIntent
            lastTool = response.toolResult?.toolId ?: lastTool

            if (response.status == AssistantResponseStatus.NEEDS_CONFIRMATION ||
                response.status == AssistantResponseStatus.NEEDS_CLARIFICATION ||
                response.status == AssistantResponseStatus.DENIED
            ) {
                break
            }
            // ui.click miss перед type: часто уже открыт нужный чат — всё равно пробуем ввод
            if (response.status == AssistantResponseStatus.FAILED) {
                val nextStep = steps.getOrNull(index + 1)?.lowercase().orEmpty()
                val softContinue = lastTool == "ui.click" &&
                    (nextStep.startsWith("напиши") || nextStep.startsWith("введи") ||
                        nextStep.startsWith("напечатай"))
                if (!softContinue) break
            }

            val t = response.toolResult?.toolId.orEmpty()
            if (t == "launcher" || t.startsWith("ui.")) preferUiType = true

            if (index < steps.lastIndex) {
                val delay = when (lastTool) {
                    "launcher" -> 2_400L
                    "ui.click", "ui.wait" -> 1_000L
                    "system.setting" -> 700L
                    else -> 500L
                }
                Thread.sleep(delay)
            }
        }

        val combined = replies.joinToString(" ")
        // processSingle уже добавлял assistant-реплики; финальный ответ один.
        return AssistantResponse(
            requestId = request.requestId,
            replyText = combined,
            status = lastStatus,
            intent = lastIntent,
            durationMs = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    private fun processSingle(
        normalizedRequest: UserRequest,
        startedAt: Long,
        preferUiType: Boolean,
        addUser: Boolean = true,
        allowReact: Boolean = true,
        trace: com.jarvis.core.runtime.RequestTrace,
    ): AssistantResponse {
        if (addUser && context.messages.lastOrNull() != "user: ${normalizedRequest.text}") {
            // already added in process() for first call
        }

        // Phase 2: повтор/патч последнего SkillCall («ещё раз», «а послезавтра?»).
        val followUp = FollowUpResolver.resolve(normalizedRequest.text, context.lastToolCall)
        if (followUp != null) {
            return runTool(
                normalizedRequest,
                followUp,
                startedAt,
                trace = trace,
                routeHint = com.jarvis.core.runtime.RouteDecision.FAST_SKILL,
                routeReason = "follow_up",
            )
        }

        val history = buildList {
            if (memoryEnabled) {
                val mem = runCatching { memoryProvider(normalizedRequest.text) }
                    .getOrDefault("")
                    .take(180)
                if (mem.isNotBlank()) add("memory: $mem")
            }
            addAll(context.messages.takeLast(4))
        }
        val routeText = if (preferUiType) {
            // Подсказка правилам: «напиши …» после UI-шага — ввод на экране, не SMS.
            normalizedRequest.text
        } else {
            normalizedRequest.text
        }
        val routeRequest = if (preferUiType && looksLikeUiTypeOverride(routeText)) {
            val body = routeText.removePrefixVerb()
                .removePrefix("на экране ").removePrefix("в поле ").trim()
            normalizedRequest.copy(text = "введи $body")
        } else {
            normalizedRequest
        }

        val topSkills = skillRetriever.retrieve(normalizedRequest.text).map {
            BrainRouter.SkillInfo(
                id = it.id,
                name = it.name,
                description = it.description,
                examples = it.examples,
                parameters = it.parameters.map { p ->
                    BrainRouter.SkillParam(
                        name = p.name,
                        description = p.description,
                        type = p.type.name,
                        required = p.required,
                        allowedValues = p.allowedValues
                    )
                }
            )
        }

        // Capability discovery: релевантный subset (не весь каталог) — для LLM / логов.
        val topCaps = capabilityDiscovery?.discover(normalizedRequest.text, limit = 5).orEmpty()
        if (topCaps.isNotEmpty()) {
            runCatching {
                android.util.Log.d(
                    "Jarvis.Capability",
                    "discover q='${SensitivityClassifier.mask(normalizedRequest.text).take(60)}' → " +
                        topCaps.joinToString { it.id },
                )
            }
        }

        val result = brainRouter.route(routeRequest, history, topSkills)

        return when (result) {
            is BrainResult.SkillCall -> {
                if (result.confidence >= executeThreshold) {
                    if (allowReact && shouldReact(normalizedRequest, result)) {
                        executeWithReact(normalizedRequest, result, topSkills, history, startedAt, trace)
                    } else {
                        executeSkillCall(normalizedRequest, result, startedAt, trace)
                    }
                } else if (result.confidence >= clarifyThreshold) {
                    respondClarify(normalizedRequest, "Уточни, пожалуйста, что сделать.", startedAt, trace)
                } else {
                    respondChat(normalizedRequest, startedAt, trace)
                }
            }
            is BrainResult.Clarify -> respondClarify(normalizedRequest, result.question, startedAt, trace)
            is BrainResult.Chat -> respondChat(normalizedRequest, startedAt, trace)
        }
    }

    /**
     * ReAct только если в фразе есть продолжение после launch/UI
     * (иначе LLM после «открой настройки» выдумывает лишние back).
     */
    private fun shouldReact(request: UserRequest, first: BrainResult.SkillCall): Boolean {
        // Multi-step уже режет CompoundCommandSplitter — ReAct только для launch-хвостов.
        if (CompoundCommandSplitter.isCompound(request.text)) return false
        val type = first.intent.type
        if (type != com.jarvis.core.common.model.IntentType.LAUNCH_APP &&
            type != com.jarvis.core.common.model.IntentType.SYSTEM_SETTING
        ) {
            return false
        }
        val t = request.text.lowercase()
        val followVerbs = listOf(
            "нажми", "тапни", "найди", "напиши", "введи", "включи", "выключи",
            "прочитай", "опиши", "подожди", "что на экране",
        )
        return followVerbs.any { t.contains(it) }
    }

    /** SkillCall + до 2 дополнительных UI/launcher шагов (ReAct). */
    private fun executeWithReact(
        request: UserRequest,
        first: BrainResult.SkillCall,
        topSkills: List<BrainRouter.SkillInfo>,
        history: List<String>,
        startedAt: Long,
        trace: com.jarvis.core.runtime.RequestTrace,
    ): AssistantResponse {
        var response = executeSkillCall(request, first, startedAt, trace)
        var steps = 1
        var lastTool = response.toolResult?.toolId.orEmpty()
        var lastMsg = response.toolResult?.message.orEmpty().ifBlank { response.replyText }

        while (
            steps < REACT_MAX_STEPS &&
            response.status == AssistantResponseStatus.PROCESSED &&
            lastTool.isNotBlank()
        ) {
            val next = brainRouter.continueAfterTool(
                originalRequest = request,
                history = history,
                topSkills = topSkills,
                lastToolId = lastTool,
                lastToolMessage = lastMsg,
            ) ?: break
            if (next !is BrainResult.SkillCall || next.confidence < executeThreshold) break

            val delay = when (lastTool) {
                "launcher" -> 400L // остальное ждёт waitForPackage внутри open
                "ui.wait" -> 0L
                "system.setting" -> 800L
                else -> 450L
            }
            if (delay > 0) Thread.sleep(delay)

            val stepResp = executeSkillCall(request, next, startedAt, trace)
            response = AssistantResponse(
                requestId = request.requestId,
                replyText = listOf(response.replyText, stepResp.replyText)
                    .filter { it.isNotBlank() }
                    .joinToString(" "),
                status = stepResp.status,
                intent = stepResp.intent ?: response.intent,
                toolResult = stepResp.toolResult ?: response.toolResult,
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                route = com.jarvis.core.runtime.RouteDecision.FAST_SKILL.name,
                routeReason = "react",
                verification = stepResp.verification,
            )
            lastTool = stepResp.toolResult?.toolId.orEmpty()
            lastMsg = stepResp.toolResult?.message.orEmpty().ifBlank { stepResp.replyText }
            steps++
            if (stepResp.status != AssistantResponseStatus.PROCESSED) break
        }
        return response
    }

    private fun looksLikeUiTypeOverride(text: String): Boolean {
        val t = text.lowercase().trim()
        return t.startsWith("напиши ") || t.startsWith("напечатай ") || t.startsWith("введи ")
    }

    private fun String.removePrefixVerb(): String {
        val t = trim()
        val verbs = listOf("напиши ", "напечатай ", "введи ")
        for (v in verbs) {
            if (t.lowercase().startsWith(v)) return t.drop(v.length).trim()
        }
        return t
    }

    private fun handlePending(
        request: UserRequest,
        pending: PendingAction,
        startedAt: Long,
        trace: com.jarvis.core.runtime.RequestTrace,
    ): AssistantResponse {
        val text = request.text.lowercase().trim()

        when (pending.phase) {
            PendingAction.Phase.COLLECT_CHAT -> {
                if (isNo(text)) {
                    context.clearPending()
                    return respond(request, "Отменил.", AssistantResponseStatus.PROCESSED, startedAt)
                }
                val chat = stripTelegramWriteVerb(request.text).trim()
                if (chat.isBlank()) {
                    return respondPending(request, "Кому написать?", startedAt, pending)
                }
                val withChat = pending.toolCall.copy(
                    arguments = pending.toolCall.arguments + mapOf("chat" to chat),
                )
                val msg = withChat.arguments["text"]?.toString().orEmpty().trim()
                if (msg.isBlank()) {
                    val ask = PendingAction(
                        toolCall = withChat,
                        prompt = "Что написать?",
                        phase = PendingAction.Phase.COLLECT_TEXT,
                    )
                    context.pendingAction = ask
                    return respond(
                        request,
                        ask.prompt,
                        AssistantResponseStatus.NEEDS_CLARIFICATION,
                        startedAt,
                    )
                }
                context.clearPending()
                return runTool(request, withChat, startedAt, trace = trace, userConfirmed = true)
            }

            PendingAction.Phase.COLLECT_TEXT -> {
                if (isNo(text)) {
                    context.clearPending()
                    return respond(request, "Отменил.", AssistantResponseStatus.PROCESSED, startedAt)
                }
                val message = stripTelegramWriteVerb(request.text).trim()
                if (message.isBlank()) {
                    return respondPending(
                        request,
                        "Что написать?",
                        startedAt,
                        pending,
                    )
                }
                val updatedCall = pending.toolCall.copy(
                    arguments = pending.toolCall.arguments + mapOf("text" to message),
                )
                context.clearPending()
                return runTool(request, updatedCall, startedAt, trace = trace, userConfirmed = true)
            }

            PendingAction.Phase.DISAMBIGUATE -> {
                if (isNo(text)) {
                    context.clearPending()
                    return respond(request, "Отменил.", AssistantResponseStatus.PROCESSED, startedAt)
                }
                val choice = matchChoice(text, pending.choices)
                if (choice == null) {
                    val names = pending.choices.joinToString(", ") { it.label }
                    return respondPending(
                        request,
                        "Не расслышал. Кому именно: $names?",
                        startedAt,
                        pending,
                    )
                }
                val updatedCall = pending.toolCall.copy(
                    arguments = pending.toolCall.arguments + mapOf(
                        "contact" to choice.label,
                        "number" to choice.value,
                    ),
                )
                context.clearPending()
                return runTool(request, updatedCall, startedAt, trace = trace, userConfirmed = true)
            }

            PendingAction.Phase.CONFIRM -> {
                when {
                    isYes(text) -> {
                        // После «да» при нескольких контактах — уточняем кому.
                        if (pending.choices.size > 1) {
                            val names = pending.choices.joinToString(", ") { it.label }
                            val ask = PendingAction(
                                toolCall = pending.toolCall,
                                prompt = "Нашёл несколько: $names. Кому звонить?",
                                phase = PendingAction.Phase.DISAMBIGUATE,
                                choices = pending.choices,
                                integrityFingerprint = pending.integrityFingerprint,
                            )
                            context.pendingAction = ask
                            return respond(
                                request,
                                ask.prompt,
                                AssistantResponseStatus.NEEDS_CLARIFICATION,
                                startedAt,
                            )
                        }
                        context.clearPending()
                        return runTool(
                            request,
                            pending.toolCall,
                            startedAt,
                            trace = trace,
                            userConfirmed = true,
                            priorFingerprint = pending.integrityFingerprint,
                        )
                    }
                    isNo(text) -> {
                        context.clearPending()
                        return respond(
                            request,
                            "Отменил.",
                            AssistantResponseStatus.PROCESSED,
                            startedAt,
                        )
                    }
                    else -> {
                        return respondPending(
                            request,
                            "Скажи «да» или «нет». ${pending.prompt}",
                            startedAt,
                            pending,
                        )
                    }
                }
            }
        }
    }

    /** Похоже на новую команду, а не на ответ «да/нет/имя контакта». */
    private fun looksLikeFreshCommand(text: String, pending: PendingAction): Boolean {
        if (pending.phase == PendingAction.Phase.COLLECT_TEXT ||
            pending.phase == PendingAction.Phase.COLLECT_CHAT
        ) {
            // Ответ на уточнение — почти любой текст; сбрасываем только явные новые команды.
            val cancel = listOf(
                "открой", "включи", "выключи", "позвони", "запусти", "поставь",
                "найди", "нажми", "закрой", "сверни",
            )
            return cancel.any { text.startsWith(it) }
        }
        val verbs = listOf(
            "позвони", "набери", "открой", "включи", "выключи", "напиши", "отправь",
            "сделай", "поставь", "найди", "запусти", "нажми", "покажи",
        )
        if (verbs.any { text.startsWith(it) }) return true
        if (pending.phase == PendingAction.Phase.DISAMBIGUATE && text.split(" ").size <= 4) {
            return false
        }
        return false
    }

    /** «напиши я скоро буду» → «я скоро буду». */
    private fun stripTelegramWriteVerb(raw: String): String {
        var t = raw.trim()
        val verbs = listOf("напиши ", "напечатай ", "отправь ", "передай ", "напиши", "напечатай")
        val lower = t.lowercase()
        for (v in verbs) {
            if (lower.startsWith(v)) {
                t = t.drop(v.length).trim()
                break
            }
        }
        return t
    }

    private fun executeSkillCall(
        request: UserRequest,
        call: BrainResult.SkillCall,
        startedAt: Long,
        trace: com.jarvis.core.runtime.RequestTrace,
    ): AssistantResponse {
        val intent = call.intent
        JarvisEvents.publish(AssistantEvent.IntentResolved(intent))
        trace.route = com.jarvis.core.runtime.RouteDecision.FAST_SKILL
        if (trace.routeReason.isBlank()) {
            trace.routeReason = "skill_call"
        }
        trace.skill = intent.type.name

        var toolCall = ToolCallFactory.build(intent, request.requestId)
        if (toolCall == null) {
            return respond(
                request,
                "Понял, но не хватает деталей. Уточни, пожалуйста.",
                AssistantResponseStatus.NEEDS_CLARIFICATION,
                startedAt,
                intent,
            )
        }

        // Telegram: нет чата / нет текста → уточнения, затем автоотправка
        if (toolCall.toolId == "telegram.send") {
            val chat = toolCall.arguments["chat"]?.toString().orEmpty().trim()
            val msg = toolCall.arguments["text"]?.toString().orEmpty().trim()
            if (chat.isBlank()) {
                val prompt = "Кому написать?"
                context.pendingAction = PendingAction(
                    toolCall = toolCall,
                    prompt = prompt,
                    phase = PendingAction.Phase.COLLECT_CHAT,
                )
                return respond(
                    request,
                    prompt,
                    AssistantResponseStatus.NEEDS_CLARIFICATION,
                    startedAt,
                    intent,
                )
            }
            if (msg.isBlank()) {
                val prompt = "Что написать?"
                context.pendingAction = PendingAction(
                    toolCall = toolCall.copy(
                        arguments = toolCall.arguments + mapOf("chat" to chat),
                    ),
                    prompt = prompt,
                    phase = PendingAction.Phase.COLLECT_TEXT,
                )
                return respond(
                    request,
                    prompt,
                    AssistantResponseStatus.NEEDS_CLARIFICATION,
                    startedAt,
                    intent,
                )
            }
        }

        val tool = toolRegistry.find(toolCall.toolId)
        if (tool == null) {
            return respond(
                request,
                "Инструмент «${toolCall.toolId}» пока недоступен.",
                AssistantResponseStatus.FAILED,
                startedAt,
                intent,
            )
        }

        // Звонок: резолвим контакт заранее; при нескольких — спросим после «да».
        if (toolCall.toolId == "phone.call") {
            val raw = toolCall.arguments["contact"]?.toString().orEmpty()
            val choices = resolveContacts(raw)
            if (choices.size == 1) {
                toolCall = toolCall.copy(
                    arguments = toolCall.arguments + mapOf(
                        "contact" to choices[0].label,
                        "number" to choices[0].value,
                    ),
                )
            }
        }

        // force_stop — деструктивно: всегда CONFIRM, даже если launcher SAFE
        val forceStop = toolCall.toolId == "launcher" &&
            toolCall.arguments["action"]?.toString()?.lowercase() in
            setOf("force_stop", "forcestop", "kill")
        if (forceStop) {
            val app = toolCall.arguments["app"]?.toString().orEmpty().ifBlank { "приложение" }
            val prompt = "Принудительно остановить «$app»? Скажи «да» или «нет»."
            return respondPending(
                request,
                prompt,
                startedAt,
                PendingAction(toolCall = toolCall, prompt = prompt),
            )
        }

        return runTool(
            request,
            toolCall,
            startedAt,
            intent,
            trace = trace,
            routeHint = com.jarvis.core.runtime.RouteDecision.FAST_SKILL,
            routeReason = "skill:${toolCall.toolId}",
        )
    }

    private fun runTool(
        request: UserRequest,
        toolCall: ToolCall,
        startedAt: Long,
        intent: com.jarvis.core.common.model.Intent? = null,
        trace: com.jarvis.core.runtime.RequestTrace? = null,
        routeHint: com.jarvis.core.runtime.RouteDecision = com.jarvis.core.runtime.RouteDecision.FAST_SKILL,
        routeReason: String = "tool",
        userConfirmed: Boolean = false,
        priorFingerprint: String? = null,
    ): AssistantResponse {
        state(AssistantState.EXECUTING)
        trace?.route = routeHint
        if (trace != null && trace.routeReason.isBlank()) {
            trace.routeReason = routeReason
        }
        trace?.tool = toolCall.toolId

        val tool = toolRegistry.find(toolCall.toolId)
        if (tool == null) {
            return respond(
                request,
                "Инструмент «${toolCall.toolId}» пока недоступен.",
                AssistantResponseStatus.FAILED,
                startedAt,
                intent,
                route = routeHint.name,
                routeReason = routeReason,
            )
        }

        val outcome = execution.executeAction(
            toolCall = toolCall,
            definition = tool.definition,
            requestId = request.requestId,
            userConfirmed = userConfirmed,
            priorFingerprint = priorFingerprint,
        )

        when (outcome) {
            is com.jarvis.core.runtime.ExecutionOrchestrator.Outcome.NeedsConfirmation -> {
                if (toolCall.toolId == "phone.call" &&
                    toolCall.arguments["action"]?.toString() == "history"
                ) {
                    return runTool(
                        request,
                        toolCall,
                        startedAt,
                        intent,
                        trace = trace,
                        userConfirmed = true,
                    )
                }
                val prompt = confirmationPrompt(toolCall)
                val choices = if (toolCall.toolId == "phone.call") {
                    val raw = toolCall.arguments["contact"]?.toString().orEmpty()
                    val resolved = resolveContacts(raw)
                    if (resolved.size > 1) resolved else emptyList()
                } else {
                    emptyList()
                }
                context.pendingAction = PendingAction(
                    toolCall = toolCall,
                    prompt = prompt,
                    phase = PendingAction.Phase.CONFIRM,
                    choices = choices,
                    integrityFingerprint = outcome.integrityFingerprint,
                )
                return respond(
                    request,
                    prompt,
                    AssistantResponseStatus.NEEDS_CONFIRMATION,
                    startedAt,
                    intent,
                    route = routeHint.name,
                    routeReason = "needs_confirmation",
                )
            }
            is com.jarvis.core.runtime.ExecutionOrchestrator.Outcome.Denied -> {
                return respond(
                    request,
                    outcome.reason,
                    AssistantResponseStatus.DENIED,
                    startedAt,
                    intent,
                    route = routeHint.name,
                    routeReason = "denied",
                )
            }
            is com.jarvis.core.runtime.ExecutionOrchestrator.Outcome.Failed -> {
                return respond(
                    request,
                    outcome.reason,
                    AssistantResponseStatus.FAILED,
                    startedAt,
                    intent,
                    route = routeHint.name,
                    routeReason = "exec_failed",
                )
            }
            is com.jarvis.core.runtime.ExecutionOrchestrator.Outcome.Executed -> {
                val result = outcome.toolResult
                val verifyLabel = outcome.verification.traceLabel()
                trace?.verification = verifyLabel
                JarvisEvents.publish(AssistantEvent.ToolExecuted(result))

                if (toolCall.toolId == "knowledge" &&
                    (result.status == com.jarvis.core.common.model.ToolResultStatus.OFFLINE ||
                        result.status == com.jarvis.core.common.model.ToolResultStatus.FAILED)
                ) {
                    android.util.Log.i(
                        "Jarvis.Orchestrator",
                        "knowledge ${result.status} → chat LLM fallback " +
                            "msg='${SensitivityClassifier.mask(result.message.orEmpty()).take(80)}'",
                    )
                    context.lastToolCall = toolCall
                    val q = toolCall.arguments["query"]?.toString()?.trim().orEmpty()
                    val chatReq = if (q.isNotBlank()) {
                        request.copy(text = "Расскажи кратко про $q")
                    } else {
                        request
                    }
                    trace?.fallback = true
                    return respondChat(chatReq, startedAt, trace)
                }

                if (result.status == com.jarvis.core.common.model.ToolResultStatus.SUCCESS) {
                    val enriched = when (toolCall.toolId) {
                        "weather" -> {
                            val city = result.data["city"]?.toString()?.trim().orEmpty()
                            if (city.isNotBlank()) {
                                toolCall.copy(arguments = toolCall.arguments + mapOf("city" to city))
                            } else toolCall
                        }
                        "knowledge" -> {
                            val q = toolCall.arguments["query"]?.toString()?.trim().orEmpty()
                            val topic = toolCall.arguments["topic"]?.toString()?.trim().orEmpty()
                                .ifBlank {
                                    q.lowercase()
                                        .removePrefix("кто такие ").removePrefix("кто такой ")
                                        .removePrefix("кто такая ").removePrefix("что такое ")
                                        .removePrefix("расскажи про ").removePrefix("расскажи о ")
                                        .trim().take(40)
                                }
                            val answeredTitle = result.data["title"]?.toString()?.trim().orEmpty()
                            if (q.isNotBlank() || topic.isNotBlank()) {
                                toolCall.copy(
                                    arguments = buildMap {
                                        if (q.isNotBlank()) put("query", q)
                                        if (topic.isNotBlank()) put("topic", topic)
                                        if (answeredTitle.isNotBlank()) {
                                            put("exclude_title", answeredTitle)
                                            put("title", answeredTitle)
                                        }
                                    },
                                )
                            } else toolCall
                        }
                        "launcher" -> {
                            val app = result.data["app"]?.toString()?.trim().orEmpty()
                                .ifBlank { toolCall.arguments["app"]?.toString()?.trim().orEmpty() }
                            if (app.isNotBlank()) {
                                toolCall.copy(
                                    arguments = toolCall.arguments + mapOf(
                                        "app" to app,
                                        "action" to (toolCall.arguments["action"] ?: "open"),
                                    ),
                                )
                            } else toolCall
                        }
                        else -> toolCall
                    }
                    context.lastToolCall = enriched
                }
                val reply = ResponsePlanner.plan(result)
                val factRefused = rememberIfFact(request)
                val finalReply = if (factRefused) FactExtractor.REFUSAL else reply
                rememberReply(finalReply)
                state(AssistantState.READY)
                return AssistantResponse(
                    requestId = request.requestId,
                    replyText = finalReply,
                    status = ResponsePlanner.assistantStatusOf(result),
                    intent = intent,
                    toolResult = result,
                    durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                    route = routeHint.name,
                    routeReason = routeReason,
                    verification = verifyLabel,
                )
            }
        }
    }

    private fun confirmationPrompt(toolCall: ToolCall): String =
        when (toolCall.toolId) {
            "phone.call" -> {
                val name = toolCall.arguments["contact"]?.toString()
                    ?: toolCall.arguments["number"]?.toString()
                    ?: "контакт"
                "Позвонить «$name»? Скажи «да» или «нет»."
            }
            "launcher" -> {
                val app = toolCall.arguments["app"]?.toString().orEmpty().ifBlank { "приложение" }
                "Принудительно остановить «$app»? Скажи «да» или «нет»."
            }
            else -> "Выполнить «${toolCall.toolId}»? Скажи «да» или «нет»."
        }

    private fun matchChoice(text: String, choices: List<PendingAction.Choice>): PendingAction.Choice? {
        if (choices.isEmpty()) return null
        val t = text.lowercase().trim()
        // «первому» / «второй»
        val ordinal = when {
            t.contains("перв") || t == "1" || t.contains("один") -> 0
            t.contains("втор") || t == "2" -> 1
            t.contains("трет") || t == "3" -> 2
            else -> -1
        }
        if (ordinal in choices.indices) return choices[ordinal]

        val scored = choices.map { c ->
            val label = c.label.lowercase()
            val score = when {
                label == t -> 100
                label.contains(t) || t.contains(label) -> 80
                Transliterator.compareKey(label).contains(Transliterator.compareKey(t)) -> 70
                else -> 0
            }
            c to score
        }.filter { it.second > 0 }.maxByOrNull { it.second }
        return scored?.first
    }

    private fun isYes(text: String): Boolean {
        val t = text.lowercase().trim()
        if (isNo(t)) return false
        return t == "да" || t == "ага" || t == "угу" || t == "yes" || t == "ок" || t == "окей" ||
            t.startsWith("да ") || t == "подтверждаю" || t == "конечно" ||
            t == "звони" || t == "звонй" || t == "call"
    }

    private fun isNo(text: String): Boolean {
        val t = text.lowercase().trim()
        return t == "нет" || t == "не" || t == "no" || t.startsWith("не ") ||
            t.contains("отмен") || t.contains("не надо") || t.contains("не звон")
    }

    private fun respondClarify(
        request: UserRequest,
        question: String,
        startedAt: Long,
        trace: com.jarvis.core.runtime.RequestTrace? = null,
    ): AssistantResponse {
        trace?.route = com.jarvis.core.runtime.RouteDecision.CLARIFY
        if (trace?.routeReason.isNullOrBlank()) {
            trace?.routeReason = "clarify"
        }
        return respond(
            request,
            question,
            AssistantResponseStatus.NEEDS_CLARIFICATION,
            startedAt,
            route = com.jarvis.core.runtime.RouteDecision.CLARIFY.name,
            routeReason = trace?.routeReason ?: "clarify",
        )
    }

    private fun respondPending(
        request: UserRequest,
        question: String,
        startedAt: Long,
        pending: PendingAction,
    ): AssistantResponse {
        context.pendingAction = pending
        return respond(
            request,
            question,
            AssistantResponseStatus.NEEDS_CONFIRMATION,
            startedAt,
            route = com.jarvis.core.runtime.RouteDecision.FAST_SKILL.name,
            routeReason = "pending_confirm",
        )
    }

    private fun respondChat(
        request: UserRequest,
        startedAt: Long,
        trace: com.jarvis.core.runtime.RequestTrace? = null,
        allowFollowUp: Boolean = true,
    ): AssistantResponse {
        val lat = trace?.interactionLatency
        val tCtx0 = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        lat?.contextStartAt = tCtx0
        val assembled = if (memoryEnabled && llmContextAssembly != null) {
            val input = llmContextAssembly.gatherInput(
                userText = request.text,
                conversationLines = context.messages,
                persona = chatPersona(),
                cloudMemoryAllowed = cloudMemoryAllowed(),
                chatProfile = chatProfile(),
                pendingContext = context,
            )
            unifiedContextBuilder.build(input)
        } else {
            null
        }
        lat?.contextEndAt = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        assembled?.diagnostics?.buildMs?.let { ms ->
            // Prefer measured builder time when available
            if (lat != null && lat.contextStartAt != null) {
                lat.contextEndAt = lat.contextStartAt!! + ms
            }
        }
        val memories = assembled?.legacyMemoriesSnippet?.takeIf { it.isNotBlank() }
            ?: if (memoryEnabled) memoryProvider(request.text) else ""
        val tDlg = System.nanoTime()
        assembled?.diagnostics?.let { d ->
            runCatching {
                android.util.Log.i("Jarvis.LlmContext", d.logLine())
            }
        }
        lat?.llmRequestStartAt = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        val detailed = dialogue.respondDetailed(
            request.text,
            context.messages,
            memories,
            assembled,
        )
        lat?.llmCompleteAt = com.jarvis.core.runtime.LatencyClock.elapsedMs()
        val dialogueMs = (System.nanoTime() - tDlg) / 1_000_000
        val route = when (detailed.kind) {
            DialogueResult.Kind.TEMPLATE -> com.jarvis.core.runtime.RouteDecision.TEMPLATE
            DialogueResult.Kind.FALLBACK_TEMPLATE -> com.jarvis.core.runtime.RouteDecision.TEMPLATE
            DialogueResult.Kind.LLM -> com.jarvis.core.runtime.RouteDecision.CHAT
        }
        trace?.route = route
        trace?.routeReason = detailed.reason.ifBlank { detailed.kind.name }
        if (detailed.kind == DialogueResult.Kind.LLM) {
            trace?.provider = detailed.providerHint ?: "chat_llm"
            trace?.model = detailed.modelHint
            trace?.fallback = detailed.fallbackUsed
            if (!detailed.selectionReason.isNullOrBlank()) {
                // Keep semantic routeReason; attach AI selection in provider/model fields
                trace?.aiSelectionReason = detailed.selectionReason
            }
            detailed.latencyMs?.let {
                trace?.aiLatencyMs = it
                trace?.aiCompletionMs = it
                lat?.let { l ->
                    // Backfill completion from provider clock if wall marks missing
                    if (l.llmRequestStartAt != null && l.llmCompleteAt == null) {
                        l.llmCompleteAt = l.llmRequestStartAt!! + it
                    }
                }
            }
            val kind = detailed.ttftKind ?: com.jarvis.core.runtime.TtftKind.NOT_AVAILABLE
            lat?.ttftKind = kind
            trace?.aiTtftKind = kind.name
            if (kind == com.jarvis.core.runtime.TtftKind.REAL) {
                detailed.ttftMs?.let { trace?.aiTtftMs = it }
            } else {
                // Never publish non-streaming completion as TTFT
                trace?.aiTtftMs = null
            }
            detailed.contextDiagnostics?.let { d ->
                trace?.contextSections = d.contextSections.joinToString(",")
                trace?.contextChars = d.contextChars
                trace?.memoryCount = d.memoryCount
                trace?.historyTurns = d.historyTurns
                trace?.systemPromptVersion = d.systemPromptVersion
                trace?.contextMs = d.buildMs
            }
        }
        runCatching {
            android.util.Log.i(
                "Jarvis.Orchestrator",
                "dialogue ${dialogueMs}ms kind=${detailed.kind} reason=${detailed.reason} " +
                    "replyLen=${detailed.text.length} user='${SensitivityClassifier.mask(request.text).take(80)}'",
            )
        }
        cloudToolResponse(request, detailed.text, startedAt, trace, allowFollowUp)?.let { return it }
        // В уточнении текст дополнен данными инструмента: это не слова пользователя.
        val factRefused = allowFollowUp && rememberIfFact(request)
        val finalText = if (factRefused) FactExtractor.REFUSAL else detailed.text
        rememberReply(finalText)
        state(AssistantState.READY)
        return AssistantResponse(
            requestId = request.requestId,
            replyText = finalText,
            status = AssistantResponseStatus.PROCESSED,
            intent = null,
            durationMs = (System.nanoTime() - startedAt) / 1_000_000,
            route = route.name,
            routeReason = detailed.reason,
            provider = trace?.provider,
        )
    }

    /**
     * Пишем ответ в историю диалога только после mask(): одноразовые коды и номера
     * карт не должны оседать в контексте LLM. Пользователь при этом получает полный
     * текст — маскируется лишь сохраняемая копия ([AssistantResponse.replyText] не
     * меняется).
     */
    private fun rememberReply(text: String) {
        val masked = SensitivityClassifier.mask(text)
        context.addAssistant(masked)
        ConversationJournal.appendAssistant(masked)
    }

    /**
     * Реплика пользователя: в память контекста — как есть (дедуп и follow-up
     * сравнивают с исходным текстом), на диск — только masked-версия
     * (это делает [ConversationJournal]).
     */
    private fun rememberUser(text: String) {
        context.addUser(text)
        ConversationJournal.appendUser(text)
    }

    /**
     * @return true, если фраза похожа на факт, но содержала секрет: сохранять
     *         нельзя, а пользователю вместо «запомнил» уходит [FactExtractor.REFUSAL].
     */
    private fun rememberIfFact(request: UserRequest): Boolean {
        if (!memoryEnabled) return false
        return when (val decision = FactExtractor.decide(request.text)) {
            is FactExtractor.Decision.Save -> {
                runCatching { longTermMemory.add(decision.text, category = "preference") }
                false
            }
            FactExtractor.Decision.Refused -> true
            null -> false
        }
    }

    private fun respond(
        request: UserRequest,
        text: String,
        status: AssistantResponseStatus,
        startedAt: Long,
        intent: com.jarvis.core.common.model.Intent? = null,
        route: String? = null,
        routeReason: String? = null,
    ): AssistantResponse {
        rememberReply(text)
        state(AssistantState.READY)
        return AssistantResponse(
            requestId = request.requestId,
            replyText = text,
            status = status,
            intent = intent,
            durationMs = (System.nanoTime() - startedAt) / 1_000_000,
            route = route,
            routeReason = routeReason,
        )
    }

    private fun state(s: AssistantState) {
        JarvisEvents.publish(AssistantEvent.StateChanged(s))
    }

    companion object {
        /** Первый SkillCall + до 2 продолжений = 3 шага. */
        private const val REACT_MAX_STEPS = 3

        /** Инструменты, чей результат нужно осмыслить: «нужен ли зонт» отвечает модель по прогнозу. */
        private val DATA_TOOLS = setOf("weather", "calendar", "system.status", "contacts.search")
    }
}
