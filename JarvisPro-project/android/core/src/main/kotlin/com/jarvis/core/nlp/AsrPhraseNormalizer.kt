package com.jarvis.core.nlp

/**
 * Пост-обработка ASR-текста для смешанных RU+EN команд.
 *
 * Streaming-модель zipformer-small-ru — кириллический BPE: английские слова
 * («father», «andy warhol») приходят как фонетическая кириллица
 * («фазер», «энди ворхол»). Слой восстанавливает латиницу для известных
 * имён и оставляет уже распознанную латиницу как есть.
 *
 * Полный bilingual ASR без второй модели невозможен; для музыки дополнительно
 * работает [MusicQueryMatcher] (нечёткое сравнение с каталогом).
 */
object AsrPhraseNormalizer {

    fun normalize(text: String): String {
        if (text.isBlank()) return text
        val tokens = TOKEN_REGEX.findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return text.trim()

        // Сначала пробуем многословные фразы (длинные → короткие)
        val joined = tokens.joinToString(" ") { it.lowercase() }
        for ((cyr, latin) in PHRASE_LATIN) {
            if (joined.contains(cyr)) {
                val replaced = joined.replace(cyr, latin)
                return rebuildCasing(replaced, tokens)
            }
        }

        val rebuilt = tokens.map { token ->
            val lower = token.lowercase()
            LATIN_BY_CYRILLIC[lower]
                ?: LATIN_BY_CYRILLIC[Transliterator.normalize(lower)]
                ?: token
        }
        return rebuilt.joinToString(" ")
    }

    /**
     * Неполная команда: глагол без объекта («включи»),
     * музыкальный каркас без названия, оборванный хвост («… в»),
     * или «напиши любимой жене» без «в телеграмме» — endpoint срезал продолжение.
     */
    fun isIncompleteCommand(text: String): Boolean {
        val words = TOKEN_REGEX.findAll(text.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return true
        if (words.size == 1) return words[0] in BARE_COMMAND_VERBS || words[0] in WRITE_VERBS
        val content = words.filterNot { it in FILLER_WORDS }
        if (content.isEmpty()) return true
        if (content.size == 1 && (content[0] in BARE_COMMAND_VERBS || content[0] in WRITE_VERBS)) {
            return true
        }
        // «… в» / «… на» — ждём объект
        if (content.last() in DANGLING_PREPS) return true
        if (isMusicFrameWithoutTitle(content)) return true
        if (isTruncatedMessengerWrite(content)) return true
        return false
    }

    /**
     * «напиши любимой жене» без telegram/смс — часто обрубок до «в телеграмме».
     * «напиши маме привет» — уже полное SMS, не держим.
     * «напиши маме» — одно слово после глагола, не держим (SMS/уточнение).
     */
    private fun isTruncatedMessengerWrite(content: List<String>): Boolean {
        val verbIdx = content.indexOfFirst { it in WRITE_VERBS }
        if (verbIdx < 0) return false
        val rest = content.drop(verbIdx + 1)
        if (rest.isEmpty()) return true
        if (rest.any { isMessengerToken(it) || it in SMS_MARKERS }) return false
        if (rest.any { it in MESSAGE_STARTERS }) return false
        // 2–4 слова контакта без мессенджера («любимой жене») — ждём «в телеграмме»
        return rest.size in 2..4
    }

    private fun isMessengerToken(w: String): Boolean =
        w == "telegram" || w == "тг" || w.startsWith("телеграм") ||
            w.startsWith("whatsapp") || w.startsWith("ватсап") || w.startsWith("вотсап")

    /** «включи песню» / «поставь трек» — ждём название (часто EN). */
    private fun isMusicFrameWithoutTitle(content: List<String>): Boolean {
        val markerIdx = content.indexOfFirst { w ->
            MUSIC_FRAME_WORDS.any { m -> w == m || w.startsWith(m) }
        }
        if (markerIdx < 0) return false
        // «включи музыку» — законченная команда; ждать название надо только у «включи песню».
        if (content[markerIdx].startsWith("музык")) return false
        val hasVerb = content.any { it in BARE_COMMAND_VERBS }
        if (!hasVerb) return false
        return content.drop(markerIdx + 1).isEmpty()
    }

    val BARE_COMMAND_VERBS: Set<String> = setOf(
        "включи", "включить", "включите",
        "выключи", "выключить", "выключите",
        "открой", "открыть", "откройте",
        "закрой", "закрыть",
        "запусти", "запустить",
        "сделай", "сделайте",
        "поставь", "поставьте",
        "найди", "найдите",
        "покажи", "покажите",
        "напиши", "написать", "напечатай", "отправь", "передай",
    )

    private val WRITE_VERBS = setOf(
        "напиши", "написать", "напечатай", "отправь", "передай", "отправить",
    )

    private val DANGLING_PREPS = setOf(
        "в", "во", "на", "к", "ко", "с", "со", "для", "про", "и", "или", "а",
    )

    private val SMS_MARKERS = setOf("смс", "sms", "сообщение", "сообщением")

    private val MESSAGE_STARTERS = setOf(
        "привет", "пока", "здравствуй", "здравствуйте", "добрый", "добрая",
        "спасибо", "пожалуйста", "ок", "хорошо", "ладно",
        "я", "мы", "ты", "вы", "он", "она", "они",
        "сейчас", "скоро", "уже", "ещё", "еще", "сегодня", "завтра",
        "куплю", "буду", "выезжаю", "приеду", "иду", "еду",
    )

    private fun rebuildCasing(replaced: String, originalTokens: List<String>): String {
        // normalize() всегда возвращает lower для mapped EN; глаголы RU как в исходнике
        val out = replaced.split(Regex("\\s+"))
        if (out.size != originalTokens.size) return replaced
        return out.mapIndexed { i, t ->
            if (t.all { it in 'a'..'z' || it in '0'..'9' }) t
            else originalTokens.getOrElse(i) { t }
        }.joinToString(" ")
    }

    private val FILLER_WORDS = setOf(
        "пожалуйста", "плиз", "please", "ну", "давай", "просто", "мне", "мне-ка",
    )

    private val MUSIC_FRAME_WORDS = listOf(
        "песн", "песен", "трек", "музык", "альбом", "исполнит", "артист", "композиц",
    )

    /** Многословные EN-фразы (кириллическая ослышка → латиница). */
    private val PHRASE_LATIN: List<Pair<String, String>> = listOf(
        "энди ворхол" to "andy warhol",
        "энди вархол" to "andy warhol",
        "энди уорхол" to "andy warhol",
        "богемиан рэпсоди" to "bohemian rhapsody",
        "богемиан рапсоди" to "bohemian rhapsody",
        "богемиан рhapsody" to "bohemian rhapsody",
        "стейт оф грайс" to "state of grace",
        "шейп оф ю" to "shape of you",
        "блейдинг лав" to "blinding lights",
        "блайндинг лайтс" to "blinding lights",
    ).sortedByDescending { it.first.length }

    /**
     * Частотные EN-имена/бренды → каноническая латиница.
     * Ключи — типичные кириллические «ослышки» RU-ASR.
     */
    private val LATIN_BY_CYRILLIC: Map<String, String> = mapOf(
        // music / artists / tracks
        "фазер" to "father",
        "фатер" to "father",
        "фазэр" to "father",
        "фадр" to "father",
        "фазе" to "father",
        "ворхол" to "warhol",
        "вархол" to "warhol",
        "уорхол" to "warhol",
        "энди" to "andy",
        "эндй" to "andy",
        "богемиан" to "bohemian",
        "богемьян" to "bohemian",
        "рэпсоди" to "rhapsody",
        "рапсоди" to "rhapsody",
        "инси" to "incy",
        "инци" to "incy",
        "инсиу" to "incy",
        "спотифай" to "spotify",
        "спотифаи" to "spotify",
        "ютуб" to "youtube",
        "ютьюб" to "youtube",
        "нетфликс" to "netflix",
        "дрейк" to "drake",
        "дэйк" to "drake",
        "уикенд" to "weeknd",
        "викнд" to "weeknd",
        "тейлор" to "taylor",
        "свифт" to "swift",
        "бибер" to "bieber",
        "эминем" to "eminem",
        "эминэм" to "eminem",
        // messengers / apps
        "телеграм" to "telegram",
        "телеграмм" to "telegram",
        "телеграме" to "telegram",
        "телеграмме" to "telegram",
        "телеграму" to "telegram",
        "телеграмму" to "telegram",
        "телеграма" to "telegram",
        "телеграмма" to "telegram",
        "вотсап" to "whatsapp",
        "ватсап" to "whatsapp",
        "инстаграм" to "instagram",
        "инстаграмм" to "instagram",
        "хром" to "chrome",
        "гугл" to "google",
        "джимейл" to "gmail",
        "твиттер" to "twitter",
        "твитер" to "twitter",
        "дискорд" to "discord",
        "тиктоке" to "tiktok",
        "тикток" to "tiktok",
    )

    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
}
