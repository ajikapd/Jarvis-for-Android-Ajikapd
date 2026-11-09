package com.jarvis.tools

import android.content.Context
import org.json.JSONObject

/**
 * Синонимы приложений, которые задаёт сам человек: «телега» → Telegram, «работа» → Slack.
 * Хранятся на телефоне: синоним → пакет приложения.
 */
class AppAliases(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    /** Пары (синоним, пакет), по алфавиту синонимов. */
    fun all(): List<Pair<String, String>> {
        val json = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())
        return json.keys().asSequence().map { it to json.optString(it) }.filter { it.second.isNotBlank() }
            .sortedBy { it.first }.toList()
    }

    fun add(alias: String, packageName: String) {
        val clean = alias.trim().lowercase()
        if (clean.isBlank() || packageName.isBlank()) return
        val json = JSONObject().apply { all().forEach { (a, p) -> put(a, p) } }
        json.put(clean, packageName)
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    fun remove(alias: String) {
        val json = JSONObject().apply { all().filter { it.first != alias }.forEach { (a, p) -> put(a, p) } }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    /** Пакет по сказанному синониму; совпадение должно быть почти точным. */
    fun resolve(query: String): String? = AppMatcher.best(query, all().map { it.second to it.first }, accept = ALIAS_ACCEPT)

    private companion object {
        const val KEY = "app_aliases"
        const val ALIAS_ACCEPT = 85
    }
}
