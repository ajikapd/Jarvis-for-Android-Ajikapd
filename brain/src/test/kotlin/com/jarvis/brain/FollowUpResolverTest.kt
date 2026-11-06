package com.jarvis.brain

import com.jarvis.core.common.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpResolverTest {
    @Test
    fun `delayed command is not a patch of the last action`() {
        val torch = ToolCall(requestId = "x", toolId = "flashlight", arguments = mapOf("action" to "on"))
        assertEquals(null, FollowUpResolver.resolve("через десять минут выключи фонарик", torch))
        assertEquals("off", FollowUpResolver.resolve("выключи", torch)?.arguments?.get("action"))
    }

    private fun timer(vararg args: Pair<String, Any>) = ToolCall(requestId = "x", toolId = "timer", arguments = mapOf(*args))

    @Test
    fun `named timer is a new command not a patch of stopwatch`() {
        assertEquals(null, FollowUpResolver.resolve("поставь таймер на полтора часа", timer("action" to "stopwatch", "mode" to "stop")))
        assertEquals(null, FollowUpResolver.resolve("поставь таймер на час", timer("minutes" to 5, "seconds" to 0)))
    }

    @Test
    fun `short patch of a timer keeps working`() {
        val set = timer("minutes" to 5, "seconds" to 0)
        assertEquals(10, FollowUpResolver.resolve("а на 10 минут", set)?.arguments?.get("minutes"))
        assertEquals(90, FollowUpResolver.resolve("а на полтора часа", set)?.arguments?.get("minutes"))
        assertEquals(30, FollowUpResolver.resolve("а на полчаса", set)?.arguments?.get("minutes"))
    }

    @org.junit.Test
    fun `reminder time answer completes the phrase`() {
        val last = com.jarvis.core.common.model.ToolCall(toolId = "reminder", requestId = "1", arguments = mapOf("action" to "add", "phrase" to "напомни купить хлеб"))
        org.junit.Assert.assertEquals(
            "напомни купить хлеб завтра в девять",
            FollowUpResolver.resolve("завтра в девять", last)?.arguments?.get("phrase"),
        )
        org.junit.Assert.assertNull(FollowUpResolver.resolve("выключи фонарик", last))
    }

    @org.junit.Test
    fun `finished reminder takes no follow up`() {
        val last = com.jarvis.core.common.model.ToolCall(toolId = "reminder", requestId = "1", arguments = mapOf("phrase" to "напомни завтра в 9 купить хлеб"))
        org.junit.Assert.assertNull(FollowUpResolver.resolve("завтра в девять", last))
    }


    private fun weather(city: String? = "Москва", day: Int? = null) = ToolCall(
        requestId = "r1",
        toolId = "weather",
        arguments = buildMap {
            if (city != null) put("city", city)
            if (day != null) put("day", day)
        },
    )

    private fun launcher(app: String) = ToolCall(
        requestId = "l1",
        toolId = "launcher",
        arguments = mapOf("action" to "open", "app" to app),
    )

    @Test
    fun plainReplay_copiesLastCall() {
        val last = weather()
        val got = FollowUpResolver.resolve("ещё раз", last)
        assertNotNull(got)
        assertEquals("weather", got!!.toolId)
        assertEquals("Москва", got.arguments["city"])
        assertTrue(got.requestId != last.requestId)
    }

    @Test
    fun weather_dayFollowUp_послезавтра() {
        val got = FollowUpResolver.resolve("а послезавтра?", weather())
        assertNotNull(got)
        assertEquals(2, got!!.arguments["day"])
        assertEquals("Москва", got.arguments["city"])
    }

    @Test
    fun weather_cityPatch() {
        val got = FollowUpResolver.resolve("а в казани?", weather("Москва"))
        assertNotNull(got)
        assertEquals("Казань", got!!.arguments["city"])
    }

    @Test
    fun weather_butPrefix_city() {
        val got = FollowUpResolver.resolve("то же самое, но в казани", weather("Москва"))
        assertNotNull(got)
        assertEquals("Казань", got!!.arguments["city"])
    }

    @Test
    fun weather_cityPatch_donetsk() {
        val got = FollowUpResolver.resolve("а в Донецке?", weather("Санкт-Петербург"))
        assertNotNull(got)
        assertEquals("Донецк", got!!.arguments["city"])
    }

    @Test
    fun weather_cityOnly_withoutA() {
        val got = FollowUpResolver.resolve("в питере", weather("Москва"))
        assertNotNull(got)
        assertEquals("Санкт-Петербург", got!!.arguments["city"])
    }

    @Test
    fun knowledge_followUp_pronouns() {
        val last = ToolCall(
            requestId = "k1",
            toolId = "knowledge",
            arguments = mapOf("query" to "дельфины", "topic" to "дельфины", "exclude_title" to "Дельфины"),
        )
        val got = FollowUpResolver.resolve("почему их считают такими умными?", last)
        assertNotNull(got)
        assertEquals("knowledge", got!!.toolId)
        val q = got.arguments["query"]?.toString().orEmpty()
        assertTrue("query should mention dolphins: $q", q.contains("дельфин"))
        assertTrue("query should ask why smart: $q", q.contains("умн") || q.contains("почему"))
        assertEquals("Дельфины", got.arguments["exclude_title"])
    }

    @Test
    fun knowledge_cityOnly_notPatchedAsKnowledge() {
        val last = ToolCall(
            requestId = "k1",
            toolId = "knowledge",
            arguments = mapOf("query" to "дельфины", "topic" to "дельфины"),
        )
        assertNull(FollowUpResolver.resolve("в питере", last))
        assertNull(FollowUpResolver.resolve("а в Донецке?", last))
    }

    @Test
    fun launcher_switchApp_аТеперьВк() {
        val got = FollowUpResolver.resolve("а теперь вк", launcher("телеграм"))
        assertNotNull(got)
        assertEquals("launcher", got!!.toolId)
        assertEquals("open", got.arguments["action"])
        assertEquals("вк", got.arguments["app"])
    }

    @Test
    fun launcher_switchApp_теперьТелеграм() {
        val got = FollowUpResolver.resolve("теперь телеграм", launcher("вк"))
        assertNotNull(got)
        assertEquals("телеграм", got!!.arguments["app"])
    }

    @Test
    fun launcher_minimize() {
        val got = FollowUpResolver.resolve("закрой", launcher("телеграм"))
        assertNotNull(got)
        assertEquals("minimize", got!!.arguments["action"])
        assertEquals("телеграм", got.arguments["app"])
    }

    @Test
    fun volume_followUp_тише() {
        val last = ToolCall(requestId = "r", toolId = "volume", arguments = mapOf("direction" to "up"))
        val got = FollowUpResolver.resolve("тише", last)
        assertNotNull(got)
        assertEquals("down", got!!.arguments["direction"])
    }

    @Test
    fun fullWeatherCommand_notTreatedAsFollowUp() {
        val last = weather("Казань", day = 2)
        assertNull(FollowUpResolver.resolve("какая погода в Москве", last))
    }

    @Test
    fun noLast_returnsNull() {
        assertNull(FollowUpResolver.resolve("ещё раз", null))
    }

    @Test
    fun unrelated_returnsNull() {
        assertNull(FollowUpResolver.resolve("включи фонарик", weather()))
    }

    @Test
    fun knowledge_doesNotStealWeather() {
        val last = ToolCall(
            requestId = "k1",
            toolId = "knowledge",
            arguments = mapOf("query" to "ток", "topic" to "ток"),
        )
        assertNull(FollowUpResolver.resolve("какая погода в питере", last))
        assertNull(FollowUpResolver.resolve("открой телеграм", last))
    }

    @Test
    fun knowledge_followUp_гдеОн() {
        val last = ToolCall(
            requestId = "k1",
            toolId = "knowledge",
            arguments = mapOf(
                "query" to "пушкин",
                "topic" to "пушкин",
                "exclude_title" to "Пушкин, Александр Сергеевич",
            ),
        )
        val got = FollowUpResolver.resolve("где он родился", last)
        assertNotNull(got)
        val q = got!!.arguments["query"]?.toString().orEmpty()
        assertTrue(q.contains("пушкин", ignoreCase = true))
        assertTrue(q.contains("родил") || q.contains("где"))
    }

    @Test
    fun launcher_doesNotStealVolumeCommand() {
        assertNull(FollowUpResolver.resolve("сделай громче", launcher("телеграм")))
        assertNull(FollowUpResolver.resolve("тише", launcher("телеграм")))
        assertNull(FollowUpResolver.resolve("включи фонарик", launcher("телеграм")))
    }

    @Test
    fun `routine definition is not a follow-up of the last command`() {
        val last = ToolCall(requestId = "r", toolId = "volume", arguments = mapOf("level" to 30))
        assertNull(FollowUpResolver.resolve("когда открою ютуб сделай громкость на максимум", last))
    }
}
