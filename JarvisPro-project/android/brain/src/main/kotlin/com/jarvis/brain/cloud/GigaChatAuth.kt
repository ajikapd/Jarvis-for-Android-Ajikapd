package com.jarvis.brain.cloud

import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * GigaChat не принимает ключ напрямую: Authorization key меняется на токен доступа на 30 минут.
 * Токен кэшируется и обновляется за минуту до конца. Сам ключ никуда, кроме OAuth Сбера, не уходит.
 */
object GigaChatAuth {
    const val PROVIDER = "gigachat"
    const val OAUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
    private const val SCOPE = "GIGACHAT_API_PERS"
    private const val REFRESH_MARGIN_MS = 60_000L
    private const val DEFAULT_LIFETIME_MS = 30 * 60_000L

    /** Итог обмена: токен или код/текст ошибки для цепочки. */
    data class Result(val token: String?, val code: Int?, val error: String?)

    /** POST формы: (url, заголовки, тело) → (код или null при сбое сети, тело). */
    fun interface Poster {
        fun post(url: String, headers: Map<String, String>, body: String): Pair<Int?, String?>
    }

    private data class Cached(val keyHash: Int, val token: String, val expiresAtMs: Long)

    @Volatile private var cached: Cached? = null

    @Volatile var poster: Poster = Poster(::httpPost)
    @Volatile var clock: () -> Long = System::currentTimeMillis

    @Synchronized
    fun token(authKey: String): Result {
        val now = clock()
        cached?.let { if (it.keyHash == authKey.hashCode() && it.expiresAtMs - REFRESH_MARGIN_MS > now) return Result(it.token, 200, null) }
        val (code, body) = poster.post(
            OAUTH_URL,
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Accept" to "application/json",
                "RqUID" to UUID.randomUUID().toString(),
                "Authorization" to "Basic ${authKey.removePrefix("Basic ").trim()}",
            ),
            "scope=$SCOPE",
        )
        if (code == null || code !in 200..299) return Result(null, code, body)
        val token = field(body, "access_token") ?: return Result(null, 500, "no access_token")
        val expires = expiresAtMs(field(body, "expires_at")?.toLongOrNull(), now)
        cached = Cached(authKey.hashCode(), token, expires)
        return Result(token, code, null)
    }

    fun forget() { cached = null }

    /** expires_at приходит то в секундах, то в миллисекундах. */
    internal fun expiresAtMs(raw: Long?, now: Long): Long = when {
        raw == null -> now + DEFAULT_LIFETIME_MS
        raw < 100_000_000_000L -> raw * 1000
        else -> raw
    }

    internal fun field(json: String?, name: String): String? {
        json ?: return null
        val m = Regex("\"$name\"\\s*:\\s*(\"([^\"]*)\"|(\\d+))").find(json) ?: return null
        return m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { null }
    }

    private fun httpPost(url: String, headers: Map<String, String>, body: String): Pair<Int?, String?> = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout = 10_000
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        code to text
    } catch (e: Exception) {
        null to (e.javaClass.simpleName + ": " + (e.message ?: ""))
    }
}
