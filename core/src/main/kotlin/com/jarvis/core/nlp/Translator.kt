package com.jarvis.core.nlp

data class TranslationTarget(val name: String, val locale: String)

/** Голосовой перевод: разовый и режим переводчика, где переводится каждая фраза. */
object Translator {
    sealed class Request {
        data class Once(val target: TranslationTarget, val text: String) : Request()
        data class Start(val target: TranslationTarget) : Request()
        object Stop : Request()
    }

    private val TARGETS = mapOf(
        "английск" to TranslationTarget("английский", "en-US"), "немецк" to TranslationTarget("немецкий", "de-DE"),
        "французск" to TranslationTarget("французский", "fr-FR"), "испанск" to TranslationTarget("испанский", "es-ES"),
        "итальянск" to TranslationTarget("итальянский", "it-IT"), "китайск" to TranslationTarget("китайский", "zh-CN"),
        "турецк" to TranslationTarget("турецкий", "tr-TR"),
    )
    private val ONCE = Regex("^(?:переведи|перевод|как будет|как сказать)\\s+(?:на\\s+)?(\\S+)\\s+(?:язык\\s+)?(.+)$")
    private val ONCE_TAIL = Regex("^(?:переведи|как будет|как сказать)\\s+(.+?)\\s+(?:на|по)\\s+(\\S+)(?:\\s+язык)?$")
    private val START = Regex("(?:режим переводчика|включи переводчик|будь переводчиком|режим перевода)\\s+(?:на\\s+)?(\\S+)")
    private val STOP = setOf("выключи переводчик", "стоп перевод", "хватит переводить", "закончи перевод", "выключи режим переводчика")
    private const val MIN_CYRILLIC_SHARE = 0.3

    /** Язык режима переводчика; озвучка берёт отсюда голос для иностранного текста. */
    @Volatile var active: TranslationTarget? = null

    fun parse(phrase: String): Request? {
        val text = phrase.lowercase().trim().trimEnd('.', '!', '?')
        if (text in STOP) return Request.Stop
        START.find(text)?.let { m -> target(m.groupValues[1])?.let { return Request.Start(it) } }
        ONCE.find(text)?.let { m -> target(m.groupValues[1])?.let { return Request.Once(it, m.groupValues[2]) } }
        ONCE_TAIL.find(text)?.let { m -> target(m.groupValues[2])?.let { return Request.Once(it, m.groupValues[1]) } }
        return null
    }

    private fun target(word: String) = TARGETS.entries.firstOrNull { word.startsWith(it.key) }?.value

    fun prompt(target: TranslationTarget, text: String) =
        "Переведи на ${target.name} язык. Ответь только переводом, без кавычек и пояснений: $text"

    /** Локаль для озвучки или null, если текст русский. */
    fun speechLocale(text: String): String? {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return null
        val cyrillic = letters.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        if (cyrillic.toDouble() / letters.length >= MIN_CYRILLIC_SHARE) return null
        return active?.locale ?: "en-US"
    }
}
