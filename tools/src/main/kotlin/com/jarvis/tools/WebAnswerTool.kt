package com.jarvis.tools

import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Ответ на вопрос о свежих событиях: модель с поиском, сначала Groq, затем Gemini. */
class WebAnswerTool(private val keyFor: (String) -> String?) : Tool {
    override val definition = ToolDefinition(
        id = "web.answer", name = "Ответ из сети", description = "Найти свежие сведения в интернете и ответить голосом",
        parameters = listOf(ToolParameter("query", "вопрос", ParameterType.STRING, required = true)),
        riskLevel = RiskLevel.SAFE, onlineRequirement = OnlineRequirement.ONLINE_ALLOWED,
    )

    override fun execute(call: ToolCall): ToolResult {
        val query = call.arguments["query"]?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "Что узнать, сэр?")
        val question = "$query\n\n$STYLE"
        val answer = GROQ_MODELS.firstNotNullOfOrNull { attempt { groq(it, question) } } ?: attempt { gemini(question) }
        return if (answer.isNullOrBlank()) {
            ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, "Не удалось найти ответ в сети, сэр.")
        } else {
            ToolResult(call.requestId, definition.id, ToolResultStatus.SUCCESS, answer.trim())
        }
    }

    /** Текст ошибки не логируем и не показываем: в нём может оказаться заголовок с ключом. */
    private fun attempt(block: () -> String?): String? = runCatching(block).getOrNull()?.takeIf { it.isNotBlank() }

    private fun groq(model: String, question: String): String? {
        val key = keyFor("groq") ?: return null
        val body = JSONObject().put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", question)))
        val json = post("https://api.groq.com/openai/v1/chat/completions", body, "Authorization" to "Bearer $key")
        return JSONObject(json).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    private fun gemini(question: String): String? {
        val key = keyFor("gemini") ?: return null
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", question)))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
        val json = post(url, body, "x-goog-api-key" to key)
        val parts = JSONObject(json).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
    }

    private fun post(url: String, body: JSONObject, auth: Pair<String, String>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_MS
            connection.readTimeout = READ_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty(auth.first, auth.second)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            check(connection.responseCode in 200..299)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_MS = 6_000
        const val READ_MS = 20_000
        const val GEMINI_MODEL = "gemini-3.5-flash-lite"
        val GROQ_MODELS = listOf("groq/compound-mini", "groq/compound")
        const val STYLE = "Найди в интернете и ответь по-русски для голоса: одно-два коротких предложения, " +
            "без ссылок и списков, обращение к пользователю «сэр»."
    }
}
