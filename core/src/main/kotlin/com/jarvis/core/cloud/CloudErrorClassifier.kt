package com.jarvis.core.cloud

enum class ErrorClass {
    DAILY_EXHAUSTED,
    RATE_LIMITED,
    RATE_LIMITED_DAILY,
    CIRCUIT_TRIGGER,
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
