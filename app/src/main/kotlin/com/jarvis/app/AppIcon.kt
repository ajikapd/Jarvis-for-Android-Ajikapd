package com.jarvis.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.jarvis.core.startup.UiStyle

/**
 * Значок приложения следует за выбранным интерфейсом.
 * В манифесте три псевдонима главного окна с разными значками; включён ровно один.
 */
object AppIcon {
    private const val PACKAGE = "com.jarvis.app"

    private fun alias(style: UiStyle) = when (style) {
        UiStyle.PREMIUM -> "$PACKAGE.LauncherPremium"
        UiStyle.CLASSIC -> "$PACKAGE.LauncherClassic"
        UiStyle.MODERN -> "$PACKAGE.LauncherModern"
    }

    /** Включить значок нужного интерфейса и выключить остальные. Повторный вызов ничего не меняет. */
    fun apply(context: Context, style: UiStyle?) {
        val wanted = style ?: UiStyle.MODERN
        val pm = context.packageManager
        // Сначала включаем нужный: у приложения ни на миг не пропадает значок.
        val order = listOf(wanted) + UiStyle.entries.filter { it != wanted }
        order.forEach { s ->
            val component = ComponentName(context.packageName, alias(s))
            val state = if (s == wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            runCatching {
                if (pm.getComponentEnabledSetting(component) != state) {
                    pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                }
            }
        }
    }
}
