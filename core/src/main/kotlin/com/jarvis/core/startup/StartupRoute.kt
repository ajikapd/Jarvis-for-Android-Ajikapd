package com.jarvis.core.startup

/** Куда вести человека при открытии приложения. */
enum class StartupRoute { ONBOARDING, CHOOSE_UI, GUIDE, MODERN, CLASSIC }

/** Какой интерфейс выбран: современный или классический. */
enum class UiStyle(val id: String) {
    MODERN("modern"),
    CLASSIC("classic");

    companion object {
        fun of(id: String?): UiStyle? = entries.firstOrNull { it.id == id }
    }
}

object StartupRoutes {
    /**
     * Порядок первого запуска: разрешения, выбор интерфейса, гид, сам интерфейс.
     * Кто уже прошёл гид, но интерфейс не выбирал (старая установка), остаётся в современном без вопроса.
     */
    fun decide(onboardingDone: Boolean, style: UiStyle?, guideDone: Boolean): StartupRoute = when {
        !onboardingDone -> StartupRoute.ONBOARDING
        style == null && !guideDone -> StartupRoute.CHOOSE_UI
        !guideDone -> StartupRoute.GUIDE
        style == UiStyle.CLASSIC -> StartupRoute.CLASSIC
        else -> StartupRoute.MODERN
    }
}
