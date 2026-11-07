package com.jarvis.tools

import android.app.KeyguardManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import com.jarvis.screencontrol.ScreenController

/** Как найти трек на экране поиска SoundCloud. */
internal object SoundCloudScreen {
    private val DURATION = Regex("(^|\\s)\\d{1,2}:\\d{2}(:\\d{2})?$")

    data class Row(val text: String, val top: Int, val bottom: Int)

    /** Длительность есть только у треков: профили и плейлисты выше по списку не подходят. */
    fun firstTrack(rows: List<Row>): Row? =
        rows.filter { isTrack(it) }.minByOrNull { it.top }

    /**
     * Трек, больше всего похожий на запрос. Название лежит отдельной строкой над длительностью,
     * поэтому у каждого трека читаем строки рядом. Ремикс и кавер берём, только если их просили.
     * Ничего не совпало: первый трек, как решил поиск SoundCloud.
     */
    fun bestTrack(rows: List<Row>, query: String): Row? {
        val tracks = rows.filter { isTrack(it) }.sortedBy { it.top }
        val wanted = words(query)
        if (wanted.isEmpty()) return tracks.firstOrNull()
        val variantAsked = VARIANTS.any { it in wanted }
        var previousBottom = Int.MIN_VALUE
        val scored = tracks.map { track ->
            val text = rows
                .filter { it.bottom <= track.bottom && it.top >= previousBottom && it.top >= track.top - TITLE_REACH_PX }
                .joinToString(" ") { it.text }
            previousBottom = track.bottom
            val found = words(text)
            val matched = wanted.count { it in found }.toDouble() / wanted.size
            track to if (!variantAsked && VARIANTS.any { it in found }) matched - VARIANT_PENALTY else matched
        }
        val best = scored.maxByOrNull { it.second } ?: return null
        return if (best.second > 0.0) best.first else tracks.first()
    }

    private fun isTrack(row: Row) = DURATION.containsMatchIn(row.text.trim())

    private fun words(text: String): Set<String> =
        text.lowercase().replace('ё', 'е').split(NOT_WORD).filter { it.isNotBlank() }.toSet()

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val VARIANTS = setOf(
        "remix", "rmx", "cover", "nightcore", "slowed", "reverb", "sped", "karaoke", "instrumental",
        "ремикс", "кавер", "караоке", "минус",
    )
    private const val TITLE_REACH_PX = 220
    private const val VARIANT_PENALTY = 0.5
}

internal enum class PlayerResult { PLAYING, LOCKED, NO_ACCESS, FAILED }

/** Запасной путь: открыть штатный поиск и нажать на подходящий трек, как человек. */
internal class SoundCloudDriver(
    private val context: Context,
    private val started: () -> Boolean,
) {
    fun playSearch(query: String): PlayerResult {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) return PlayerResult.LOCKED
        if (!ScreenController.isAvailable()) return PlayerResult.NO_ACCESS
        val before = ScreenController.activePackageName()
        context.startActivity(
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                setPackage(PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        val row = waitForTrack(query)
        if (row == null) {
            Log.w(TAG, "SoundCloud: нет трека в результатах")
            return PlayerResult.FAILED
        }
        val width = context.resources.displayMetrics.widthPixels
        ScreenController.tap(width / 2f, (row.top + row.bottom) / 2f)
        if (!waitUntil(PLAY_WAIT_MS, started)) return PlayerResult.FAILED
        leave(before)
        return PlayerResult.PLAYING
    }

    private fun waitForTrack(query: String): SoundCloudScreen.Row? {
        val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ScreenController.activePackageName() == PACKAGE) {
                val rows = ScreenController.visibleNodes().map {
                    SoundCloudScreen.Row(it.text, it.bounds.top, it.bounds.bottom)
                }
                SoundCloudScreen.bestTrack(rows, query)?.let { return it }
            }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    /** Возврат туда, где был пользователь. Первое «назад» может лишь закрыть клавиатуру. */
    private fun leave(before: String?) {
        if (before == null || before == PACKAGE) return
        repeat(MAX_BACKS) {
            if (ScreenController.activePackageName() != PACKAGE) return
            ScreenController.globalAction("back")
            Thread.sleep(BACK_PAUSE_MS)
        }
    }

    private fun waitUntil(timeoutMs: Long, done: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (done()) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    companion object {
        const val PACKAGE = "com.soundcloud.android"
        const val SERVICE = "com.soundcloud.android.playback.players.MediaService"
        private const val TAG = "Jarvis.Music"
        private const val SCREEN_WAIT_MS = 8_000L
        private const val PLAY_WAIT_MS = 7_000L
        private const val POLL_MS = 250L
        private const val MAX_BACKS = 3
        private const val BACK_PAUSE_MS = 350L
    }
}
