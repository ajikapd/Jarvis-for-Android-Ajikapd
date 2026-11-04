package com.jarvis.brain.cloud

import com.jarvis.brain.aiprovider.AICompletionRequest
import com.jarvis.brain.aiprovider.AICompletionResult
import com.jarvis.brain.aiprovider.AICompletionStatus
import com.jarvis.brain.aiprovider.AIProviderAvailability
import com.jarvis.brain.aiprovider.AIProviderCapabilities
import com.jarvis.brain.aiprovider.AIProviderError
import com.jarvis.brain.aiprovider.AIProviderErrorCategory
import com.jarvis.brain.aiprovider.AIProviderKind
import com.jarvis.brain.aiprovider.CloudLLMProvider
import com.jarvis.brain.aiprovider.CloudLlmHttpTransport
import com.jarvis.brain.aiprovider.StreamingAIProvider
import com.jarvis.core.cloud.ChainConfig
import com.jarvis.core.cloud.ChainHealth
import com.jarvis.core.cloud.ChainRouter
import com.jarvis.core.cloud.CloudCall
import com.jarvis.core.cloud.DefaultCloudChain
import com.jarvis.core.cloud.ErrorClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ChainCloudProvider (9б-2b): облачный провайдер поверх цепочки
 * ChainRouter + CloudChainCall. При отсутствии ключей groq/gemini
 * делегирует все вызовы legacy [CloudLLMProvider].
 */
class ChainCloudProvider(
    private val legacy: CloudLLMProvider,
    private val router: ChainRouter,
    internal val health: ChainHealth,
    private val keyFor: (providerId: String) -> String?,
    private val providersWithKeys: () -> Set<String>,
    transport: CloudLlmHttpTransport? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val config: ChainConfig = ChainConfig.Default,
    private val allFailedCooldownMs: Long = 10_000,
    /** Фабрика CloudCall для инъекции в тестах; null → реальный CloudChainCall. */
    private val callFactory: ((AICompletionRequest) -> CloudCall)? = null,
) : StreamingAIProvider {

    override val id: String = CloudLLMProvider.ID
    override val displayName: String = "Cloud LLM (chain)"
    override val kind: AIProviderKind = AIProviderKind.CLOUD
    override val capabilities: AIProviderCapabilities = legacy.capabilities

    @Volatile var lastWinner: String? = null
        private set
    @Volatile var lastHedged: Boolean = false
        private set
    @Volatile var lastFailures: List<ErrorClass> = emptyList()
        private set

    @Volatile private var allFailedUntil: Long = 0
    private var cachedKeys: Set<String>? = null
    private var cachedKeysAt: Long = 0
    private val keysCacheTtlMs = 2_000L

    private fun chainKeys(): Set<String> {
        val now = clock()
        val c = cachedKeys
        if (c != null && now - cachedKeysAt < keysCacheTtlMs) return c
        val fresh = providersWithKeys().filter { it == "groq" || it == "gemini" }.toSet()
        cachedKeys = fresh
        cachedKeysAt = now
        return fresh
    }

    fun invalidate() {
        cachedKeys = null
        cachedKeysAt = 0
    }

    fun onKeyChanged(providerId: String) {
        health.reset(providerId)
        invalidate()
        SafeLog.i(TAG, "keyChanged provider=$providerId")
    }

    override fun availability(): AIProviderAvailability {
        val now = clock()
        if (now < allFailedUntil) return AIProviderAvailability.UNAVAILABLE
        val keys = chainKeys()
        if (keys.isNotEmpty()) return AIProviderAvailability.AVAILABLE
        return if (legacy.availability() == AIProviderAvailability.AVAILABLE) {
            AIProviderAvailability.AVAILABLE
        } else {
            AIProviderAvailability.UNAVAILABLE
        }
    }

    override fun warm() = Unit

    override fun complete(request: AICompletionRequest): AICompletionResult {
        val started = clock()
        if (request.cancelRequested) {
            return AICompletionResult(
                requestId = request.requestId,
                providerId = id,
                status = AICompletionStatus.CANCELLED,
                error = AIProviderError(AIProviderErrorCategory.CANCELLED, "CANCELLED", "Cancellation requested"),
            )
        }
        val keys = chainKeys()
        if (keys.isEmpty()) {
            return legacy.complete(request)
        }
        val entries = DefaultCloudChain.availableEntries(DefaultCloudChain.entries(), keys)
        val sinkText = StringBuilder()
        var ttft: Long? = null
        val sink = object : com.jarvis.core.cloud.TokenSink {
            override fun onFirstToken(text: String) {
                if (ttft == null) ttft = clock() - started
                sinkText.append(text)
            }
            override fun onToken(text: String) { sinkText.append(text) }
            override fun onComplete(usage: Map<String, Any>?) { }
        }
        return try {
            val result = runBlocking {
                router.run(ChainRouter.Request(), entries, health, buildCloudCall(request), sink)
            }
            lastWinner = "${result.entry.providerId}/${result.entry.modelId}"
            lastHedged = result.hedged
            lastFailures = result.failures
            SafeLog.i(
                TAG,
                "complete requestId=${request.requestId} winner=$lastWinner hedged=${result.hedged} " +
                    "ttftMs=${result.timeToFirstTokenMs} totalMs=${clock() - started} " +
                    "attempts=[${result.attemptReport.joinToString()}]",
            )
            AICompletionResult(
                requestId = request.requestId,
                providerId = id,
                status = AICompletionStatus.SUCCESS,
                text = sinkText.toString().trim(),
                latencyMs = clock() - started,
                streamed = true,
                modelId = result.entry.modelId,
                ttftMs = result.timeToFirstTokenMs,
                ttftKind = com.jarvis.core.runtime.TtftKind.REAL,
            )
        } catch (e: com.jarvis.core.cloud.AllFailedException) {
            if (!e.partial) allFailedUntil = clock() + allFailedCooldownMs
            lastFailures = e.failures
            SafeLog.i(
                TAG,
                "complete requestId=${request.requestId} allFailed winner=null hedged=${e.hedged} " +
                    "ttftMs=null totalMs=${clock() - started} failures=${e.failures} " +
                    "attempts=[${e.attemptReport.joinToString()}] cooldownMs=$allFailedCooldownMs",
            )
            AICompletionResult(
                requestId = request.requestId,
                providerId = id,
                status = AICompletionStatus.FAILURE,
                error = AIProviderError(
                    category = dominantFailureCategory(e.failures),
                    code = "CHAIN_ALL_FAILED",
                    message = e.failures.joinToString(","),
                ),
            )
        }
    }

    private fun dominantFailureCategory(failures: List<ErrorClass>): AIProviderErrorCategory =
        when (failures.filter { it == ErrorClass.RATE_LIMITED || it == ErrorClass.RATE_LIMITED_DAILY }.size * 2 >= failures.size) {
            true -> AIProviderErrorCategory.TIMEOUT
            false -> AIProviderErrorCategory.NETWORK
        }

    /**
     * Блокирующий, как у [CloudLLMProvider]: возвращается, когда все события уже доставлены.
     * [com.jarvis.brain.aiprovider.ChatStreamPipeline] читает результат сразу после возврата.
     */
    override fun stream(
        request: AICompletionRequest,
        listener: com.jarvis.brain.aiprovider.AIStreamListener,
        cancelFlag: AtomicBoolean,
    ): com.jarvis.brain.aiprovider.AIStreamHandle {
        if (chainKeys().isEmpty()) return legacy.stream(request, listener, cancelFlag)
        if (request.cancelRequested) cancelFlag.set(true)
        runStream(request, listener, cancelFlag)
        return object : com.jarvis.brain.aiprovider.AIStreamHandle {
            override val requestId: String = request.requestId
            override fun cancel() { cancelFlag.set(true) }
            override fun isCancelled(): Boolean = cancelFlag.get()
        }
    }

    private fun runStream(
        request: AICompletionRequest,
        listener: com.jarvis.brain.aiprovider.AIStreamListener,
        cancelFlag: AtomicBoolean,
    ) {
        val started = clock()
        val requestId = request.requestId
        fun send(type: com.jarvis.brain.aiprovider.AIStreamEventType, text: String = "", modelId: String? = null, errorCode: String? = null, errorMessage: String? = null) {
            listener.onEvent(
                com.jarvis.brain.aiprovider.AIStreamEvent(
                    type = type, text = text, requestId = requestId, providerId = id,
                    modelId = modelId, errorCode = errorCode, errorMessage = errorMessage,
                    atElapsedMs = clock() - started,
                ),
            )
        }
        fun emit(type: com.jarvis.brain.aiprovider.AIStreamEventType, text: String = "", modelId: String? = null, errorCode: String? = null, errorMessage: String? = null) {
            if (!cancelFlag.get()) send(type, text, modelId, errorCode, errorMessage)
        }
        try {
            emit(com.jarvis.brain.aiprovider.AIStreamEventType.START)
            val entries = DefaultCloudChain.availableEntries(DefaultCloudChain.entries(), chainKeys())
            var chunks = 0
            val sink = object : com.jarvis.core.cloud.TokenSink {
                override fun onFirstToken(text: String) {
                    chunks++
                    emit(com.jarvis.brain.aiprovider.AIStreamEventType.TEXT, text)
                }
                override fun onToken(text: String) {
                    chunks++
                    emit(com.jarvis.brain.aiprovider.AIStreamEventType.TEXT, text)
                }
                override fun onComplete(usage: Map<String, Any>?) = Unit
            }
            val call = buildCloudCall(request)
            val result = runBlocking {
                val run = async(Dispatchers.Default) {
                    runCatching { router.run(ChainRouter.Request(), entries, health, call, sink) }
                }
                // отмена корутины роутера гасит все попытки без записи сбоев
                while (withTimeoutOrNull(CANCEL_POLL_MS) { run.join() } == null) {
                    if (cancelFlag.get()) run.cancel()
                }
                run.await().getOrThrow()
            }
            if (cancelFlag.get()) {
                send(com.jarvis.brain.aiprovider.AIStreamEventType.CANCELLED)
                return
            }
            lastWinner = "${result.entry.providerId}/${result.entry.modelId}"
            lastHedged = result.hedged
            lastFailures = result.failures
            emit(com.jarvis.brain.aiprovider.AIStreamEventType.COMPLETE, modelId = result.entry.modelId)
            emit(
                com.jarvis.brain.aiprovider.AIStreamEventType.METADATA,
                text = "ttftMs=${result.timeToFirstTokenMs};chunks=$chunks;latencyMs=${clock() - started};hedged=${result.hedged};entry=${result.entry.providerId}/${result.entry.modelId}",
                modelId = result.entry.modelId,
            )
            SafeLog.i(
                TAG,
                "stream requestId=${request.requestId} winner=$lastWinner hedged=${result.hedged} " +
                    "ttftMs=${result.timeToFirstTokenMs} totalMs=${clock() - started} chunks=$chunks " +
                    "attempts=[${result.attemptReport.joinToString()}]",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            SafeLog.i(TAG, "stream requestId=${request.requestId} cancelled totalMs=${clock() - started}")
            send(com.jarvis.brain.aiprovider.AIStreamEventType.CANCELLED)
        } catch (e: com.jarvis.core.cloud.AllFailedException) {
            if (cancelFlag.get()) {
                send(com.jarvis.brain.aiprovider.AIStreamEventType.CANCELLED)
                return
            }
            // обрыв посреди ответа — не повод уводить следующие запросы с облака
            if (!e.partial) allFailedUntil = clock() + allFailedCooldownMs
            lastFailures = e.failures
            SafeLog.i(
                TAG,
                "stream requestId=${request.requestId} allFailed winner=null hedged=${e.hedged} partial=${e.partial} " +
                    "ttftMs=null totalMs=${clock() - started} failures=${e.failures} " +
                    "attempts=[${e.attemptReport.joinToString()}] cooldownMs=${if (e.partial) 0 else allFailedCooldownMs}",
            )
            emit(
                com.jarvis.brain.aiprovider.AIStreamEventType.ERROR,
                errorCode = "CHAIN_ALL_FAILED",
                errorMessage = e.failures.joinToString(","),
            )
        }
    }

    private val realTransport: CloudLlmHttpTransport = transport ?: com.jarvis.brain.aiprovider.UrlConnectionCloudLlmTransport()

    private fun buildCloudCall(request: AICompletionRequest): CloudCall =
        callFactory?.invoke(request) ?: CloudChainCall(
            systemPrompt = legacy.resolveSystem(request),
            history = request.history,
            userText = request.userText,
            maxTokens = capabilities.maxOutputTokens.coerceAtLeast(16),
            keyFor = keyFor,
            transport = realTransport,
            connectTimeoutMs = config.connectTimeoutMs.toInt(),
            readTimeoutMs = config.totalDeadlineMs.toInt(),
        )

    private companion object {
        const val TAG = "Jarvis.CloudChain"
        const val CANCEL_POLL_MS = 50L
    }
}
