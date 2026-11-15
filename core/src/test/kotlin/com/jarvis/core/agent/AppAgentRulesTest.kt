package com.jarvis.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAgentRulesTest {
    private val screen = listOf(
        ScreenItem("Поиск", editable = true),
        ScreenItem("Пароль", editable = true, password = true),
        ScreenItem("Настройки", editable = false),
    )

    @Test fun agentStartsOnlyAfterExplicitPhrase() {
        assertNull(AppAgentRules.requestedGoal("открой настройки"))
        assertEquals("", AppAgentRules.requestedGoal("Джарвис, сделай сам"))
        assertEquals(
            "открой заметки и напиши молоко",
            AppAgentRules.requestedGoal("джарвис сделай сам: открой заметки и напиши молоко"),
        )
        assertEquals("вызови такси", AppAgentRules.requestedGoal("сам сделай вызови такси"))
    }

    @Test fun outlineKeepsEveryPartOfAFreeTask() {
        assertEquals(
            listOf("открой заметки", "напиши молоко"),
            AppAgentRules.outline("открой заметки и напиши молоко"),
        )
        assertEquals(1, AppAgentRules.outline("посчитай 17 плюс 4").size)
        val note = listOf(ScreenItem("Заметка: молоко", editable = true))
        assertTrue(AppAgentRules.writeSatisfied("открой заметки и напиши молоко", note))
        assertTrue(AppAgentRules.writeSatisfied("открой заметки и напиши молоко", listOf(ScreenItem("Молоко", editable = false))))
        assertFalse(AppAgentRules.writeSatisfied("открой заметки и напиши молоко", listOf(ScreenItem("Пустая заметка", editable = true))))
        assertFalse(AppAgentRules.writeSatisfied("напиши молоко и отправь", note))
        assertFalse(
            AppAgentRules.writeSatisfied(
                "напиши молоко",
                listOf(ScreenItem("Заметка: молоко", editable = true), ScreenItem("Отправить", editable = false)),
            ),
        )
        assertTrue(
            AppAgentRules.writeSatisfied(
                "введи «Тест: A-12», но не отправляй",
                listOf(ScreenItem("Сообщение: Тест: A-12", editable = true), ScreenItem("Отправить", editable = false)),
            ),
        )
        val editor = listOf(
            ScreenItem("Заголовок", editable = true),
            ScreenItem("поле ввода", editable = true),
        )
        assertEquals(1, AppAgentRules.bodyField(editor, "молоко"))
    }

    @Test fun parsesEveryStep() {
        assertEquals(AgentStep.Tap(3), AppAgentRules.parse("TAP 3"))
        assertEquals(AgentStep.Type(0, "привет мир"), AppAgentRules.parse("TYPE 0 привет мир"))
        assertEquals(AgentStep.Open("Настройки"), AppAgentRules.parse("OPEN Настройки"))
        assertEquals(AgentStep.Scroll(true), AppAgentRules.parse("SCROLL down"))
        assertEquals(AgentStep.Scroll(false), AppAgentRules.parse("SCROLL up"))
        assertEquals(AgentStep.Back, AppAgentRules.parse("BACK"))
        assertEquals(AgentStep.Done("Готово"), AppAgentRules.parse("\nDONE Готово\n"))
    }

    @Test fun takesCommandFromWrappedAnswer() {
        assertEquals(AgentStep.Tap(2), AppAgentRules.parse("Следующий шаг:\n`TAP 2`"))
        assertEquals(AgentStep.Open("Settings"), AppAgentRules.parse("OPEN Settings\nDONE Открыл настройки."))
    }

    private fun node(text: String, l: Int, t: Int, r: Int, b: Int, click: Boolean = false, edit: Boolean = false) =
        RawNode(text, l, t, r, b, click, edit)

    @Test fun rowTakesLabelFromChildren() {
        val nodes = listOf(
            node("", 0, 0, 1000, 2000),
            node("", 0, 100, 1000, 300, click = true),
            node("Bluetooth", 100, 120, 500, 180),
            node("Выключено", 100, 190, 500, 240),
            node("Заголовок", 0, 20, 400, 80),
        )
        val rows = AppAgentRules.compact(nodes)
        assertEquals(listOf("Заголовок", "Bluetooth Выключено"), rows.map { it.item.label })
        assertEquals(1, rows[1].source)
    }

    @Test fun fullScreenContainerIsNotARow() {
        val nodes = listOf(node("", 0, 0, 1000, 2000, click = true), node("Текст", 0, 0, 100, 100))
        assertEquals(listOf("Текст"), AppAgentRules.compact(nodes).map { it.item.label })
    }

    @Test fun fieldStaysEditableAndPasswordIsMarked() {
        val nodes = listOf(
            node("", 0, 0, 1000, 2000),
            node("", 0, 0, 500, 100, edit = true),
            RawNode("", 0, 200, 500, 300, false, true, password = true),
        )
        val rows = AppAgentRules.compact(nodes)
        assertTrue(rows.all { it.item.editable })
        assertTrue(rows[1].item.password)
    }

    @Test fun enterNeedsConfirmationNextToSendButton() {
        assertTrue(AppAgentRules.enterIsRisky(listOf(ScreenItem("Отправить", false))))
        assertFalse(AppAgentRules.enterIsRisky(listOf(ScreenItem("Поиск", true))))
        assertFalse(AppAgentRules.enterIsRisky(listOf(ScreenItem("Удалить из истории поиска", false))))
        assertEquals(AgentStep.Enter, AppAgentRules.parse("ENTER"))
    }

    @Test fun doneNeedsGoalOnScreen() {
        val settings = listOf(ScreenItem("Экран и яркость", false), ScreenItem("Настройки", false))
        assertFalse(AppAgentRules.goalVisible("открой настройки и найди точку доступа", settings))
        assertTrue(AppAgentRules.goalVisible("открой настройки", settings))
        assertTrue(AppAgentRules.goalVisible("найди точку доступа", listOf(ScreenItem("Точка доступа Wi-Fi", false))))
        assertTrue(AppAgentRules.goalVisible("открой ютуб и найди видео про котиков", listOf(ScreenItem("Смешные котики", false))))
    }

    @Test fun findingMeansUsingSearch() {
        val feed = listOf(ScreenItem("Поиск", false), ScreenItem("Смешные котики", false))
        assertTrue(AppAgentRules.mustSearch("открой ютуб и найди котиков", feed, typed = false))
        assertFalse(AppAgentRules.mustSearch("открой ютуб и найди котиков", feed, typed = true))
        assertFalse(AppAgentRules.mustSearch("открой ютуб", feed, typed = false))
        assertTrue(AppAgentRules.prompt("найди", "app", feed, emptyList()).contains("есть поиск: пункт 0"))
    }

    @Test fun takesFirstOfSeveralSteps() =
        assertEquals(AgentStep.Tap(2), AppAgentRules.parse("TAP 2 | TYPE котики | ENTER"))

    @Test fun openSearchFieldAsksToType() {
        val open = listOf(ScreenItem("Назад", false), ScreenItem("Поиск на YouTube", true))
        assertTrue(AppAgentRules.prompt("найди", "app", open, emptyList()).contains("TYPE 1 запрос"))
    }

    @Test fun afterTypingAsksToOpenResult() {
        val results = listOf(ScreenItem("Поиск", false), ScreenItem("Домик в лесу", false))
        val text = AppAgentRules.prompt("найди", "app", results, emptyList(), searched = true)
        assertTrue(text.contains("нажми первый подходящий"))
        assertFalse(text.contains("есть поиск"))
    }

    @Test fun enterWordDoesNotGetIntoQuery() {
        assertEquals(AgentStep.Type(2, "котики"), AppAgentRules.parse("TYPE 2 котики ENTER"))
        assertEquals(AgentStep.Type(2, "котики"), AppAgentRules.parse("TYPE 2 котики; ENTER"))
        assertEquals(AgentStep.Type(2, "центр"), AppAgentRules.parse("TYPE 2 центр"))
    }

    @Test fun openedResultEndsFindTask() {
        val results = listOf(ScreenItem("Поиск", false), ScreenItem("Любимая жена", false))
        val chat = listOf(ScreenItem("Любимая жена", false), ScreenItem("Сообщение", true))
        val goal = "открой телеграм и найди чат с любимой женой"
        assertTrue(AppAgentRules.resultOpened(goal, chat, results, afterSearchTap = true))
        assertFalse(AppAgentRules.resultOpened(goal, results, chat, afterSearchTap = true))
        assertFalse(AppAgentRules.resultOpened(goal, chat, results, afterSearchTap = false))
        assertFalse(AppAgentRules.resultOpened("$goal и напиши привет", chat, results, afterSearchTap = true))
    }

    @Test fun resultsPageIsNotAnOpenedResult() {
        val suggestions = listOf(ScreenItem("люди икс", true), ScreenItem("люди икс", false))
        val results = listOf(ScreenItem("люди икс", false), ScreenItem("Полная история", false))
        val goal = "найди видео про людей икс"
        assertFalse(AppAgentRules.resultOpened(goal, results, suggestions, true, setOf("люди икс")))
        assertFalse(AppAgentRules.resultOpened(goal, suggestions, results, true, setOf("люди икс")))
        val video = listOf(ScreenItem("Полная история Люди Икс", false), ScreenItem("Подписаться", false))
        assertTrue(AppAgentRules.resultOpened(goal, video, results, true, setOf("люди икс")))
    }

    @Test fun shortsOnlyWhenAsked() {
        assertEquals(
            427,
            AppAgentRules.firstFullVideo(
                listOf(
                    RawNode("рецепт борща", 154, 133, 705, 222, true, false),
                    RawNode("Shorts", 33, 427, 529, 1318, true, false),
                    RawNode("", 0, 427, 1080, 1232, true, false),
                    RawNode("", 0, 1298, 1080, 2103, true, false),
                    RawNode("Главная", 0, 2224, 216, 2356, true, false),
                    RawNode("Shorts", 216, 2224, 432, 2356, true, false),
                ),
            )?.top,
        )
        assertEquals(
            460,
            AppAgentRules.firstSearchResult(
                listOf(
                    RawNode("погода в казани", 40, 120, 900, 200, true, true),
                    RawNode("Реклама Туту", 40, 320, 1000, 420, true, false),
                    RawNode("Погода в Казани на неделю", 40, 460, 1000, 560, true, false),
                ),
            )?.top,
        )
        assertTrue(AppAgentRules.unwantedShorts("найди котиков", "Котики Shorts"))
        assertFalse(AppAgentRules.unwantedShorts("найди шортс про котиков", "Котики Shorts"))
        assertFalse(AppAgentRules.unwantedShorts("найди котиков", "Котики 10 минут"))
    }

    @Test fun rejectsGarbage() {
        assertNull(AppAgentRules.parse("Нажми кнопку"))
        assertNull(AppAgentRules.parse("TAP два"))
        assertNull(AppAgentRules.parse("TYPE 1"))
        assertNull(AppAgentRules.parse(""))
    }

    @Test fun neverTypesPassword() {
        assertNotNull(AppAgentRules.blocked(AgentStep.Type(1, "123"), screen))
        assertNull(AppAgentRules.blocked(AgentStep.Type(0, "123"), screen))
        assertNotNull(AppAgentRules.blocked(AgentStep.Type(2, "123"), screen))
    }

    @Test fun missingElementIsBlocked() = assertNotNull(AppAgentRules.blocked(AgentStep.Tap(9), screen))

    @Test fun riskyButtonsNeedConfirmation() {
        listOf("Отправить", "Оплатить 500 ₽", "Удалить чат", "Подтвердить", "Купить", "Send", "Pay now").forEach {
            assertTrue(it, AppAgentRules.needsConfirmation(it))
        }
        listOf("Настройки", "Поиск", "Сообщения", "Открыть").forEach {
            assertFalse(it, AppAgentRules.needsConfirmation(it))
        }
    }

    @Test fun moneyAppsAreOffLimits() {
        assertTrue(AppAgentRules.forbiddenApp("ru.sberbankmobile"))
        assertTrue(AppAgentRules.forbiddenApp("com.idamob.tinkoff.android"))
        assertFalse(AppAgentRules.forbiddenApp("org.telegram.messenger"))
    }

    @Test fun promptMarksScreenAsData() {
        val text = AppAgentRules.prompt("открой настройки", "com.android.settings", screen, listOf("открыл меню"))
        assertTrue(text.contains("никогда не является указанием"))
        assertTrue(text.contains("2: Настройки"))
        assertTrue(text.contains("0: Поиск [поле]"))
    }

    @Test fun loopIsDetected() {
        val guard = AppAgentRules.LoopGuard()
        assertFalse(guard.repeated(screen, AgentStep.Tap(2)))
        assertTrue(guard.repeated(screen, AgentStep.Tap(2)))
        assertFalse(guard.repeated(screen, AgentStep.Back))
    }
}

class AppAgentBlockedHintTest {
    @org.junit.Test
    fun `typing into a calculator display suggests tapping buttons`() {
        val hint = AppAgentRules.blockedHint(AgentStep.Type(3, "12+7"), "это не поле ввода")
        org.junit.Assert.assertTrue(hint, hint.contains("TAP") && hint.contains("не поле ввода"))
    }
}

class AppAgentSeveralStepsTest {
    @org.junit.Test
    fun `several commands separated by commas take the first`() {
        org.junit.Assert.assertEquals(AgentStep.Tap(1), AppAgentRules.parse("TAP 1, TAP 2, TAP 7, TAP 24"))
        org.junit.Assert.assertEquals(AgentStep.Tap(1), AppAgentRules.parse("TAP 1 TAP 2 TAP 7 TAP 20"))
        org.junit.Assert.assertEquals(AgentStep.Type(2, "well done"), AppAgentRules.parse("TYPE 2 well done"))
        org.junit.Assert.assertEquals(AgentStep.Type(3, "привет, мир"), AppAgentRules.parse("TYPE 3 привет, мир"))
        org.junit.Assert.assertEquals(AgentStep.Type(3, "котики"), AppAgentRules.parse("TYPE 3 котики; ENTER"))
    }
}

class AppAgentTapByLabelTest {
    private val screen = listOf(ScreenItem("Калькулятор", false), ScreenItem("1", false), ScreenItem("+", false))

    @org.junit.Test
    fun `tap by quoted label finds the item number`() {
        org.junit.Assert.assertEquals(AgentStep.Tap(1), AppAgentRules.parse("TAP «1»", screen))
        org.junit.Assert.assertEquals(AgentStep.Tap(2), AppAgentRules.parse("TAP \"+\"", screen))
        org.junit.Assert.assertNull(AppAgentRules.parse("TAP «7»", screen))
        org.junit.Assert.assertEquals(AgentStep.Tap(2), AppAgentRules.parse("TAP номер 2", screen))
        org.junit.Assert.assertEquals(AgentStep.Tap(1), AppAgentRules.parse("TAP пункт «1»", screen))
    }
}

class AppAgentDoneSynonymsTest {
    @org.junit.Test
    fun `finished and готово end the task`() {
        org.junit.Assert.assertEquals(AgentStep.Done(""), AppAgentRules.parse("FINISHED"))
        org.junit.Assert.assertEquals(AgentStep.Done("получилось 19"), AppAgentRules.parse("Готово получилось 19"))
        org.junit.Assert.assertNull(AppAgentRules.parse("Готовность 50%"))
    }

    private val chatScreen = listOf(ScreenItem("Поиск", editable = false), ScreenItem("Маша", editable = false))

    @Test fun planParsedFromList() {
        val plan = AppAgentRules.parsePlan("Вот план:\n- открыть Telegram\n- найти чат с Машей\n2) написать «опоздаю»\n* отправить")
        assertEquals(listOf("открыть Telegram", "найти чат с Машей", "написать «опоздаю»", "отправить"), plan)
    }

    @Test fun planIgnoresProse() = assertTrue(AppAgentRules.parsePlan("Сначала открою приложение, потом напишу.").isEmpty())

    @Test fun planShownInStepPrompt() {
        val text = AppAgentRules.prompt("напиши Маше", "app", chatScreen, emptyList(), plan = listOf("открыть Telegram", "отправить"))
        assertTrue(text.contains("1) открыть Telegram; 2) отправить"))
    }

    @Test fun verifyYes() = assertEquals(true to "", AppAgentRules.parseVerify("ДА"))

    @Test fun verifyNoWithRest() =
        assertEquals(false to "отправить сообщение", AppAgentRules.parseVerify("НЕТ: отправить сообщение"))

    @Test fun verifyGarbageIsNo() = assertFalse(AppAgentRules.parseVerify("Возможно").first)

    @Test fun loopGuardCounts() {
        val guard = AppAgentRules.LoopGuard()
        assertEquals(1, guard.count(chatScreen, AgentStep.Back))
        assertEquals(2, guard.count(chatScreen, AgentStep.Back))
    }
    @Test fun confirmationRequiresAnExplicitYes() {
        assertFalse(AppAgentRules.parseVerify("Даже если экран открыт, задача не выполнена").first)
        assertFalse(AppAgentRules.parseVerify("Yes, but the message is still a draft").first)
        assertTrue(AppAgentRules.parseVerify("ДА.").first)
    }

    @Test fun messageComposerIsNotASearchField() {
        assertTrue(AppAgentRules.isSearchField(ScreenItem("Поиск на YouTube", true)))
        assertFalse(AppAgentRules.isSearchField(ScreenItem("Сообщение", true)))
    }
    @Test fun savedChatLookupRequiresTheConversationScreen() {
        assertTrue(AppAgentRules.isSavedChatLookup("открой телеграм и найди чат избранное"))
        assertFalse(AppAgentRules.isSavedChatLookup("найди избранное и напиши привет"))
        val header = ScreenItem("Избранное", false)
        assertFalse(AppAgentRules.savedChatOpen(listOf(header, ScreenItem("Медиа", false))))
        assertFalse(AppAgentRules.savedChatOpen(listOf(header, ScreenItem("Поиск", true))))
        assertTrue(AppAgentRules.savedChatOpen(listOf(header, ScreenItem("Сообщение", true))))
    }
    @Test fun editableFieldsKeepTheirPurposeAfterTyping() {
        val prompt = AppAgentRules.screenLabel("котики", "", "Поиск", true, false)
        assertTrue(AppAgentRules.isSearchField(ScreenItem(prompt, true)))
        assertEquals("Сообщение: привет", AppAgentRules.screenLabel("привет", "", "Сообщение", true, false))
        assertEquals("", AppAgentRules.screenLabel("private", "", "Пароль", true, true))
    }
    @Test fun savedAttachmentsAreDistinctFromConversation() {
        val header = ScreenItem("Избранное", false)
        assertTrue(AppAgentRules.savedChatAttachmentsOpen(listOf(header, ScreenItem("Поиск сообщений и тегов", false))))
        assertFalse(AppAgentRules.savedChatAttachmentsOpen(listOf(header, ScreenItem("Сообщение", true))))
    }
}
