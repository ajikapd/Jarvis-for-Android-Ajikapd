package com.jarvis.core.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupRoutesTest {
    private fun route(onboarding: Boolean, style: UiStyle?, guide: Boolean) = StartupRoutes.decide(onboarding, style, guide)

    @Test fun permissionsComeFirst() = assertEquals(StartupRoute.ONBOARDING, route(false, UiStyle.CLASSIC, true))

    @Test fun afterPermissionsChooseInterface() = assertEquals(StartupRoute.CHOOSE_UI, route(true, null, false))

    @Test fun guideComesAfterChoice() {
        assertEquals(StartupRoute.GUIDE, route(true, UiStyle.MODERN, false))
        assertEquals(StartupRoute.GUIDE, route(true, UiStyle.CLASSIC, false))
    }

    @Test fun chosenInterfaceOpens() {
        assertEquals(StartupRoute.MODERN, route(true, UiStyle.MODERN, true))
        assertEquals(StartupRoute.CLASSIC, route(true, UiStyle.CLASSIC, true))
    }

    @Test fun oldInstallWithoutChoiceStaysModern() = assertEquals(StartupRoute.MODERN, route(true, null, true))

    @Test fun styleIds() {
        assertEquals(UiStyle.CLASSIC, UiStyle.of("classic"))
        assertEquals(UiStyle.MODERN, UiStyle.of("modern"))
        assertNull(UiStyle.of(null))
        assertNull(UiStyle.of("retro"))
    }
}
