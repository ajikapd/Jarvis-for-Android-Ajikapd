package com.jarvis.app

import android.content.Context
import com.jarvis.core.notes.CustomRoutines
import com.jarvis.core.notes.NoteBook
import com.jarvis.core.notes.PlaceBook
import com.jarvis.core.routines.EventRoutines
import com.jarvis.core.routines.EventRule
import com.jarvis.core.routines.EventTrigger
import org.json.JSONArray
import org.json.JSONObject

private fun Context.voicePrefs() = applicationContext.getSharedPreferences("jarvis_voice_data", Context.MODE_PRIVATE)

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

/** Списки голоса в настройках приложения. */
class PrefsNoteBook(private val context: Context) : NoteBook {
    @Synchronized
    override fun items(list: String): List<String> =
        runCatching { JSONArray(context.voicePrefs().getString("list_$list", "[]")).strings() }.getOrDefault(emptyList())

    @Synchronized
    override fun add(list: String, items: List<String>) = save(list, items(list) + items)

    @Synchronized
    override fun remove(list: String, item: String): Boolean {
        val all = items(list)
        val left = all.filterNot { it.contains(item) }
        save(list, left)
        return left.size != all.size
    }

    @Synchronized
    override fun clear(list: String) = save(list, emptyList())

    private fun save(list: String, items: List<String>) {
        context.voicePrefs().edit().putString("list_$list", JSONArray(items).toString()).apply()
    }
}

class PrefsPlaceBook(private val context: Context) : PlaceBook {
    override fun get(key: String): String? = context.voicePrefs().getString("place_$key", null)

    override fun set(key: String, address: String) {
        context.voicePrefs().edit().putString("place_$key", address).apply()
    }
}

/** Какие сценарии запускаются событиями. Шаги хранит [PrefsCustomRoutines]. */
class PrefsEventRoutines(private val context: Context) : EventRoutines {
    private fun load(): List<EventRule> =
        runCatching { JSONArray(context.voicePrefs().getString("event_rules", "[]")).strings() }
            .getOrDefault(emptyList())
            .mapNotNull { line ->
                val parts = line.split('|', limit = 2)
                val trigger = EventTrigger.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
                EventRule(trigger, parts.getOrElse(1) { "" })
            }

    private fun save(rules: List<EventRule>) {
        val lines = rules.map { "${it.trigger.name}|${it.arg}" }
        context.voicePrefs().edit().putString("event_rules", JSONArray(lines).toString()).apply()
    }

    @Synchronized
    override fun rules(): List<EventRule> = load()

    @Synchronized
    override fun put(rule: EventRule) = save(load() - rule + rule)

    @Synchronized
    override fun remove(rule: EventRule) = save(load() - rule)
}

class PrefsCustomRoutines(private val context: Context) : CustomRoutines {
    private fun all(): JSONObject =
        runCatching { JSONObject(context.voicePrefs().getString("routines", "{}").orEmpty()) }.getOrDefault(JSONObject())

    private fun save(value: JSONObject) {
        context.voicePrefs().edit().putString("routines", value.toString()).apply()
    }

    @Synchronized
    override fun steps(name: String): List<String>? = all().optJSONArray(name.lowercase().trim())?.strings()

    @Synchronized
    override fun put(name: String, steps: List<String>) = save(all().put(name.lowercase().trim(), JSONArray(steps)))

    @Synchronized
    override fun remove(name: String): Boolean {
        val value = all()
        val had = value.remove(name.lowercase().trim()) != null
        save(value)
        return had
    }

    @Synchronized
    override fun names(): List<String> = all().keys().asSequence().toList()
}
