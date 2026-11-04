package com.jarvis.brain.cloud

import com.jarvis.brain.aiprovider.CloudLlmHttpTransport
import com.jarvis.brain.aiprovider.CloudLlmStreamTransport
import com.jarvis.brain.aiprovider.OpenAiCompatibleCloudClient
import com.jarvis.brain.aiprovider.UrlConnectionCloudLlmTransport
import com.jarvis.core.cloud.CancelHandle
import com.jarvis.core.cloud.ChainConfig
import com.jarvis.core.cloud.ChainEntry
import com.jarvis.core.cloud.CallListener
import com.jarvis.core.cloud.CloudCall
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CloudChainCall (9б-2): адаптер ChainEntry + CloudCall поверх
 * OpenAiCompatibleCloudClient.streamChat. Неблокирующий start(),
 * колбэки по контракту ChainRouter, отмена через CancelHandle.
 */
class CloudChainCall(
    private val systemPrompt: String,
    private val history: List<String>,
    private val userText: String,
    private val maxTokens: Int,
    private val keyFor: (providerId: String) -> String?,
    private val transport: CloudLlmHttpTransport = UrlConnectionCloudLlmTransport(),
    private val readTimeoutMs: Int = ChainConfig.Default.totalDeadlineMs.toInt(),
    private val executor: ExecutorService = DEFAULT_EXECUTOR,
) : CloudCall {

    private val cancelled = AtomicBoolean(false)
    private var firstTokenSent = false
    @Volatile private var activeKey: String? = null

    override fun start(entry: ChainEntry, paramCandidateIndex: Int, listener: CallListener): CancelHandle {
        cancelled.set(false)
        firstTokenSent = false
        val params = entry.paramCandidates.getOrNull(paramCandidateIndex) ?: emptyMap()
        executor.execute {
            if (cancelled.get()) return@execute
            val key = keyFor(entry.providerId)
            activeKey = key
            if (key.isNullOrBlank()) {
                safeError(listener, 401, "api key missing")
                return@execute
            }
            val client = OpenAiCompatibleCloudClient(
                baseUrlProvider = { entry.baseUrl },
                apiKeyProvider = { key },
                transport = transport,
                readTimeoutMs = readTimeoutMs,
            )
            val result = client.streamChat(
                model = entry.modelId,
                systemPrompt = systemPrompt,
                userText = userText,
                history = history,
                maxTokens = maxTokens,
                extraBody = params,
                isCancelled = { cancelled.get() },
            ) { delta ->
                if (cancelled.get()) return@streamChat
                if (!firstTokenSent) {
                    firstTokenSent = true
                    listener.onFirstToken(delta)
                } else {
                    listener.onToken(delta)
                }
            }
            if (cancelled.get()) return@execute
            when {
                result.code == null -> safeError(listener, null, result.errorBody)
                result.code in 200..299 -> {
                    if (cancelled.get()) return@execute
                    listener.onComplete(result.usage)
                }
                else -> safeError(listener, result.code, result.errorBody)
            }
        }
        return object : CancelHandle {
            override fun cancel() { cancelled.set(true) }
        }
    }

    private fun safeError(listener: CallListener, code: Int?, body: String?) {
        if (cancelled.get()) return
        val redacted = redactKey(body)
        listener.onError(code, redacted)
        SafeLog.w(TAG, "chainCall error code=$code body=${redacted?.take(120)}")
    }

    /** Убирает значение ключа из текста ошибки (лучшее усилие). */
    private fun redactKey(body: String?): String? {
        if (body == null) return null
        val k = activeKey ?: return body
        return if (k.isNotBlank()) body.replace(k, "[REDACTED]") else body
    }

    companion object {
        private const val TAG = "Jarvis.CloudChainCall"
        /** Кешируемый пул daemon-потоков. */
        private val DEFAULT_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "cloud-chain").apply { isDaemon = true }
        }
    }
}
