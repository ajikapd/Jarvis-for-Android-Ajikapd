package com.jarvis.tools.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Лёгкий HTTP GET + TTL-кэш для Stage 2 (погода / знания).
 * Retry на 429/503; без внешних зависимостей — HttpURLConnection.
 */
object SimpleHttp {

    private data class Entry(val body: String, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun get(
        url: String,
        ttlMs: Long = 0L,
        timeoutMs: Int = 8_000,
        accept: String = "application/json, text/plain, */*",
        maxAttempts: Int = 3,
    ): String {
        if (ttlMs > 0) {
            val hit = cache[url]
            if (hit != null && hit.expiresAt > System.currentTimeMillis()) return hit.body
        }
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                val body = getOnce(url, timeoutMs, accept)
                if (ttlMs > 0) {
                    cache[url] = Entry(body, System.currentTimeMillis() + ttlMs)
                }
                return body
            } catch (e: Exception) {
                lastError = e
                val msg = e.message.orEmpty()
                val retryable = msg.contains("HTTP 429") || msg.contains("HTTP 503") ||
                    msg.contains("HTTP 502") || msg.contains("timed out", true) ||
                    msg.contains("Timeout", true)
                if (!retryable || attempt == maxAttempts - 1) throw e
                val backoff = (350L * (attempt + 1)) + (50L * attempt)
                Thread.sleep(backoff.coerceAtMost(2_000L))
            }
        }
        throw lastError ?: IllegalStateException("HTTP failed")
    }

    private fun getOnce(url: String, timeoutMs: Int, accept: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            // Wikipedia требует идентифицируемый UA; пустой/ботовый режется.
            setRequestProperty(
                "User-Agent",
                "JarvisAndroid/0.2 (local voice assistant; contact: jarvis-android@local)",
            )
            setRequestProperty("Accept-Language", "ru,en;q=0.8")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = BufferedReader(InputStreamReader(stream ?: return "", Charsets.UTF_8))
                .use { it.readText() }
            if (code !in 200..299) {
                error("HTTP $code: ${body.take(200)}")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    fun getJson(url: String, ttlMs: Long = 0L): JSONObject = JSONObject(get(url, ttlMs))

    fun getJsonArray(url: String, ttlMs: Long = 0L): JSONArray = JSONArray(get(url, ttlMs))

    /** Не бросает — для мягкого fallback в KnowledgeTool. */
    fun getJsonOrNull(url: String, ttlMs: Long = 0L): JSONObject? =
        runCatching { getJson(url, ttlMs) }.getOrNull()

    fun getJsonArrayOrNull(url: String, ttlMs: Long = 0L): JSONArray? =
        runCatching { getJsonArray(url, ttlMs) }.getOrNull()
}
