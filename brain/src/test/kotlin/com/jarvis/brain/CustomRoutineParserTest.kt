package com.jarvis.brain

import com.jarvis.brain.CustomRoutineParser.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomRoutineParserTest {
    @Test fun whenISay() = assertEquals(
        Request.Define("поехали", listOf("включи блютуз", "включи музыку")),
        CustomRoutineParser.parse("когда я говорю поехали, включи блютуз и музыку"),
    )

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
}
