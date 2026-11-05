package com.jarvis.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jarvis.core.reminder.Reminder
import com.jarvis.core.reminder.ReminderBook
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/** Напоминания живут в настройках и будильнике системы, поэтому переживают смерть процесса. */
class AndroidReminderBook(context: Context) : ReminderBook {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("jarvis_reminders", Context.MODE_PRIVATE)
    private val alarms = ctx.getSystemService(AlarmManager::class.java)

    @Synchronized
    override fun add(at: LocalDateTime, text: String, daily: Boolean): Reminder {
        val id = prefs.getLong(KEY_NEXT, 1L)
        val reminder = Reminder(id, at, text, daily)
        store(all() + reminder, nextId = id + 1)
        arm(reminder)
        return reminder
    }

    @Synchronized
    override fun all(): List<Reminder> {
        val array = runCatching { JSONArray(prefs.getString(KEY_ITEMS, "[]")) }.getOrElse { JSONArray() }
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Reminder(o.getLong("id"), millisToTime(o.getLong("at")), o.optString("text"), o.optBoolean("daily"))
        }
    }

    @Synchronized
    override fun remove(id: Long): Boolean {
        val items = all()
        if (items.none { it.id == id }) return false
        alarms?.cancel(fireIntent(id))
        store(items.filter { it.id != id })
        return true
    }

    /** После срабатывания: разовое исчезает, ежедневное переносится на следующий раз. */
    @Synchronized
    fun fired(id: Long): Reminder? {
        val reminder = all().firstOrNull { it.id == id } ?: return null
        if (!reminder.daily) {
            remove(id)
            return reminder
        }
        var next = reminder.at
        val now = LocalDateTime.now()
        while (next <= now) next = next.plusDays(1)
        val moved = reminder.copy(at = next)
        store(all().map { if (it.id == id) moved else it })
        arm(moved)
        return reminder
    }

    /** Система забывает будильники при перезагрузке и принудительной остановке. */
    @Synchronized
    fun rearm() {
        val now = LocalDateTime.now()
        for (reminder in all()) {
            if (reminder.at > now) arm(reminder) else ctx.sendBroadcast(ReminderReceiver.intent(ctx, reminder.id))
        }
    }

    private fun arm(reminder: Reminder) {
        val manager = alarms ?: return
        val at = timeToMillis(reminder.at)
        val fire = fireIntent(reminder.id)
        try {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (exact) {
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp()), fire)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm denied: ${e.message}")
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }
    }

    private fun fireIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.toInt(), ReminderReceiver.intent(ctx, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, DashboardActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun store(items: List<Reminder>, nextId: Long? = null) {
        val array = JSONArray()
        items.forEach { r ->
            array.put(
                JSONObject().put("id", r.id).put("at", timeToMillis(r.at))
                    .put("text", r.text).put("daily", r.daily),
            )
        }
        val edit = prefs.edit().putString(KEY_ITEMS, array.toString())
        nextId?.let { edit.putLong(KEY_NEXT, it) }
        edit.apply()
    }

    private fun timeToMillis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun millisToTime(ms: Long) =
        java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()

    private companion object {
        const val TAG = "Jarvis.Reminders"
        const val KEY_ITEMS = "items"
        const val KEY_NEXT = "next_id"
    }
}
