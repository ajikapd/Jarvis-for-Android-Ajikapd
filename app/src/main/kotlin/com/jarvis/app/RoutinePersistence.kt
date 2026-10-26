package com.jarvis.app

import android.content.Context
import com.jarvis.core.planner.ActionPlan
import com.jarvis.core.planner.ActionPlanStatus
import com.jarvis.core.routines.RoutineStatus
import com.jarvis.core.routines.SavedRoutine
import org.json.JSONArray
import org.json.JSONObject

object RoutinePersistence {
    private const val PREF = "jarvis_routines_v1"
    private const val KEY = "items"
    private const val SEEDED = "seeded"

    fun load(context: Context): List<SavedRoutine> {
        val raw = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val status = runCatching { RoutineStatus.valueOf(o.getString("status")) }
                        .getOrDefault(RoutineStatus.SAVED)
                    if (status != RoutineStatus.SAVED && status != RoutineStatus.DISABLED) continue
                    add(stub(o.getString("id"), o.getString("title"), o.getString("nl"), status))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, routines: List<SavedRoutine>) {
        val arr = JSONArray()
        routines.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("title", r.title)
                    .put("nl", r.naturalLanguage)
                    .put("status", r.status.name),
            )
        }
        context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).putBoolean(SEEDED, true).apply()
    }

    fun seeded(context: Context): Boolean =
        context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE).getBoolean(SEEDED, false)

    fun markSeeded(context: Context) {
        context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(SEEDED, true).apply()
    }

    fun stub(id: String, title: String, nl: String, status: RoutineStatus): SavedRoutine =
        SavedRoutine(
            id = id,
            title = title.take(120).ifBlank { "Протокол" },
            naturalLanguage = nl.take(2000),
            plan = ActionPlan(
                planId = "plan-$id",
                goalId = "goal-$id",
                status = ActionPlanStatus.READY,
                steps = emptyList(),
                notes = listOf("persisted"),
            ),
            status = status,
            createdAtMillis = System.currentTimeMillis(),
        )
}
