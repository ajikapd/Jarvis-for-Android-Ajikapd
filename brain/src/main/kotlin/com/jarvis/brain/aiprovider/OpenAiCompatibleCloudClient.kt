package com.jarvis.brain.aiprovider

import com.jarvis.core.privacy.SensitivityClassifier

/**
 * OpenAI-compatible cloud client defaults and model identifiers.
 * The endpoint is always supplied by the user/provider wiring.
 */
object NaraRouterDefaults {
    /** Быстрые ответы для совместимых cloud-моделей. */
    const val MODEL_FAST = "agnes-2.5-flash"
    /** Модель качества для совместимых cloud-моделей. */
    const val MODEL_QUALITY = "mistral-medium-3-5"
    const val CONNECT_TIMEOUT_MS = 12_000
    const val READ_TIMEOUT_MS = 45_000
    const val MAX_OUTPUT_TOKENS = 256
    /** UA для всех облачных запросов (некоторые шлюзы режут дефолтный Java-UA). */
    const val USER_AGENT = "Jarvis/1.0 (Android; personal assistant)"
}

data class CloudLlmHttpResponse(
    val code: Int,
    val body: String,
)

/** Результат streamChat: код, тело ошибки (≤2 КБ), накопленный текст, usage. */
data class StreamChatResult(
    val code: Int?,
    val errorBody: String,
    val text: String,
    val usage: Map<String, Any>?,
)

/** Максимум захвата тела ошибки. */
internal const val MAX_ERROR_BODY_CHARS = 2048

fun interface CloudLlmHttpTransport {
    fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): CloudLlmHttpResponse
}

/**
 * V2-07: streaming HTTP transport (SSE / NDJSON lines).
 * [onLine] return false to abort reading.
 */
fun interface CloudLlmStreamTransport {
    fun postJsonStream(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        onLine: (String) -> Boolean,
    ): Int
}

/**
 * HttpURLConnection transport — без лишних зависимостей в :brain.
 */
class UrlConnectionCloudLlmTransport : CloudLlmHttpTransport, CloudLlmStreamTransport {
    override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): CloudLlmHttpResponse {
        val conn = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", NaraRouterDefaults.USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return CloudLlmHttpResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }

    override fun postJsonStream(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        onLine: (String) -> Boolean,
    ): Int {
        val conn = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("User-Agent", NaraRouterDefaults.USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!onLine(line)) break
                }
            }
            return code
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Клиент OpenAI-compatible Chat Completions (+ SSE streaming).
 */
class OpenAiCompatibleCloudClient(
    private val baseUrlProvider: () -> String,
    private val apiKeyProvider: () -> String?,
    private val transport: CloudLlmHttpTransport = UrlConnectionCloudLlmTransport(),
    private val streamTransport: CloudLlmStreamTransport =
        (transport as? CloudLlmStreamTransport) ?: UrlConnectionCloudLlmTransport(),
    private val connectTimeoutMs: Int = NaraRouterDefaults.CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = NaraRouterDefaults.READ_TIMEOUT_MS,
) {
    fun hasApiKey(): Boolean = !apiKeyProvider()?.trim().isNullOrBlank()

    fun isConfigured(): Boolean = hasApiKey() && baseUrlProvider().trim().isNotBlank()

    fun chatCompletion(
        model: String,
        systemPrompt: String,
        userText: String,
        history: List<String>,
        maxTokens: Int = NaraRouterDefaults.MAX_OUTPUT_TOKENS,
        extraBody: Map<String, Any> = emptyMap(),
    ): Result<String> {
        val key = apiKeyProvider()?.trim().orEmpty()
        if (key.isBlank()) {
            return Result.failure(IllegalStateException("API key missing"))
        }
        val base = baseUrlProvider().trim().trimEnd('/')
        val url = "$base/chat/completions"
        val messages = buildMessages(systemPrompt, history, userText)
        val body = buildJsonBody(model, messages, maxTokens, stream = false, extraBody = extraBody)
        return try {
            val response = transport.postJson(
                url = url,
                headers = mapOf(
                    "Authorization" to "Bearer $key",
                    "User-Agent" to NaraRouterDefaults.USER_AGENT,
                ),
                body = body,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
            )
            if (response.code !in 200..299) {
                Result.failure(
                    IllegalStateException("HTTP ${response.code}: ${response.body.take(200)}"),
                )
            } else {
                val text = extractAssistantContent(response.body)
                if (text.isNullOrBlank()) {
                    Result.failure(IllegalStateException("empty content"))
                } else {
                    Result.success(text.trim())
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Real SSE streaming. Invokes [onDelta] for each content delta as received from the server.
     * Returns aggregated text or failure. Does NOT fake-split a completed response.
     */
    fun chatCompletionStream(
        model: String,
        systemPrompt: String,
        userText: String,
        history: List<String>,
        maxTokens: Int = NaraRouterDefaults.MAX_OUTPUT_TOKENS,
        extraBody: Map<String, Any> = emptyMap(),
        isCancelled: () -> Boolean = { false },
        onDelta: (String) -> Unit,
    ): Result<String> {
        val key = apiKeyProvider()?.trim().orEmpty()
        if (key.isBlank()) {
            return Result.failure(IllegalStateException("API key missing"))
        }
        val base = baseUrlProvider().trim().trimEnd('/')
        val url = "$base/chat/completions"
        val messages = buildMessages(systemPrompt, history, userText)
        val body = buildJsonBody(model, messages, maxTokens, stream = true, extraBody = extraBody)
        val acc = StringBuilder()
        return try {
            val code = streamTransport.postJsonStream(
                url = url,
                headers = mapOf(
                    "Authorization" to "Bearer $key",
                    "Accept" to "text/event-stream",
                    "User-Agent" to NaraRouterDefaults.USER_AGENT,
                ),
                body = body,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
            ) { line ->
                if (isCancelled()) return@postJsonStream false
                val payload = parseSseDataLine(line) ?: return@postJsonStream true
                if (payload == "[DONE]") return@postJsonStream false
                val delta = extractStreamDelta(payload)
                if (!delta.isNullOrEmpty()) {
                    acc.append(delta)
                    onDelta(delta)
                }
                true
            }
            if (isCancelled()) {
                return Result.failure(IllegalStateException("CANCELLED"))
            }
            if (code !in 200..299) {
                return Result.failure(IllegalStateException("HTTP $code stream"))
            }
            val text = acc.toString().trim()
            if (text.isBlank()) {
                Result.failure(IllegalStateException("empty stream content"))
            } else {
                Result.success(text)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Streaming chat для цепочки (9б-2): возвращает код HTTP, тело ошибки
     * (все не-data строки при коде не 2xx, до 2 КБ), накопленный текст и
     * usage (best-effort, из chunk с полем usage; null если нет).
     */
    fun streamChat(
        model: String,
        systemPrompt: String,
        userText: String,
        history: List<String>,
        maxTokens: Int = NaraRouterDefaults.MAX_OUTPUT_TOKENS,
        extraBody: Map<String, Any> = emptyMap(),
        isCancelled: () -> Boolean = { false },
        onDelta: (String) -> Unit,
    ): StreamChatResult {
        val key = apiKeyProvider()?.trim().orEmpty()
        if (key.isBlank()) {
            return StreamChatResult(401, "api key missing", "", null)
        }
        val base = baseUrlProvider().trim().trimEnd('/')
        val url = "$base/chat/completions"
        val messages = buildMessages(systemPrompt, history, userText)
        val body = buildJsonBody(model, messages, maxTokens, stream = true, extraBody = extraBody)
        val acc = StringBuilder()
        val errBody = StringBuilder()
        var usage: Map<String, Any>? = null
        return try {
            val code = streamTransport.postJsonStream(
                url = url,
                headers = mapOf(
                    "Authorization" to "Bearer $key",
                    "Accept" to "text/event-stream",
                    "User-Agent" to NaraRouterDefaults.USER_AGENT,
                ),
                body = body,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
            ) { line ->
                if (isCancelled()) return@postJsonStream false
                // не-data строки при не-2xx ответе идут в тело ошибки
                val payload = parseSseDataLine(line)
                if (payload == null) {
                    if (errBody.length < MAX_ERROR_BODY_CHARS) {
                        errBody.append(line).append('\n')
                    }
                    return@postJsonStream true
                }
                if (payload == "[DONE]") return@postJsonStream false
                // usage из чанка (best-effort)
                val chunkUsage = extractStreamUsage(payload)
                if (chunkUsage != null) usage = chunkUsage
                val delta = extractStreamDelta(payload)
                if (!delta.isNullOrEmpty()) {
                    acc.append(delta)
                    onDelta(delta)
                }
                true
            }
            if (isCancelled()) {
                return StreamChatResult(0, "", acc.toString(), null)
            }
            if (code !in 200..299) {
                return StreamChatResult(code, errBody.toString().take(MAX_ERROR_BODY_CHARS), "", null)
            }
            StreamChatResult(code, "", acc.toString(), usage)
        } catch (t: Throwable) {
            StreamChatResult(null, t.message.orEmpty().take(MAX_ERROR_BODY_CHARS), "", null)
        }
    }

    /** Best-effort parse поля usage из стрим-чанка (без org.json — regex-fallback). */
    internal fun extractStreamUsage(json: String): Map<String, Any>? {
        if (json.isBlank() || json == "[DONE]") return null
        val promptM = Regex("\"prompt_tokens\"\\s*:\\s*(\\d+)").find(json) ?: return null
        val completionM = Regex("\"completion_tokens\"\\s*:\\s*(\\d+)").find(json)
        val out = mutableMapOf<String, Any>(
            "prompt_tokens" to promptM.groupValues[1].toInt(),
        )
        if (completionM != null) {
            out["completion_tokens"] = completionM.groupValues[1].toInt()
        }
        val reasoningM = Regex("\"reasoning_tokens\"\\s*:\\s*(\\d+)").find(json)
        if (reasoningM != null) {
            out["reasoning_tokens"] = reasoningM.groupValues[1].toInt()
        }
        return out
    }

    internal fun parseSseDataLine(line: String): String? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith(":")) return null
        return when {
            t.startsWith("data:") -> t.removePrefix("data:").trim()
            else -> null
        }
    }

    /** Extract delta.content from OpenAI stream chunk JSON. */
    internal fun extractStreamDelta(json: String): String? {
        if (json.isBlank() || json == "[DONE]") return null
        return try {
            val root = org.json.JSONObject(json)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta") ?: return null
            delta.optString("content").takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            val m = Regex(""""content"\s*:\s*"((?:\\.|[^"\\])*)"""").find(json) ?: return null
            m.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Собирает messages для облачного запроса.
     *
     * Последняя линия защиты приватности: перед отправкой каждая строка проходит
     * [SensitivityClassifier.mask] — коды и номера карт заменяются на «[скрыто]».
     * Формат запроса не меняется, обычные числа (телефоны, время, цены) не трогаются.
     *
     * internal — только для unit-тестов внутри :brain.
     */
    internal fun buildMessages(
        systemPrompt: String,
        history: List<String>,
        userText: String,
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(8)
        if (systemPrompt.isNotBlank()) {
            out += "system" to SensitivityClassifier.mask(systemPrompt).take(1_500)
        }
        history.takeLast(8).forEach { line ->
            val lower = line.lowercase()
            when {
                lower.startsWith("user:") ->
                    out += "user" to SensitivityClassifier.mask(line.substringAfter(':').trim()).take(800)
                lower.startsWith("assistant:") ->
                    out += "assistant" to SensitivityClassifier.mask(line.substringAfter(':').trim()).take(800)
            }
        }
        out += "user" to SensitivityClassifier.mask(userText).take(AICompletionRequest.MAX_USER)
        return out
    }

    private fun buildJsonBody(
        model: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        stream: Boolean,
        extraBody: Map<String, Any> = emptyMap(),
    ): String {
        val sb = StringBuilder(512)
        sb.append('{')
        sb.append("\"model\":").append(jsonString(model)).append(',')
        sb.append("\"max_tokens\":").append(maxTokens.coerceIn(16, 1024)).append(',')
        sb.append("\"temperature\":0.7,")
        if (stream) sb.append("\"stream\":true,")
        sb.append("\"messages\":[")
        messages.forEachIndexed { i, (role, content) ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"role\":").append(jsonString(role)).append(',')
            sb.append("\"content\":").append(jsonString(content))
            sb.append('}')
        }
        sb.append(']')
        // extraBody: значения String/Boolean/Number верхнего уровня;
        // ключи model/messages/stream/max_tokens/temperature не перекрываются
        extraBody
            .filterKeys { it !in PROTECTED_BODY_KEYS }
            .filterValues { it is String || it is Boolean || it is Number }
            .forEach { (k, v) ->
                sb.append(',')
                sb.append(jsonString(k)).append(':')
                when (v) {
                    is String -> sb.append(jsonString(v))
                    is Boolean -> sb.append(v)
                    is Number -> sb.append(v)
                    else -> sb.append("null")
                }
            }
        sb.append('}')
        return sb.toString()
    }

    /** Ключи JSON-тела, которые extraBody не может перекрыть. */
    internal val PROTECTED_BODY_KEYS = setOf("model", "messages", "stream", "max_tokens", "temperature")

    private fun jsonString(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    /**
     * Minimal parse of choices[0].message.content without full JSON lib dependency quirks.
     */
    internal fun extractAssistantContent(json: String): String? {
        return try {
            val root = org.json.JSONObject(json)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val msg = choices.getJSONObject(0).optJSONObject("message") ?: return null
            msg.optString("content").takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            val m = Regex(""""content"\s*:\s*"((?:\\.|[^"\\])*)"""").find(json) ?: return null
            m.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
    }
}
