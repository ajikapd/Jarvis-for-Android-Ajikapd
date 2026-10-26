package com.jarvis.onboarding

import android.Manifest
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Утилиты для проверки и запроса разрешений/настроек, критичных
 * для фоновой работы на HyperOS 3.0 (Android 16).
 *
 * Онбординг-экран вызывает эти методы последовательно, а если
 * разрешение не выдано — открывает нужный экран System Settings
 * через explicit Intent.
 */
object PermissionsHelper {

    // ── Доступные runtime-разрешения ──────────────────────────────────

    fun isAudioRecordGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Android 13+: нужно для показа уведомления Foreground Service. */
    fun isPostNotificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun audioRecordPermission() = Manifest.permission.RECORD_AUDIO

    fun postNotificationsPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.POST_NOTIFICATIONS
        else null

    // ── Системные настройки (экраны) ─────────────────────────────────

    /** Draws overlay (SYSTEM_ALERT_WINDOW). */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }

    /** Battery optimization whitelisting. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Accessibility Service — ручная активация пользователем. */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val serviceName = "${context.packageName}/${serviceClass.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(serviceName, ignoreCase = true) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ── HyperOS specifics ─────────────────────────────────────────────

    /** HyperOS autostart screen (Xiaomi-only, may not exist on other ROMs). */
    fun openAutoStartSettings(context: Context) {
        try {
            val intent = Intent().apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetailSettings(context)
        }
    }

    /** Fallback: opens app detail settings screen. */
    fun openAppDetailSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Notification Listener Service. */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return flat.split(":").any { it.contains(context.packageName) }
    }

    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** PACKAGE_USAGE_STATS (специальный AppOps, не runtime). */
    fun isUsageAccessGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetailSettings(context)
        }
    }

    /** ROLE_ASSISTANT / цифровой помощник (VoiceInteractionService или ACTION_ASSIST). */
    fun isAssistantRoleHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    }

    fun isAssistantRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    }

    /** Открывает экран выбора голосового ввода / цифрового помощника. */
    fun openAssistantPickerSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // try next
            }
        }
        openAppDetailSettings(context)
    }

    /** WRITE_SETTINGS (яркость и др.). */
    fun canWriteSettings(context: Context): Boolean =
        Settings.System.canWrite(context)

    fun openWriteSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }

    fun contactsPermission() = Manifest.permission.READ_CONTACTS
    fun callPermission() = Manifest.permission.CALL_PHONE
    fun smsPermission() = Manifest.permission.SEND_SMS
    fun calendarPermission() = Manifest.permission.WRITE_CALENDAR

    fun isPermissionGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}