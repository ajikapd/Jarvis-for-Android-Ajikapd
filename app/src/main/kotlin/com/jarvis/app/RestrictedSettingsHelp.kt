package com.jarvis.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Android 13+ для приложений не из магазина прячет специальные возможности и доступ
 * к уведомлениям за окном «Настройки с ограниченным доступом». Снять его можно только вручную:
 * «О приложении» → ⋮ → «Разрешить ограниченные настройки». Объясняем это до перехода.
 */
object RestrictedSettingsHelp {
    const val TEXT =
        "Если Android покажет «Настройки с ограниченным доступом», откройте «О приложении» Jarvis, " +
            "нажмите ⋮ вверху справа → «Разрешить ограниченные настройки» и вернитесь сюда. " +
            "На некоторых телефонах этот пункт появляется только после первой попытки включить доступ."

    fun thenOpen(activity: Activity, open: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            open()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Если доступ заблокирован")
            .setMessage(TEXT)
            .setPositiveButton("Продолжить") { _, _ -> open() }
            .setNeutralButton("О приложении") { _, _ -> openAppInfo(activity) }
            .show()
    }

    fun openAppInfo(activity: Activity) {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
