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
import com.jarvis.core.cloud.CallListener
import com.jarvis.core.cloud.CancelHandle
import com.jarvis.core.cloud.ChainEntry
import com.jarvis.core.cloud.ChainConfig
import com.jarvis.core.cloud.ChainHealth
import com.jarvis.core.cloud.ChainRouter
import com.jarvis.core.cloud.CloudCall
import com.jarvis.core.cloud.DefaultCloudChain
import com.jarvis.core.cloud.ErrorClass
import com.jarvis.core.cloud.ThoughtStripper
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    private var allFailedUntil: Long = 0
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
            allFailedUntil = clock() + allFailedCooldownMs
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

    override fun stream(
        request: AICompletionRequest,
        listener: com.jarvis.brain.aiprovider.AIStreamListener,
        cancelFlag: AtomicBoolean,
    ): com.jarvis.brain.aiprovider.AIStreamHandle {
        val started = clock()
        val requestId = request.requestId
        val cancelled = AtomicBoolean(false)

        val thread = Thread({ runStream(request, listener, cancelFlag, started, cancelled) }, "chain-cloud-stream")
        thread.isDaemon = true
        thread.start()

        return object : com.jarvis.brain.aiprovider.AIStreamHandle {
            override val requestId: String = requestId
            override fun cancel() { cancelled.set(true) }
            override fun isCancelled(): Boolean = cancelled.get()
        }
    }

    private fun runStream(
        request: AICompletionRequest,
        listener: com.jarvis.brain.aiprovider.AIStreamListener,
        cancelFlag: AtomicBoolean,
        started: Long,
        cancelled: AtomicBoolean,
    ) {
        val requestId = request.requestId
        fun emit(type: com.jarvis.brain.aiprovider.AIStreamEventType, text: String = "", modelId: String? = null, errorCode: String? = null, errorMessage: String? = null) {
            if (cancelled.get()) return
            listener.onEvent(
                com.jarvis.brain.aiprovider.AIStreamEvent(
                    type = type, text = text, requestId = requestId, providerId = id,
                    modelId = modelId, errorCode = errorCode, errorMessage = errorMessage,
                    atElapsedMs = clock() - started,
                ),
            )
        }
        try {
            emit(com.jarvis.brain.aiprovider.AIStreamEventType.START)
            val keys = chainKeys()
            if (keys.isEmpty()) {
                emit(com.jarvis.brain.aiprovider.AIStreamEventType.ERROR, errorCode = "CHAIN_NO_STREAM_LEGACY", errorMessage = "no chain keys; legacy does not support stream")
                return
            }
            val entries = DefaultCloudChain.availableEntries(DefaultCloudChain.entries(), keys)
            var chunks = 0
            val stripper = ThoughtStripper()
            val sink = object : com.jarvis.core.cloud.TokenSink {
                override fun onFirstToken(text: String) {
                    chunks++
                    emit(com.jarvis.brain.aiprovider.AIStreamEventType.TEXT, stripper.strip(text))
                }
                override fun onToken(text: String) {
                    chunks++
                    emit(com.jarvis.brain.aiprovider.AIStreamEventType.TEXT, stripper.strip(text))
                }
                override fun onComplete(usage: Map<String, Any>?) {
                    val flushed = stripper.flush()
                    if (flushed.isNotEmpty()) emit(com.jarvis.brain.aiprovider.AIStreamEventType.TEXT, flushed)
                }
            }
            val cancellableCall = CancellableCloudCall(buildCloudCall(request))
            // корутина опроса cancelFlag (каждые 50 мс): эмитит CANCELLED сразу
            val runJob = kotlinx.coroutines.GlobalScope.launch {
                val deadline = started + config.totalDeadlineMs + 1_000
                while (!cancelled.get() && clock() < deadline) {
                    if (cancelFlag.get()) {
                        // отменить активный вызов у CloudCall, затем событие и флаг
                        cancellableCall.handle.get()?.cancel()
                        // сначала событие, потом флаг (emit подавляет после cancel)
                        listener.onEvent(
                            com.jarvis.brain.aiprovider.AIStreamEvent(
                                type = com.jarvis.brain.aiprovider.AIStreamEventType.CANCELLED,
                                requestId = requestId,
                                providerId = id,
                                atElapsedMs = clock() - started,
                            ),
                        )
                        cancelled.set(true)
                        break
                    }
                    Thread.sleep(50)
                }
            }
            try {
                val result = runBlocking {
                    router.run(ChainRouter.Request(), entries, health, cancellableCall, sink)
                }
                runJob.cancel()
                if (cancelled.get()) {
                    emit(com.jarvis.brain.aiprovider.AIStreamEventType.CANCELLED)
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
            } finally {
                runJob.cancel()
            }
        } catch (e: com.jarvis.core.cloud.AllFailedException) {
            allFailedUntil = clock() + allFailedCooldownMs
            lastFailures = e.failures
            SafeLog.i(
                TAG,
                "stream requestId=${request.requestId} allFailed winner=null hedged=${e.hedged} " +
                    "ttftMs=null totalMs=${clock() - started} failures=${e.failures} " +
                    "attempts=[${e.attemptReport.joinToString()}] cooldownMs=$allFailedCooldownMs",
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

    /** Обёртка над CloudCall, фиксирующая CancelHandle, чтобы по cancelFlag можно было отменить активный вызов. */
    private class CancellableCloudCall(
        private val delegate: CloudCall
    ) : CloudCall {
        val handle = java.util.concurrent.atomic.AtomicReference<CancelHandle?>(null)
        override fun start(entry: ChainEntry, paramCandidateIndex: Int, listener: CallListener): CancelHandle {
            val h = delegate.start(entry, paramCandidateIndex, listener)
            handle.set(h)
            return h
        }
    }

    private companion object {
        const val TAG = "Jarvis.CloudChain"
    }
}
