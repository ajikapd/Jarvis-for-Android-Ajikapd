package com.jarvis.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InfoFeedsTest {
    @Test fun rssTitles() {
        val rss = """
            <channel><title>РИА Новости</title>
            <item><title>Первая &quot;новость&quot;</title><link>x</link></item>
            <item><title><![CDATA[Вторая]]></title></item>
            <item><title>Третья</title></item></channel>
        """
        assertEquals(listOf("Первая \"новость\"", "Вторая"), InfoFeeds.headlines(rss, 2))
    }

    @Test fun ruble() {
        assertEquals("81 рубль 61 копейка", InfoFeeds.rubles(81.613))
        assertEquals("92 рубля 12 копеек", InfoFeeds.rubles(92.12))
        assertEquals("11 рублей 5 копеек", InfoFeeds.rubles(11.05))
    }

    @Test fun report() = assertEquals(
        "Доллар 80 рублей 0 копеек. Евро 100 рублей 0 копеек. Юань 10 рублей 0 копеек.",
        InfoFeeds.ratesReport("""{"rates":{"RUB":80.0,"EUR":0.8,"CNY":8.0}}"""),
    )

    @Test fun brokenAnswer() = assertNull(InfoFeeds.ratesReport("""{"result":"error"}"""))
}
