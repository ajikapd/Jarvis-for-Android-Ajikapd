package com.jarvis.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process assistant thread for UI 3.0 Ассистент tab.
 * Fed from DashboardActivity events — not a second voice owner.
 */
object ConversationStore {
    data class Bubble(
        val id: Long,
        val role: Role,
        val text: String,
        val atMs: Long,
        val badge: String? = null,
        val plan: String? = null,
        val favorite: Boolean = false,
    ) {
        enum class Role { USER, AI, SYSTEM }
    }

    private val items = CopyOnWriteArrayList<Bubble>()
    private var seq = 0L

    fun pushUser(text: String) {
        if (text.isBlank()) return
        items.add(
            Bubble(
                id = ++seq,
                role = Bubble.Role.USER,
                text = text.trim(),
                atMs = System.currentTimeMillis(),
            ),
        )
        trim()
    }

    fun pushAi(text: String, badge: String? = null, plan: String? = null) {
        if (text.isBlank()) return
        items.add(
            Bubble(
                id = ++seq,
                role = Bubble.Role.AI,
                text = text.trim(),
                atMs = System.currentTimeMillis(),
                badge = badge,
                plan = plan,
            ),
        )
        trim()
    }

    fun toggleFavorite(id: Long) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val old = items[idx]
            items[idx] = old.copy(favorite = !old.favorite)
        }
    }

    fun all(): List<Bubble> = items.toList()

    fun today(): List<Bubble> {
        val start = startOfDayMs()
        return items.filter { it.atMs >= start }
    }

    fun week(): List<Bubble> {
        val start = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        return items.filter { it.atMs >= start }
    }

    fun favorites(): List<Bubble> = items.filter { it.favorite }

    fun clear() = items.clear()

    fun formatTime(atMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(atMs))

    private fun startOfDayMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun trim() {
        while (items.size > 120) items.removeAt(0)
    }
}
