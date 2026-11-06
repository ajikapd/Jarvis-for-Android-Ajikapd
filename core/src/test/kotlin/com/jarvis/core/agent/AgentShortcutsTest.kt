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

    @Test fun anotherActionStaysWithAgent() {
        assertNull(AgentShortcuts.match("открой настройки блютуз и выключи его"))
        assertNull(AgentShortcuts.match("открой телеграм и напиши привет"))
    }
}
