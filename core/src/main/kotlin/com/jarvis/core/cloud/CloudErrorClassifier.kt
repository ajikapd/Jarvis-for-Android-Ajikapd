package com.jarvis.core.cloud

enum class ErrorClass {
    RATE_LIMITED,
    RATE_LIMITED_DAILY,
    AUTH_OR_REGION,
    BLOCKED_BY_CLOUDFLARE,
    OVERLOADED,
    SERVER_ERROR,
    NETWORK_ERROR,
    BAD_REQUEST,
    EMPTY_RESPONSE,
    OTHER
}

object CloudErrorClassifier {
    private val DAILY_RE = Regex("per\\s*day|daily|\\brpd\\b|\\btpd\\b|requests per day|tokens per day", RegexOption.IGNORE_CASE)

    /** Секунды из Retry-After-подобных строк: null, если не найдено. */
    private val GEMINI_RETRY_RE = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"")
    private val GROQ_RETRY_RE = Regex("try again in ((?:\\d+(?:\\.\\d+)?\\s*(?:ms|s|m)\\s*)+)", RegexOption.IGNORE_CASE)
    private val GROQ_SEGMENT_RE = Regex("(\\d+(?:\\.\\d+)?)\\s*(ms|s|m)", RegexOption.IGNORE_CASE)

    /**
     * Parse retry-after из тела ошибки (округление вверх):
     * - Gemini: "retryDelay": "20s" → 20
     * - Groq: "try again in 7.5s" → 8; "try again in 1m3.5s" → 64
     * null, если не найдено.
     */
    fun parseRetryAfterSeconds(body: String?): Int? {
        val text = body ?: return null
        GEMINI_RETRY_RE.find(text)?.let { m ->
            return kotlin.math.ceil(m.groupValues[1].toDouble()).toInt()
        }
        GROQ_RETRY_RE.find(text)?.let { m ->
            // сумма всех сегментов вида "1m3.5s"
            var seconds = 0.0
            GROQ_SEGMENT_RE.findAll(m.groupValues[1]).forEach { seg ->
                val value = seg.groupValues[1].toDouble()
                seconds += when (seg.groupValues[2].lowercase()) {
                    "ms" -> value / 1000.0
                    "m" -> value * 60.0
                    else -> value
                }
            }
            return kotlin.math.ceil(seconds).toInt()
        }
        return null
    }

    fun classify(httpStatus: Int?, body: String?): ErrorClass {
        val text = body.orEmpty()
        if (httpStatus == null) {
            if (text == "missing_key") return ErrorClass.AUTH_OR_REGION
            return ErrorClass.NETWORK_ERROR
        }
        if (httpStatus == 403 && text.contains("1010")) {
            return ErrorClass.BLOCKED_BY_CLOUDFLARE
        }
        if (httpStatus == 401 || httpStatus == 403) {
            val lower = text.lowercase()
            if (lower.contains("api key not valid") || lower.contains("api_key_invalid")) {
                return ErrorClass.AUTH_OR_REGION
            }
            return ErrorClass.AUTH_OR_REGION
        }
        if (httpStatus == 429) {
            return if (DAILY_RE.containsMatchIn(text)) ErrorClass.RATE_LIMITED_DAILY else ErrorClass.RATE_LIMITED
        }
        if (httpStatus == 503) {
            return ErrorClass.OVERLOADED
        }
        if (httpStatus == 500 || httpStatus == 502 || httpStatus == 504) {
            return ErrorClass.SERVER_ERROR
        }
        if (httpStatus == 400) {
            val lower = text.lowercase()
            if (lower.contains("api key not valid") || lower.contains("api_key_invalid")) {
                return ErrorClass.AUTH_OR_REGION
            }
            return ErrorClass.BAD_REQUEST
        }
        return ErrorClass.OTHER
    }
}
