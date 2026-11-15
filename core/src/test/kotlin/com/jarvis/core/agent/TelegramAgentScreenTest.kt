package com.jarvis.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramAgentScreenTest {
    private val window = RawNode("", 0, 0, 1080, 2400, false, false)
    private val back = RawNode("Назад", 6, 111, 155, 265, true, false)
    private val header = RawNode("", 143, 0, 827, 265, true, false)
    private val title = RawNode("Избранное", 302, 144, 793, 234, false, false)
    private val search = RawNode("Поиск", 844, 111, 962, 265, true, false)
    private val editor = RawNode("Сообщение: Привет", 158, 2207, 801, 2326, true, true)

    @Test fun headerIsRecognizedEvenWhenAChildOfItIsSelected() {
        val nodes = listOf(window, back, header, title, search, editor)
        val chat = requireNotNull(TelegramAgentScreen.conversation(nodes))
        assertEquals("Избранное", chat.title)
        assertEquals(5, chat.editor)
        assertTrue(TelegramAgentScreen.isHeaderTarget(nodes, chat, 2))
        assertTrue(TelegramAgentScreen.isHeaderTarget(nodes, chat, 3))
        assertFalse(TelegramAgentScreen.isHeaderTarget(nodes, chat, 4))
        assertFalse(TelegramAgentScreen.isHeaderTarget(nodes, chat, 5))
    }

    @Test fun menuRowIsNotTheConversationHeader() {
        val row = RawNode("", 508, 419, 1058, 551, true, false)
        val label = title.copy(left = 558, top = 455, right = 904, bottom = 514)
        val nodes = listOf(window, row, label)
        assertNull(TelegramAgentScreen.conversation(nodes))
        assertEquals(1, TelegramAgentScreen.savedNavigation(nodes))
    }

    @Test fun attachmentsAndSearchAreNotTheConversation() {
        assertNull(TelegramAgentScreen.conversation(listOf(window, back, header, title)))
        assertNull(TelegramAgentScreen.conversation(listOf(window, back, header, title, editor.copy(text = "Поиск: Избранное"))))
    }

    @Test fun profilesRemainAvailableWhenExplicitlyRequested() {
        assertTrue(TelegramAgentScreen.protectsHeader("найди чат с Сашей и напиши привет"))
        assertTrue(TelegramAgentScreen.protectsHeader("напиши в чат «открой профиль автора»"))
        assertFalse(TelegramAgentScreen.protectsHeader("открой чат с Сашей и покажи профиль"))
        assertFalse(TelegramAgentScreen.protectsHeader("открой чат «Саша» и покажи профиль"))
        assertFalse(TelegramAgentScreen.protectsHeader("зайди в медиа чата"))
    }

    @Test fun draftRequiresSavedDestinationAndExplicitNoSend() {
        assertEquals("Тест 12", TelegramAgentScreen.draftText("открой Избранное и введи «Тест 12», но не отправляй"))
        assertEquals("Избранное", TelegramAgentScreen.draftText("введи в Избранном текст «Избранное» без отправки"))
        assertNull(TelegramAgentScreen.draftText("введи «Избранное» без отправки"))
        assertNull(TelegramAgentScreen.draftText("напиши в Избранном «Привет» и отправь"))
    }

    @Test fun namedChatLookupDoesNotConsumeACompoundAction() {
        assertEquals("Тестовый чат", TelegramAgentScreen.lookupTitle("открой телеграм и найди чат «Тестовый чат»"))
        assertNull(TelegramAgentScreen.lookupTitle("найди чат «Тестовый чат» и напиши привет"))
    }

    @Test fun completionNeedsExistingEvidenceOutsideInputFields() {
        val screen = listOf(ScreenItem("Сообщение: Черновик", true), ScreenItem("Отправлено", false))
        assertEquals(setOf(1), AppAgentRules.completionEvidence("ДА: 1", screen))
        assertTrue(AppAgentRules.completionEvidence("ДА", screen).isEmpty())
        assertTrue(AppAgentRules.completionEvidence("ДА: 0, 1", screen).isEmpty())
        assertTrue(AppAgentRules.completionEvidence("ДА: 99", screen).isEmpty())
    }
}
