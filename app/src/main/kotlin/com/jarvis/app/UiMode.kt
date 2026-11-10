package com.jarvis.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.core.startup.StartupRoute
import com.jarvis.core.startup.StartupRoutes
import com.jarvis.core.startup.UiStyle

/** Выбор между современным и классическим интерфейсом и порядок первого запуска. */
object UiMode {
    private const val KEY = "ui_mode"

    /** Открыть современный экран, даже если выбран классический (например, запись голоса из гида). */
    const val EXTRA_FORCE_MODERN = "force_modern"

    /** Открыть раздел «Ещё» по названию. С [EXTRA_FORCE_MODERN] «Назад» закрывает окно. */
    const val EXTRA_OPEN_SECTION = "open_section"

    fun style(context: Context): UiStyle? =
        UiStyle.of(JarvisSettings.prefs(context).getString(KEY, null))

    fun isClassic(context: Context): Boolean = style(context) == UiStyle.CLASSIC

    fun isPremium(context: Context): Boolean = style(context) == UiStyle.PREMIUM

    fun set(context: Context, style: UiStyle) {
        JarvisSettings.prefs(context).edit().putString(KEY, style.id).apply()
        com.jarvis.app.ui.Fonts.premium = style == UiStyle.PREMIUM
        // Виджет всегда в стиле выбранного интерфейса.
        JarvisWidgetProvider.refresh(context)
        AppIcon.apply(context, style)
    }

    fun route(activity: AppCompatActivity): StartupRoute = StartupRoutes.decide(
        onboardingDone = !OnboardingActivity.needsOnboarding(activity),
        style = style(activity),
        guideDone = !UsageGuideActivity.needsGuide(activity),
    )

    /** Окно для шага первого запуска; null — шаг уже пройден, остаёмся в интерфейсе. */
    fun stepIntent(context: Context, route: StartupRoute): Intent? = when (route) {
        StartupRoute.ONBOARDING -> Intent(context, OnboardingActivity::class.java)
        StartupRoute.CHOOSE_UI -> Intent(context, InterfaceChoiceActivity::class.java)
        StartupRoute.GUIDE -> Intent(context, UsageGuideActivity::class.java)
        StartupRoute.MODERN, StartupRoute.CLASSIC -> null
    }

    fun homeIntent(context: Context): Intent = Intent(
        context,
        if (isClassic(context)) DashboardActivity::class.java else com.jarvis.app.ui.JarvisActivity::class.java,
    )

    /** Следующий шаг после разрешений, выбора или гида. */
    fun continueFrom(activity: AppCompatActivity) {
        val next = stepIntent(activity, route(activity)) ?: homeIntent(activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(next)
        activity.finish()
    }

    /** Переключение интерфейса из настроек: старое окно закрывается, новое открывается с нуля. */
    fun switchTo(activity: Activity, style: UiStyle) {
        set(activity, style)
        activity.startActivity(homeIntent(activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        activity.finish()
    }
}
