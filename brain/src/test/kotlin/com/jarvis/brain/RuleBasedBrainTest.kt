package com.jarvis.brain

import com.jarvis.core.common.model.IntentType
import com.jarvis.core.common.model.UserRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedBrainTest {
    @org.junit.Test
    fun `delayed command is scheduled not executed`() {
        for (say in listOf(
            "через десять минут выключи фонарик", "завтра в восемь утра включи музыку",
            "каждое утро в восемь делай сводку", "выключи фонарик через час",
        )) {
            val s = slots(say, IntentType.REMINDER)
            assertEquals(say, true, s["command"])
        }
    }

    @org.junit.Test
    fun `plain commands with time words stay as they are`() {
        assertEquals(IntentType.TIMER, (route("поставь таймер через минуту") as? BrainResult.SkillCall)?.intent?.type ?: IntentType.TIMER)
        assertEquals(IntentType.TIMER, (route("поставь будильник на завтра на семь утра") as BrainResult.SkillCall).intent.type)
        assertEquals(IntentType.FLASHLIGHT, (route("выключи фонарик") as BrainResult.SkillCall).intent.type)
        assertEquals(IntentType.WEATHER, (route("какая погода завтра утром") as BrainResult.SkillCall).intent.type)
        assertTrue(route("позвони маме завтра в десять").let { (it as? BrainResult.SkillCall)?.intent?.type != IntentType.REMINDER })
    }

    @org.junit.Test
    fun `app name after enable opens the app`() {
        assertEquals("ютуб", slots("включи ютуб", IntentType.LAUNCH_APP)["app"])
    }

    @org.junit.Test
    fun `addressee without text asks what to write`() {
        assertTrue(route("напиши маме") is BrainResult.Clarify)
    }

    @org.junit.Test
    fun `info requests`() {
        assertEquals(IntentType.NEWS, (route("какие новости") as BrainResult.SkillCall).intent.type)
        assertEquals(IntentType.RATES, (route("какой курс доллара") as BrainResult.SkillCall).intent.type)
        assertEquals(IntentType.LOCATION, (route("где я нахожусь") as BrainResult.SkillCall).intent.type)
        assertEquals("list", slots("какие у меня приложения", IntentType.LAUNCH_APP)["action"])
    }

    @org.junit.Test
    fun `call control`() {
        assertEquals("redial", slots("перезвони", IntentType.CALL)["action"])
        assertEquals("answer", slots("ответь на звонок", IntentType.PHONE_CONTROL)["action"])
        assertEquals("end", slots("сбрось звонок", IntentType.PHONE_CONTROL)["action"])
    }

    @org.junit.Test
    fun `calendar days and titles`() {
        assertEquals(1, slots("что у меня завтра", IntentType.CREATE_EVENT)["day"])
        assertEquals(1, slots("какие планы на завтра", IntentType.CREATE_EVENT)["day"])
        assertEquals("встречу завтра в три", slots("запиши в календарь встречу завтра в три", IntentType.CREATE_EVENT)["title"])
    }

    @org.junit.Test
    fun `weather on a named day is not a city`() {
        val weekend = slots("погода на выходные", IntentType.WEATHER)
        assertEquals(null, weekend["city"])
        assertTrue(weekend["day"] is Int)
        val friday = slots("какая погода в пятницу", IntentType.WEATHER)
        assertEquals(null, friday["city"])
        assertTrue(friday["day"] is Int)
    }

    @org.junit.Test
    fun `forget a fact`() {
        assertEquals("кофе", slots("забудь про кофе", IntentType.FORGET)["query"])
    }

    private fun slots(say: String, type: IntentType): Map<String, Any?> {
        val r = route(say) as? BrainResult.SkillCall ?: throw AssertionError("$say -> ${route(say)}")
        assertEquals(say, type, r.intent.type)
        return r.intent.slots.mapValues { it.value.value }
    }

    @org.junit.Test
    fun `device switches are never music`() {
        val cases = mapOf(
            "включи вайфай" to ("wifi" to "on"), "выключи вай фай" to ("wifi" to "off"),
            "включи блютуз" to ("bluetooth" to "on"), "выключи блютуз" to ("bluetooth" to "off"),
            "включи авиарежим" to ("airplane" to "on"), "включи мобильный интернет" to ("mobile_data" to "on"),
            "включи режим не беспокоить" to ("dnd" to "on"), "выключи режим не беспокоить" to ("dnd" to "off"),
            "поставь на беззвучный" to ("silent" to "on"), "включи вибрацию" to ("vibrate" to "on"),
            "включи автояркость" to ("auto_brightness" to "on"), "поверни экран" to ("auto_rotate" to "on"),
            "включи тёмную тему" to ("dark_mode" to "on"), "включи энергосбережение" to ("battery_saver" to "on"),
            "включи точку доступа" to ("hotspot" to "on"),
        )
        for ((say, want) in cases) {
            val s = slots(say, IntentType.SYSTEM_SETTING)
            assertEquals(say, want, s["name"] to s["value"])
        }
    }

    @org.junit.Test
    fun `давай is not wifi and a song named like a setting stays music`() {
        assertEquals(IntentType.MUSIC, (route("давай включи музыку") as BrainResult.SkillCall).intent.type)
        assertEquals(IntentType.MUSIC, (route("включи песню самолёт") as BrainResult.SkillCall).intent.type)
    }

    @org.junit.Test
    fun `volume and brightness verbs`() {
        assertEquals("down", slots("убавь звук", IntentType.VOLUME)["direction"])
        assertEquals(10, slots("поставь громкость на десять", IntentType.VOLUME)["percent"])
        assertEquals("down", slots("убавь яркость", IntentType.BRIGHTNESS)["direction"])
        assertEquals(100, slots("яркость на максимум", IntentType.BRIGHTNESS)["percent"])
        assertEquals(50, slots("яркость на пятьдесят", IntentType.BRIGHTNESS)["percent"])
    }

    @org.junit.Test
    fun `timer and alarm wording`() {
        assertEquals(90, slots("поставь таймер на полтора часа", IntentType.TIMER)["minutes"])
        assertEquals(90, slots("таймер на полторы минуты", IntentType.TIMER)["seconds"])
        assertEquals("alarm_cancel", slots("отмени будильник", IntentType.TIMER)["action"])
        assertEquals(6, slots("разбуди меня в шесть тридцать", IntentType.TIMER)["hour"])
        assertEquals(30, slots("разбуди меня в шесть тридцать", IntentType.TIMER)["minute"])
        assertEquals("alarm", slots("разбуди меня через восемь часов", IntentType.TIMER)["action"])
        assertEquals("start", slots("запусти секундомер", IntentType.TIMER)["mode"])
        assertEquals("stop", slots("останови секундомер", IntentType.TIMER)["mode"])
    }

    @org.junit.Test
    fun `music wording`() {
        assertEquals("pause", slots("останови музыку", IntentType.MUSIC)["action"])
        assertEquals("status", slots("что сейчас играет", IntentType.MUSIC)["action"])
        assertEquals("status", slots("какая это песня", IntentType.MUSIC)["action"])
        assertEquals("forward", slots("перемотай вперёд", IntentType.MUSIC)["action"])
        assertEquals("rewind", slots("перемотай назад", IntentType.MUSIC)["action"])
        assertEquals("спокойное", slots("включи что нибудь спокойное", IntentType.MUSIC)["query"])
    }

    @org.junit.Test
    fun `messages find the addressee`() {
        val sms = slots("отправь смс папе привет", IntentType.SEND_SMS)
        assertEquals("папе", sms["recipient"])
        assertEquals("привет", sms["text"])
        val tg = slots("напиши в телеграм диме привет", IntentType.SEND_TELEGRAM)
        assertEquals("привет", tg["text"])
        assertEquals("read", slots("прочитай последнее сообщение", IntentType.SEND_SMS)["action"])
        assertEquals("read", slots("есть новые сообщения", IntentType.SEND_SMS)["action"])
    }

    @org.junit.Test
    fun `misc wording`() {
        assertEquals("home", slots("вернись на главный экран", IntentType.UI_GLOBAL)["action"])
        assertEquals("clear", slots("очисти уведомления", IntentType.NOTIFICATIONS)["action"])
    }

    private fun route(say: String) = RuleBasedBrain().route(UserRequest(say), emptyList(), emptyList())

    private fun slot(say: String, type: IntentType, name: String): Any? {
        val r = route(say) as BrainResult.SkillCall
        assertEquals(say, type, r.intent.type)
        return r.intent.slots[name]?.value
    }

    @org.junit.Test
    fun `briefing phrases`() {
        for (say in listOf("утренняя сводка", "сводка на день", "что у меня на сегодня", "брифинг", "какие планы на сегодня")) {
            assertEquals(say, IntentType.BRIEFING, (route(say) as BrainResult.SkillCall).intent.type)
        }
    }

    @org.junit.Test
    fun `navigation extracts the place`() {
        assertEquals("работы", slot("проложи маршрут до работы", IntentType.NAVIGATE, "destination"))
        assertEquals("вокзала", slot("как доехать до вокзала", IntentType.NAVIGATE, "destination"))
        assertEquals("аптеку", slot("покажи на карте аптеку", IntentType.NAVIGATE, "destination"))
        assertEquals("домой", slot("поехали домой", IntentType.NAVIGATE, "destination"))
    }

    @org.junit.Test
    fun `everyday words are not navigation`() {
        assertTrue(route("поехали дальше") !is BrainResult.SkillCall)
        val memoryCard = route("что на карте памяти")
        assertTrue((memoryCard as? BrainResult.SkillCall)?.intent?.type != IntentType.NAVIGATE)
    }

    @org.junit.Test
    fun `web search extracts the query`() {
        assertEquals("рецепт борща", slot("найди в интернете рецепт борща", IntentType.WEB_SEARCH, "query"))
        assertEquals("отзывы о пылесосе", slot("поищи в сети отзывы о пылесосе", IntentType.WEB_SEARCH, "query"))
    }

    @org.junit.Test
    fun `camera phrases`() {
        assertEquals("photo", slot("сфотографируй", IntentType.CAMERA, "mode"))
        assertEquals("photo", slot("сделай селфи", IntentType.CAMERA, "mode"))
        assertEquals("video", slot("сними видео", IntentType.CAMERA, "mode"))
    }

    @org.junit.Test
    fun `screenshot is not a camera shot`() {
        assertEquals(IntentType.SCREENSHOT, (route("сделай скриншот") as BrainResult.SkillCall).intent.type)
    }

    private fun reminderSlots(say: String): Map<String, Any?> {
        val r = RuleBasedBrain().route(UserRequest(say), emptyList(), emptyList()) as BrainResult.SkillCall
        assertEquals(say, IntentType.REMINDER, r.intent.type)
        return r.intent.slots.mapValues { it.value.value }
    }

    @org.junit.Test
    fun `reminder phrases`() {
        assertEquals("add", reminderSlots("напомни завтра в девять позвонить маме")["action"])
        assertEquals("add", reminderSlots("напомни через двадцать минут выключить плиту")["action"])
        assertEquals("add", reminderSlots("поставь напоминание на завтра")["action"])
        assertEquals("list", reminderSlots("какие у меня напоминания")["action"])
        assertEquals("cancel", reminderSlots("отмени напоминание")["action"])
        assertEquals(true, reminderSlots("удали все напоминания")["all"])
        assertEquals("хлеб", reminderSlots("отмени напоминание про хлеб")["query"])
    }

    @org.junit.Test
    fun `remind without text stays a timer`() {
        val r = RuleBasedBrain().route(UserRequest("напомни через десять минут"), emptyList(), emptyList())
        assertEquals(IntentType.TIMER, (r as BrainResult.SkillCall).intent.type)
    }

    @org.junit.Test
    fun `system status phrases`() {
        for (say in listOf("статус систем", "сколько заряда", "какой заряд батареи", "отчёт о системах", "сколько свободной памяти")) {
            val r = RuleBasedBrain().route(UserRequest(say), emptyList(), emptyList())
            assertEquals(say, IntentType.SYSTEM_STATUS, (r as BrainResult.SkillCall).intent.type)
        }
    }


    private val brain = RuleBasedBrain()

    private fun call(text: String): BrainResult {
        val top = emptyList<BrainRouter.SkillInfo>()
        return brain.route(UserRequest(text), emptyList(), top)
    }

    private fun asSkill(result: BrainResult): BrainResult.SkillCall =
        result as BrainResult.SkillCall

    // ── flashlight ───────────────────────────────────────────────

    @Test
    fun `flashlight on`() {
        val c = asSkill(call("включи фонарик"))
        assertEquals(IntentType.FLASHLIGHT, c.intent.type)
        assertEquals("on", c.intent.slots["action"]?.value)
    }

    @Test
    fun `flashlight off`() {
        val c = asSkill(call("выключи свет"))
        assertEquals(IntentType.FLASHLIGHT, c.intent.type)
        assertEquals("off", c.intent.slots["action"]?.value)
    }

    // ── volume / brightness ──────────────────────────────────────

    @Test
    fun `volume up`() {
        val c = asSkill(call("сделай громче"))
        assertEquals(IntentType.VOLUME, c.intent.type)
        assertEquals("up", c.intent.slots["direction"]?.value)
    }

    @Test
    fun `volume mute`() {
        val c = asSkill(call("выключи звук"))
        assertEquals(IntentType.VOLUME, c.intent.type)
        assertEquals("mute", c.intent.slots["direction"]?.value)
    }

    @Test
    fun `brightness down`() {
        val c = asSkill(call("сделай экран темнее"))
        assertEquals(IntentType.BRIGHTNESS, c.intent.type)
        assertEquals("down", c.intent.slots["direction"]?.value)
    }

    // ── timer ────────────────────────────────────────────────────

    @Test
    fun `timer - 5 minutes`() {
        val c = asSkill(call("поставь таймер на 5 минут"))
        assertEquals(IntentType.TIMER, c.intent.type)
        assertEquals(5, c.intent.slots["minutes"]?.value)
    }

    @Test
    fun `timer - reminder via minyut`() {
        val c = asSkill(call("напомни через 10 минут"))
        assertEquals(IntentType.TIMER, c.intent.type)
        assertEquals(10, c.intent.slots["minutes"]?.value)
    }

    @Test
    fun `timer - one hour becomes 60 minutes`() {
        val c = asSkill(call("поставь таймер на час"))
        assertEquals(IntentType.TIMER, c.intent.type)
        assertEquals(60, c.intent.slots["minutes"]?.value)
    }

    @Test
    fun `timer - seconds`() {
        val c = asSkill(call("таймер на 30 секунд"))
        assertEquals(IntentType.TIMER, c.intent.type)
        assertEquals(30, c.intent.slots["seconds"]?.value)
    }

    @Test
    fun `timer - no duration clarifies`() {
        val r = call("поставь таймер")
        assertTrue(r is BrainResult.Clarify)
    }

    // ── send SMS ─────────────────────────────────────────────────

    @Test
    fun `send sms`() {
        val c = asSkill(call("отправь маме сообщение куплю хлеб"))
        assertEquals(IntentType.SEND_SMS, c.intent.type)
        val text = c.intent.slots["text"]?.value as? String
        assertNotNull(text)
        assertTrue(text?.contains("куплю хлеб") == true)
        assertEquals("маме", c.intent.slots["recipient"]?.value)
    }

    @Test
    fun `напиши routes to sms not call`() {
        val c = asSkill(call("напиши маме привет"))
        assertEquals(IntentType.SEND_SMS, c.intent.type)
        assertEquals("маме", c.intent.slots["recipient"]?.value)
        assertEquals("привет", c.intent.slots["text"]?.value)
        assertTrue(c.confidence >= 0.85f)
    }

    @Test
    fun `напиши в телеграмме asks for contact`() {
        val c = asSkill(call("напиши в телеграмме"))
        assertEquals(IntentType.SEND_TELEGRAM, c.intent.type)
        assertTrue(c.intent.slots["chat"] == null)
        assertTrue(c.intent.slots["text"] == null)
    }

    @Test
    fun `напиши любимой жене в телеграмме`() {
        val c = asSkill(call("напиши любимой жене в телеграмме"))
        assertEquals(IntentType.SEND_TELEGRAM, c.intent.type)
        assertEquals("жена", c.intent.slots["chat"]?.value)
        assertTrue(c.intent.slots["text"] == null)
    }

    @Test
    fun `напиши избранное в телеграмме`() {
        val c = asSkill(call("напиши избранное в телеграмме"))
        assertEquals(IntentType.SEND_TELEGRAM, c.intent.type)
        assertEquals("Избранное", c.intent.slots["chat"]?.value)
        assertTrue(c.intent.slots["text"] == null)
    }

    @Test
    fun `напиши контакту в телеграмме с текстом`() {
        val c = asSkill(call("напиши маме в телеграмме я скоро буду дома"))
        assertEquals(IntentType.SEND_TELEGRAM, c.intent.type)
        assertEquals("мама", c.intent.slots["chat"]?.value)
        assertEquals("я скоро буду дома", c.intent.slots["text"]?.value)
    }

    @Test
    fun `напиши в telegram только текст без контакта`() {
        val c = asSkill(call("напиши в telegram я скоро буду дома"))
        assertEquals(IntentType.SEND_TELEGRAM, c.intent.type)
        assertTrue(c.intent.slots["chat"] == null)
        assertEquals("я скоро буду дома", c.intent.slots["text"]?.value)
    }

    // ── launch app ───────────────────────────────────────────────

    @Test
    fun `launch app`() {
        val c = asSkill(call("открой Telegram"))
        assertEquals(IntentType.LAUNCH_APP, c.intent.type)
        assertEquals("telegram", c.intent.slots["app"]?.value)
    }

    // ── calendar event ───────────────────────────────────────────

    @Test
    fun `create event`() {
        val c = asSkill(call("создай событие встреча с Иваном"))
        assertEquals(IntentType.CREATE_EVENT, c.intent.type)
        assertEquals("встреча с иваном", c.intent.slots["title"]?.value)
    }

    @Test
    fun `reminder with duration keeps its text and is not an event`() {
        val c = asSkill(call("напомни про встречу через час"))
        assertEquals(IntentType.REMINDER, c.intent.type)
    }

    // ── chat fallback ────────────────────────────────────────────

    @Test
    fun `free chat routes to Chat`() {
        val r = call("расскажи анекдот")
        assertTrue(r is BrainResult.Chat)
    }

    @Test
    fun `non-command unclear phrase routes to Chat`() {
        val r = call("просто разговор ни о чём")
        assertTrue(r is BrainResult.Chat)
    }

    // ── weather ──────────────────────────────────────────────────

    @Test
    fun `weather tomorrow with city`() {
        val c = asSkill(call("какая погода завтра в москве"))
        assertEquals(IntentType.WEATHER, c.intent.type)
        assertEquals(1, c.intent.slots["day"]?.value)
        assertNotNull(c.intent.slots["city"]?.value)
    }

    @Test
    fun `weather послезавтра`() {
        val c = asSkill(call("погода на послезавтра"))
        assertEquals(IntentType.WEATHER, c.intent.type)
        assertEquals(2, c.intent.slots["day"]?.value)
    }

    // ── свободная речь: фразы из прогона на телефоне ─────────

    private fun slots(text: String): Map<String, Any?> =
        asSkill(call(text)).intent.slots.mapValues { it.value.value }

    @Test
    fun `free phrases control flashlight`() {
        assertEquals("on", slots("посвети")["action"])
        assertEquals("on", slots("сделай светло")["action"])
        assertEquals("off", slots("убери свет")["action"])
    }

    @Test
    fun `volume and brightness percent in words`() {
        assertEquals(mapOf("direction" to "set", "percent" to 40), slots("громкость на сорок процентов"))
        assertEquals(mapOf("direction" to "set", "percent" to 50), slots("яркость на пятьдесят процентов"))
    }

    @Test
    fun `timer words seconds cancel status`() {
        assertEquals(10, slots("поставь таймер на десять секунд")["seconds"])
        assertEquals(2, slots("засеки две минуты")["minutes"])
        assertEquals(25, slots("таймер на двадцать пять минут")["minutes"])
        assertEquals(30, slots("таймер на полчаса")["minutes"])
        assertEquals("cancel", slots("отмени таймер")["action"])
        assertEquals("status", slots("сколько осталось на таймере")["action"])
    }

    @Test
    fun `alarm time`() {
        assertEquals(mapOf("action" to "alarm", "hour" to 7, "minute" to 0), slots("поставь будильник на семь утра"))
        assertEquals(mapOf("action" to "alarm", "hour" to 20, "minute" to 0), slots("будильник на восемь вечера"))
        assertEquals(mapOf("action" to "alarm", "hour" to 7, "minute" to 30), slots("будильник на семь тридцать"))
    }

    @Test
    fun `screenshot is not music`() {
        assertEquals(IntentType.SCREENSHOT, asSkill(call("сделай скриншот")).intent.type)
    }

    @Test
    fun `music without title`() {
        assertEquals("resume", slots("включи музыку")["action"])
        assertEquals("pause", slots("выключи музыку")["action"])
    }

    @Test
    fun `settings sections and call history`() {
        assertEquals("battery", slots("открой настройки батареи")["name"])
        assertEquals("bluetooth", slots("открой настройки блютуз")["name"])
        assertEquals("history", slots("кто мне звонил")["action"])
        assertEquals(IntentType.LAUNCH_APP, asSkill(call("зайди в телеграм")).intent.type)
    }

    @Test
    fun `launch ignores filler words`() {
        assertEquals("настройки", slots("зайди в настройки")["app"])
        assertEquals("ютуб", slots("запусти ютуб пожалуйста")["app"])
    }

    @Test
    fun `slang mute is not louder`() {
        assertEquals("mute", slots("выруби звук")["direction"])
        assertEquals("mute", slots("отключи звук")["direction"])
    }
}
