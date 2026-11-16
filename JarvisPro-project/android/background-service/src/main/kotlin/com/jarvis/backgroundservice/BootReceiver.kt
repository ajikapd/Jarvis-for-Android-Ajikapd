package com.jarvis.backgroundservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Запускает JarvisService при загрузке устройства.
 *
 * В HyperOS обычно BOOT_COMPLETED приходит с задержкой или не приходит,
 * если приложение не добавлено в «Автозапуск». Онбординг-экран
 * (permissions-onboarding) поможет пользователю это настроить.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON"
            )
        ) {
            JarvisService.start(context)
        }
    }
}