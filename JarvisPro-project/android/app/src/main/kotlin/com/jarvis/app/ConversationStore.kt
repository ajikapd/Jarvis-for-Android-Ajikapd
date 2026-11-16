package com.jarvis.app

import com.jarvis.core.nlp.AddressRhythm
import com.jarvis.core.storage.ConversationJournal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process assistant thread for UI 3.0 Ассистент tab.
 * Fed from DashboardActivity events — not a second voice owner.
 *
 * Параллельно с RAM-пузырями каждая реплика пишется в постоянную историю
 * ([ConversationJournal] → `conversation_messages`), причём только в masked-виде.
 * [Bubble.dbId] связывает пузырь со строкой БД, чтобы «избранное» = keep=1.
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
        /** id строки conversation_messages; null для системных/несохранённых. */
        val dbId: String? = null,
    ) {
        enum class Role { USER, AI, SYSTEM }
    }

    private val items = CopyOnWriteArrayList<Bubble>()
    private var seq = 0L

    fun pushUser(text: String) {
        if (text.isBlank()) return
        val clean = text.trim()
        items.add(
            Bubble(
                id = ++seq,
                role = Bubble.Role.USER,
                text = clean,
                atMs = System.currentTimeMillis(),
                dbId = ConversationJournal.appendUser(clean),
            ),
        )
        trim()
    }

    fun pushAi(text: String, badge: String? = null, plan: String? = null) {
        if (text.isBlank()) return
        val clean = AddressRhythm.strip(text.trim())
        items.add(
            Bubble(
                id = ++seq,
                role = Bubble.Role.AI,
                text = clean,
                atMs = System.currentTimeMillis(),
                badge = badge,
                plan = plan,
                dbId = ConversationJournal.appendAssistant(clean),
            ),
        )
        trim()
    }

    /** Поднимает историю из БД при старте процесса (только если RAM ещё пуст). */
    fun restore(entries: List<ConversationJournal.Entry>) {
        if (entries.isEmpty() || items.isNotEmpty()) return
        entries.forEach { e ->
            val role = if (e.role == "user") Bubble.Role.USER else Bubble.Role.AI
            items.add(
                Bubble(
                    id = ++seq,
                    role = role,
                    text = if (role == Bubble.Role.AI) AddressRhythm.strip(e.text) else e.text,
                    atMs = e.createdAt,
                    favorite = e.keep,
                    dbId = e.id,
                ),
            )
        }
        trim()
    }

    fun toggleFavorite(id: Long) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val old = items[idx]
            val next = !old.favorite
            items[idx] = old.copy(favorite = next)
            // «Избранное» = keep=1 в постоянной истории.
            ConversationJournal.setKeep(old.dbId, next)
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
