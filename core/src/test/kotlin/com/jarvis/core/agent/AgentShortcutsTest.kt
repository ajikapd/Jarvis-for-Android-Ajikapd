package com.jarvis.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentShortcutsTest {
    private fun youtube(query: String) = AgentShortcut.YouTubeSearch(query)

    @Test fun youtubeSearch() {
        assertEquals(youtube("котиков"), AgentShortcuts.match("открой ютуб и найди видео про котиков"))
        assertEquals(youtube("людей икс"), AgentShortcuts.match("открой youtube и найди видео про людей икс"))
        assertEquals(youtube("домик в лесу"), AgentShortcuts.match("найди на ютубе домик в лесу"))
    }

    @Test fun plainYoutubeStaysWithAgent() {
        assertNull(AgentShortcuts.match("открой ютуб"))
        assertNull(AgentShortcuts.match("открой ютуб и подпишись на канал"))
    }

    @Test fun settingsSections() {
        assertEquals(AgentShortcut.Setting("hotspot"), AgentShortcuts.match("открой настройки и найди точку доступа"))
        assertEquals(AgentShortcut.Setting("bluetooth"), AgentShortcuts.match("открой настройки блютуз"))
        assertEquals(AgentShortcut.Setting("wifi"), AgentShortcuts.match("открой настройки вайфая"))
    }

    @Test fun mapsAndWeb() {
        assertEquals(AgentShortcut.MapSearch("аптеку"), AgentShortcuts.match("найди на карте аптеку"))
        assertEquals(AgentShortcut.MapSearch("кафе рядом"), AgentShortcuts.match("найди кафе рядом в яндекс картах"))
        assertEquals(AgentShortcut.WebSearch("рецепт борща"), AgentShortcuts.match("найди в интернете рецепт борща"))
    }

    @Test fun anotherActionStaysWithAgent() {
        assertNull(AgentShortcuts.match("открой настройки блютуз и выключи его"))
        assertNull(AgentShortcuts.match("открой телеграм и напиши привет"))
    }
    @Test fun hotspotLookupDoesNotChangeSettings() {
        assertEquals(AgentShortcut.Setting("hotspot"), AgentShortcuts.match("В настройках найди точку доступа."))
        assertEquals(AgentShortcut.Setting("hotspot"), AgentShortcuts.match("Найди точку доступа"))
        assertNull(AgentShortcuts.match("Открой настройки и включи точку доступа"))
        assertNull(AgentShortcuts.match("В настройках выключи Bluetooth"))
    }
}
