package com.jarvis.tools

import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAnswerToolTest {
    private val rss = """<rss><channel><title>лента</title>
        <item><title>Канберра &amp; столица</title><description>Город &lt;b&gt;в Австралии&lt;/b&gt;</description></item>
        <item><title><![CDATA[Вторая новость]]></title><description></description></item>
        </channel></rss>"""

    private fun run(ask: (String) -> String?, page: String = rss, query: String = "столица австралии") =
        WebAnswerTool(ask, fetch = { page }).execute(ToolCall(requestId = "r", toolId = "web.answer", arguments = mapOf("query" to query)))

    @Test fun `rss items become plain lines`() {
        assertEquals(listOf("Канберра & столица: Город в Австралии", "Вторая новость"), WebAnswerTool.parseRss(rss))
    }

    @Test fun `model answers from found text`() {
        var seen = ""
        val r = run({ seen = it; "Канберра, сэр." })
        assertEquals("Канберра, сэр.", r.message)
        assertTrue(seen.contains("Город в Австралии") && seen.contains("столица австралии"))
    }

    @Test fun `without model the first result is read`() {
        val r = run({ null })
        assertEquals(ToolResultStatus.SUCCESS, r.status)
        assertTrue(r.message.orEmpty(), r.message.orEmpty().startsWith("Канберра"))
    }

    @Test fun `nothing found is an honest failure`() {
        val r = run({ "выдумка" }, page = "<rss></rss>")
        assertEquals(ToolResultStatus.FAILED, r.status)
        assertTrue(r.message.orEmpty().contains("Не удалось"))
    }

    @Test fun `empty query asks back`() {
        assertEquals(ToolResultStatus.AMBIGUOUS, run({ null }, query = "").status)
    }
}
