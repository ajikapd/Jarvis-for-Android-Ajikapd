package com.jarvis.brain

import com.jarvis.brain.CustomRoutineParser.Request
import com.jarvis.core.routines.EventRule
import com.jarvis.core.routines.EventTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomRoutineParserTest {
    @Test fun whenISay() = assertEquals(
        Request.Define("поехали", listOf("включи блютуз", "включи музыку")),
        CustomRoutineParser.parse("когда я говорю поехали, включи блютуз и музыку"),
    )

    @Test fun headphones() = assertEquals(
        Request.DefineEvent(EventRule(EventTrigger.HEADPHONES_ON), listOf("включи музыку")),
        CustomRoutineParser.parse("когда подключаю наушники, включи музыку"),
    )

    @Test fun chargerOff() = assertEquals(
        Request.DefineEvent(EventRule(EventTrigger.CHARGER_OFF), listOf("сделай потише")),
        CustomRoutineParser.parse("когда снимаю с зарядки сделай потише"),
    )

    @Test fun chargerOn() = assertEquals(
        Request.DefineEvent(EventRule(EventTrigger.CHARGER_ON), listOf("выключи фонарик", "скажи погоду")),
        CustomRoutineParser.parse("как только поставлю на зарядку, то выключи фонарик и скажи погоду"),
    )

    @Test fun wifiLost() = assertEquals(
        Request.DefineEvent(EventRule(EventTrigger.WIFI_OFF), listOf("останови музыку")),
        CustomRoutineParser.parse("если отключается вайфай останови музыку"),
    )

    @Test fun appOpen() = assertEquals(
        Request.DefineEvent(EventRule(EventTrigger.APP_OPEN, "тик ток"), listOf("включи режим не беспокоить")),
        CustomRoutineParser.parse("когда открываю тик ток включи режим не беспокоить"),
    )

    @Test fun unknownConditionIsNotARule() = assertNull(CustomRoutineParser.parse("когда приду домой включи свет"))

    @Test fun twoVerbs() = assertEquals(
        Request.Define("вечер", listOf("выключи фонарик", "сделай потише")),
        CustomRoutineParser.parse("создай сценарий вечер выключи фонарик и сделай потише"),
    )

    @Test fun nameOfTwoWords() = assertEquals(
        Request.Define("пора работать", listOf("открой почту")),
        CustomRoutineParser.parse("если скажу пора работать то открой почту"),
    )

    @Test fun delete() = assertEquals(Request.Delete("вечер"), CustomRoutineParser.parse("удали сценарий вечер"))
    @Test fun names() = assertEquals(Request.Names, CustomRoutineParser.parse("какие у меня сценарии"))
    @Test fun noCommand() = assertNull(CustomRoutineParser.parse("когда говорю привет"))
    @Test fun unrelated() = assertNull(CustomRoutineParser.parse("включи музыку"))

    @Test fun createCommandByVoice() {
        assertEquals(
            CustomRoutineParser.Request.Define("поехали", listOf("включи блютуз", "включи музыку")),
            CustomRoutineParser.parse("Создай команду поехали: включи блютуз и музыку"),
        )
    }

    @Test fun agentCommandByVoice() {
        assertEquals(
            CustomRoutineParser.Request.Define("такси", listOf("сделай сам: вызови такси домой")),
            CustomRoutineParser.parse("создай команду такси сделай сам вызови такси домой"),
        )
    }

    @Test fun deleteCommand() =
        assertEquals(CustomRoutineParser.Request.Delete("поехали"), CustomRoutineParser.parse("удали команду поехали"))

    @Test fun appSynonymsByVoice() {
        assertEquals(CustomRoutineParser.Request.Alias("телег", "телеграм", "телегой"), CustomRoutineParser.parse("называй телеграм телегой"))
        assertEquals(CustomRoutineParser.Request.Alias("телега", "телеграм"), CustomRoutineParser.parse("добавь синоним телега для телеграм"))
        assertEquals(CustomRoutineParser.Request.Alias("ютубчик", "youtube"), CustomRoutineParser.parse("синоним ютубчик для youtube"))
    }
}
