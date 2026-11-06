package com.jarvis.tools

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.jarvis.screencontrol.ScreenController

/**
 * Без интернета играет Яндекс Музыка: у неё есть скачанное. Внешнего запуска у неё нет,
 * поэтому приложение открывается и нажимается «Слушать» в мини-плеере.
 */
internal class YandexOfflineDriver(
    private val context: Context,
    private val started: () -> Boolean,
) {
    fun play(): PlayerResult {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) return PlayerResult.LOCKED
        if (!ScreenController.isAvailable()) return PlayerResult.NO_ACCESS
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return PlayerResult.FAILED
        context.startActivity(launch)
        val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val button = ScreenController.visibleNodes().firstOrNull { it.contentDescription == PLAY_LABEL }
            if (button != null) {
                ScreenController.tapCenter(button)
                break
            }
            Thread.sleep(POLL_MS)
        }
        val until = System.currentTimeMillis() + PLAY_WAIT_MS
        while (System.currentTimeMillis() < until) {
            if (started()) return PlayerResult.PLAYING
            Thread.sleep(POLL_MS)
        }
        return PlayerResult.FAILED
    }

    companion object {
        const val PACKAGE = "ru.yandex.music"
        private const val PLAY_LABEL = "Слушать"
        private const val SCREEN_WAIT_MS = 8_000L
        private const val PLAY_WAIT_MS = 4_000L
        private const val POLL_MS = 300L
    }
}
