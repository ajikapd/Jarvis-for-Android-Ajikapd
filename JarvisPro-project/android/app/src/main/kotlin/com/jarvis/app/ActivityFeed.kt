package com.jarvis.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process activity feed for UI 3.0 Активность tab.
 * Fed from DashboardActivity event collection — not a second orchestrator.
 */
object ActivityFeed {
    data class Item(
        val id: Long,
        val title: String,
        val detail: String,
        val kind: Kind,
        val atMs: Long,
        var pendingConfirm: Boolean = false,
        var failed: Boolean = false,
    ) {
        enum class Kind { VOICE, TOOL, SYSTEM, CONFIRM, MEMORY }
    }

    private val items = CopyOnWriteArrayList<Item>()
    private var seq = 0L

    fun push(
        title: String,
        detail: String,
        kind: Item.Kind,
        pendingConfirm: Boolean = false,
        failed: Boolean = false,
    ) {
        val id = ++seq
        items.add(
            0,
            Item(
                id = id,
                title = title,
                detail = detail,
                kind = kind,
                atMs = System.currentTimeMillis(),
                pendingConfirm = pendingConfirm,
                failed = failed,
            ),
        )
        while (items.size > 80) items.removeAt(items.lastIndex)
    }

    fun all(): List<Item> = items.toList()

    fun filter(kind: Item.Kind?): List<Item> =
        if (kind == null) all() else items.filter { it.kind == kind }

    fun markResolved(id: Long, approved: Boolean) {
        items.find { it.id == id }?.let {
            it.pendingConfirm = false
            it.failed = !approved
            if (approved) {
                // mutate via replace
            }
        }
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val old = items[idx]
            items[idx] = old.copy(
                pendingConfirm = false,
                failed = !approved,
                title = if (approved) "✓ ${old.title}" else "✕ ${old.title}",
            )
        }
    }

    fun formatTime(atMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(atMs))

    fun clear() = items.clear()
}
