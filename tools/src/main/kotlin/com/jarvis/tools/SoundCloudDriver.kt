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
        rows.filter { DURATION.containsMatchIn(it.text.trim()) }.minByOrNull { it.top }
}

/** Запасной путь: открыть штатный поиск и нажать на первый трек, как человек. */
internal class SoundCloudDriver(
    private val context: Context,
    private val started: () -> Boolean,
) {
    enum class Result { PLAYING, LOCKED, NO_ACCESS, FAILED }

    fun playSearch(query: String): Result {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) return Result.LOCKED
        if (!ScreenController.isAvailable()) return Result.NO_ACCESS
        val before = ScreenController.activePackageName()
        context.startActivity(
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                setPackage(PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        val row = waitForTrack()
        if (row == null) {
            Log.w(TAG, "SoundCloud: нет трека в результатах")
            return Result.FAILED
        }
        val width = context.resources.displayMetrics.widthPixels
        ScreenController.tap(width / 2f, (row.top + row.bottom) / 2f)
        if (!waitUntil(PLAY_WAIT_MS, started)) return Result.FAILED
        leave(before)
        return Result.PLAYING
    }

    private fun waitForTrack(): SoundCloudScreen.Row? {
        val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ScreenController.activePackageName() == PACKAGE) {
                val rows = ScreenController.visibleNodes().map {
                    SoundCloudScreen.Row(it.text, it.bounds.top, it.bounds.bottom)
                }
                SoundCloudScreen.firstTrack(rows)?.let { return it }
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
