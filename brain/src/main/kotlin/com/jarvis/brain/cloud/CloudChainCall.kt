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
import java.util.concurrent.atomic.AtomicReference

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
    /** Таймаут подключения (9б-2c A2): «висящее» подключение отваливается за ~4 с, а не 12. */
    private val connectTimeoutMs: Int = ChainConfig.Default.connectTimeoutMs.toInt(),
    private val executor: ExecutorService = DEFAULT_EXECUTOR,
) : CloudCall {

    /**
     * Один экземпляр обслуживает все попытки одного запуска цепочки, поэтому всё
     * изменяемое состояние — на попытку: отмена проигравшего не задевает победителя.
     */
    override fun start(entry: ChainEntry, paramCandidateIndex: Int, listener: CallListener): CancelHandle {
        val cancelled = AtomicBoolean(false)
        val abortConnection = AtomicReference<(() -> Unit)?>(null)
        val params = entry.paramCandidates.getOrNull(paramCandidateIndex) ?: emptyMap()
        executor.execute {
            if (cancelled.get()) return@execute
            val stored = keyFor(entry.providerId)
            if (stored.isNullOrBlank()) {
                safeError(listener, cancelled, null, 401, "api key missing")
                return@execute
            }
            // GigaChat: ключ меняется на временный токен; сбой сети остаётся сбоем сети, а не «плохим ключом».
            val key = if (entry.providerId == GigaChatAuth.PROVIDER) {
                val auth = GigaChatAuth.token(stored)
                if (auth.token == null) {
                    safeError(listener, cancelled, stored, auth.code, auth.error)
                    return@execute
                }
                auth.token
            } else stored
            var firstTokenSent = false
            val prompt = if (entry.providerId == "safev") {
                "$systemPrompt\nОтвечай только готовым ответом на русском языке. Не показывай рассуждения, план, перевод вопроса или английский текст."
            } else systemPrompt
            val client = OpenAiCompatibleCloudClient(
                baseUrlProvider = { entry.baseUrl },
                apiKeyProvider = { key },
                transport = transport,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
            )
            val result = client.streamChat(
                model = entry.modelId,
                systemPrompt = prompt,
                userText = userText,
                history = history,
                maxTokens = if (entry.providerId == "safev") maxOf(maxTokens, 512) else maxTokens,
                extraBody = params,
                isCancelled = { cancelled.get() },
                onAbortable = { abort ->
                    abortConnection.set(abort)
                    // отмена могла прийти раньше, чем появилось соединение
                    if (cancelled.get()) abort()
                },
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
                result.code == null -> safeError(listener, cancelled, key, null, result.errorBody)
                result.code in 200..299 -> {
                    if (cancelled.get()) return@execute
                    listener.onComplete(result.usage)
                }
                else -> safeError(listener, cancelled, key, result.code, result.errorBody)
            }
        }
        return object : CancelHandle {
            override fun cancel() {
                cancelled.set(true)
                abortConnection.get()?.invoke()
            }
        }
    }

    private fun safeError(listener: CallListener, cancelled: AtomicBoolean, key: String?, code: Int?, body: String?) {
        if (cancelled.get()) return
        val redacted = redactKey(body, key)
        listener.onError(code, redacted)
        SafeLog.w(TAG, "chainCall error code=$code body=${redacted?.take(120)}")
    }

    /** Убирает значение ключа из текста ошибки (лучшее усилие). */
    private fun redactKey(body: String?, key: String?): String? {
        if (body == null) return null
        return if (!key.isNullOrBlank()) body.replace(key, "[REDACTED]") else body
    }

    companion object {
        private const val TAG = "Jarvis.CloudChainCall"
        /** Кешируемый пул daemon-потоков. */
        private val DEFAULT_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "cloud-chain").apply { isDaemon = true }
        }
    }
}
