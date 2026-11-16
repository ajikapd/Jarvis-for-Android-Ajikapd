package com.jarvis.core.nlp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWindowTest {
    @Test fun afterModelAnswer() = assertTrue(ConversationWindow.keepListening("Ответ.", isChat = true, enabled = true))
    @Test fun afterQuestion() = assertTrue(ConversationWindow.keepListening("Когда, сэр? ", isChat = false, enabled = true))
    @Test fun commandEndsTheTalk() = assertFalse(ConversationWindow.keepListening("Фонарик включён, сэр.", isChat = false, enabled = true))
    @Test fun canBeTurnedOff() = assertFalse(ConversationWindow.keepListening("Ответ.", isChat = true, enabled = false))
}
