package com.jarvis.tools

import android.util.Log
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.tools.net.SimpleHttp
import java.net.URLEncoder

/**
 * Факты для голоса: Wikipedia (summary + search) → DuckDuckGo Instant Answer.
 * Ответы короткие, без дубля заголовка и без web-extract/ссылок.
 */
class KnowledgeTool : Tool {

    override val definition = ToolDefinition(
        id = "knowledge",
        name = "Знания",
        description = "Краткий ответ на фактический вопрос (Википедия / DuckDuckGo)",
        parameters = listOf(
            ToolParameter("query", "Вопрос или тема", ParameterType.STRING, required = true),
            ToolParameter("topic", "Тема диалога (follow-up)", ParameterType.STRING, false),
            ToolParameter("exclude_title", "Пропустить уже озвученную статью", ParameterType.STRING, false),
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.ONLINE_REQUIRED,
    )

    override fun execute(call: ToolCall): ToolResult {
        val query = call.arguments["query"]?.toString()?.trim().orEmpty()
        val topic = call.arguments["topic"]?.toString()?.trim().orEmpty()
        val excludeTitle = call.arguments["exclude_title"]?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            return ToolResult(call.requestId, definition.id, ToolResultStatus.AMBIGUOUS, "О чём рассказать?")
        }
        return try {
            val isFollowUp = looksLikeQuestion(query) || topic.isNotBlank() && query != topic

            // Локальные факты — до сети (быстро, без 429 / офлайн).
            localFact(query, topic)?.let { (title, text) ->
                Log.i(TAG, "local fact title='$title' q='${query.take(40)}'")
                return ok(call, text, "local", title)
            }

            if (isFollowUp && looksLikeQuestion(query)) {
                answerFollowUp(call, query, topic, excludeTitle)?.let { return it }
            }

            // Обычный факт: summary по теме / query
            for (candidate in entityCandidates(query, topic)) {
                wikipediaSummary(candidate, excludeTitle)?.let { (title, text) ->
                    Log.i(TAG, "wiki ok title='$title' q='${query.take(40)}'")
                    return ok(call, text, "wikipedia", title)
                }
            }

            duckDuckGo(query)?.let { return ok(call, sanitizeForSpeech(it), "duckduckgo") }
            if (topic.isNotBlank()) {
                duckDuckGo(topic)?.let { return ok(call, sanitizeForSpeech(it), "duckduckgo") }
            }

            // Повтор локального факта по канону (если сеть упала на середине)
            localFact(query, topic)?.let { (title, text) ->
                return ok(call, text, "local", title)
            }

            Log.w(TAG, "no answer q='${query.take(60)}' topic='${topic.take(40)}'")
            ToolResult(
                call.requestId, definition.id, ToolResultStatus.FAILED,
                "Не нашёл краткий ответ по «$query». Уточни формулировку.",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "knowledge failed: ${t.message}")
            localFact(query, topic)?.let { (title, text) ->
                Log.i(TAG, "local after error title='$title'")
                return ok(call, text, "local", title)
            }
            ToolResult(
                call.requestId, definition.id,
                if (t.message?.contains("Unable to resolve", true) == true) ToolResultStatus.OFFLINE
                else ToolResultStatus.FAILED,
                "Поиск не удался: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    private fun answerFollowUp(
        call: ToolCall,
        query: String,
        topic: String,
        excludeTitle: String,
    ): ToolResult? {
        localFact(query, topic)?.let { (title, text) ->
            return ok(call, text, "local", title)
        }

        duckDuckGo(query)?.let { return ok(call, sanitizeForSpeech(it), "duckduckgo") }

        // «где он родился» — сначала предложение из биографии темы, не поиск «город Пушкин»
        if (topic.isNotBlank() && (
                fold(query).contains("родил") || fold(query).contains("рождени") ||
                    fold(query).startsWith("где ")
                )
        ) {
            sentenceFromTopic(topic, query)?.let { text ->
                return ok(call, text, "wikipedia", topic)
            }
        }

        val searches = aspectSearchQueries(query, topic)
        for (s in searches) {
            wikipediaSearchAnswer(s, excludeTitle, topic, preferAspect = true)?.let { (title, text) ->
                return ok(call, text, "wikipedia", title)
            }
        }
        if (topic.isNotBlank()) {
            sentenceFromTopic(topic, query)?.let { text ->
                return ok(call, text, "wikipedia", topic)
            }
        }
        if (topic.isNotBlank()) {
            wikipediaSearchAnswer(topic, excludeTitle, topic, preferAspect = false)?.let { (title, text) ->
                return ok(call, text, "wikipedia", title)
            }
        }
        return null
    }

    /** Из статьи по теме выбрать предложение под уточняющий вопрос (биография и т.п.). */
    private fun sentenceFromTopic(topic: String, question: String): String? {
        val longText = wikipediaLongExtract(topic) ?: wikipediaSummary(topic, excludeTitle = "")?.second
            ?: return null
        val q = fold(question)
        val hints = when {
            q.contains("родил") || q.contains("рождени") || q.startsWith("где ") ->
                listOf("родил", "родился", "родилась", "москв", "1799", "шестого июня", "26 мая")
            q.contains("умн") || q.contains("интеллект") || q.contains("мозг") ->
                listOf("интеллект", "мозг", "умн", "когнитив")
            q.contains("едят") || q.contains("пита") -> listOf("пита", "едят", "корм")
            else -> extractAspect(question).split(' ').filter { it.length > 3 }
        }
        if (hints.isEmpty()) return null
        val sentences = longText.split(Regex("""(?<=[.!?…])\s+"""))
        val hit = sentences.firstOrNull { s ->
            val f = fold(s)
            hints.any { h -> f.contains(h) } &&
                // не чужие биографии: предложение должно быть про тему или короткое
                (containsWord(f, fold(topic)) || f.contains("родил") || sentences.indexOf(s) < 3)
        } ?: return null
        return sanitizeForSpeech(hit)
    }

    private fun wikipediaLongExtract(topic: String): String? {
        val q = URLEncoder.encode(topic, Charsets.UTF_8.name())
        val searchUrl =
            "https://ru.wikipedia.org/w/api.php?action=opensearch&search=$q&limit=1&namespace=0&format=json"
        val arr = SimpleHttp.getJsonArrayOrNull(searchUrl, ttlMs = 60 * 60_000L) ?: return null
        if (arr.length() < 2) return null
        val titles = arr.optJSONArray(1) ?: return null
        if (titles.length() == 0) return null
        val title = titles.getString(0)
        val enc = URLEncoder.encode(title, Charsets.UTF_8.name())
        val url =
            "https://ru.wikipedia.org/w/api.php?action=query&titles=$enc" +
                "&prop=extracts&explaintext=1&exsentences=12&format=json"
        val json = SimpleHttp.getJsonOrNull(url, ttlMs = 60 * 60_000L) ?: return null
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val key = pages.keys().asSequence().firstOrNull() ?: return null
        val extract = pages.optJSONObject(key)?.optString("extract").orEmpty().trim()
        return extract.takeIf { it.length > 40 }
    }

    private fun ok(
        call: ToolCall,
        text: String,
        source: String,
        title: String? = null,
    ): ToolResult = ToolResult(
        call.requestId,
        definition.id,
        ToolResultStatus.SUCCESS,
        text,
        buildMap {
            put("source", source)
            put("chars", text.length)
            if (!title.isNullOrBlank()) {
                put("title", title)
                put("search", title)
            }
        },
    )

    private fun looksLikeQuestion(query: String): Boolean {
        val q = query.lowercase().trim()
        return q.startsWith("почему") || q.startsWith("зачем") || q.startsWith("как ") ||
            q.startsWith("где ") || q.startsWith("когда ") || q.startsWith("какой ") ||
            q.startsWith("какая ") || q.startsWith("откуда") || q.startsWith("сколько ") ||
            q.contains("?") ||
            q.contains(": почему") || q.contains(": зачем") || q.contains(": как ") ||
            q.contains(": где ")
    }

    private fun entityCandidates(query: String, topic: String): List<String> {
        val out = linkedSetOf<String>()
        if (topic.isNotBlank()) {
            out += canonicalizeTopic(topic)
            out += topic
        }
        var s = query.lowercase().trim()
        for (p in listOf(
            "кто такие ", "кто такой ", "кто такая ", "кто это ",
            "что такое ", "что это такое ", "что за ",
            "расскажи коротко про ", "расскажи кратко про ",
            "расскажи про ", "расскажи о ", "загугли ",
            "коротко про ", "кратко про ",
        )) {
            if (s.startsWith(p)) {
                s = s.removePrefix(p).trim()
                break
            }
        }
        val lemma = canonicalizeTopic(s)
        if (lemma.isNotBlank() && !looksLikeQuestion(s)) {
            out += lemma
            if (lemma != s) out += s
        }
        if (!looksLikeQuestion(query)) {
            val qCanon = canonicalizeTopic(query.trim())
            out += qCanon
            out += query.trim()
        }
        return out.filter { it.isNotBlank() }.distinct().take(6)
    }

    /** Частотные RU-формы → канон для Wikipedia (дельфинов → Дельфин). */
    private fun canonicalizeTopic(raw: String): String {
        val k = raw.lowercase().trim()
            .removePrefix("про ").removePrefix("о ")
            .trim()
        return LEMMA_TOPICS[k] ?: raw.trim().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(java.util.Locale("ru")) else it.toString()
        }
    }

    /** «почему … умные» + topic дельфины → поиски «дельфины интеллект» и т.п. */
    private fun aspectSearchQueries(query: String, topic: String): List<String> {
        val out = linkedSetOf<String>()
        val aspect = extractAspect(query)
        val t = topic.trim().ifBlank {
            Regex("""\(([^)]+)\)""").find(query)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        }
        if (t.isNotBlank() && aspect.isNotBlank()) {
            out += "$t $aspect"
            out += "$aspect $t"
            for (syn in aspectSynonyms(aspect)) {
                out += "$t $syn"
            }
        }
        // не искать голый «интеллект» без темы — уводит на общие статьи
        out += query.trim()
        return out.filter { it.isNotBlank() }.take(6)
    }

    private fun extractAspect(query: String): String {
        val q = query.lowercase()
        return when {
            q.contains("умн") || q.contains("интеллект") || q.contains("мозг") -> "интеллект"
            q.contains("едят") || q.contains("пита") || q.contains("еда") || q.contains("корм") -> "питание"
            q.contains("живут") || q.contains("обита") || q.contains("где ") || q.contains("ареал") -> "ареал"
            q.contains("родил") || q.contains("рождение") || q.contains("родился") || q.contains("родилась") -> "рождение"
            q.contains("размер") || q.contains("больш") || q.contains("длин") || q.contains("вес") -> "размер"
            q.contains("скорост") || q.contains("быстр") || q.contains("плава") -> "скорость"
            q.contains("размнож") || q.contains("детён") || q.contains("детеныш") || q.contains("рожда") -> "размножение"
            q.contains("истори") || q.contains("появил") || q.contains("происхожд") -> "история"
            q.contains("опасн") || q.contains("враг") || q.contains("хищник") -> "опасность"
            else -> {
                val stop = setOf(
                    "почему", "зачем", "как", "их", "они", "им", "это", "считают", "считается",
                    "такими", "такой", "самыми", "самых", "одних", "одними", "из", "самых",
                    "очень", "наиболее", "про", "о", "в", "на", "и", "а", "же", "животных",
                    "животные", "называют", "считают",
                )
                q.split(Regex("\\s+"))
                    .map { it.trim('?', '!', '.', ',', '…') }
                    .filter { it.length > 3 && it !in stop }
                    .take(2)
                    .joinToString(" ")
            }
        }
    }

    private fun aspectSynonyms(aspect: String): List<String> = when (aspect) {
        "рождение" -> listOf("рождение", "биография", "детство")
        "интеллект" -> listOf("интеллект", "умные", "мозг", "когнитивные")
        "питание" -> listOf("питание", "еда")
        "ареал" -> listOf("ареал", "обитание", "распространение")
        else -> listOf(aspect)
    }

    private fun wikipediaSummary(query: String, excludeTitle: String = ""): Pair<String, String>? {
        val wantAnimal = isLikelyAnimalQuery(query)
        // Один-два прямых REST summary — без тяжёлого opensearch на старте
        val canon = canonicalizeTopic(query)
        if (canon.isNotBlank()) {
            for (direct in listOf(canon, pluralAnimalTitle(canon)).distinct()) {
                if (sameTitle(direct, excludeTitle)) continue
                val pair = fetchSummary(direct) ?: continue
                if (wantAnimal && looksLikePersonBio(pair.first, pair.second) &&
                    !looksLikeAnimalArticle(pair.second)
                ) {
                    continue
                }
                if (!wantAnimal || looksLikeAnimalArticle(pair.second) ||
                    !looksLikePersonBio(pair.first, pair.second)
                ) {
                    return pair
                }
            }
        }

        val q = URLEncoder.encode(query, Charsets.UTF_8.name())
        val searchUrl =
            "https://ru.wikipedia.org/w/api.php?action=opensearch&search=$q&limit=5&namespace=0&format=json"
        val arr = SimpleHttp.getJsonArrayOrNull(searchUrl, ttlMs = 60 * 60_000L) ?: return null
        if (arr.length() < 2) return null
        val titles = arr.optJSONArray(1) ?: return null
        if (titles.length() == 0) return null
        var animalFallback: Pair<String, String>? = null
        val limit = minOf(titles.length(), 4)
        for (i in 0 until limit) {
            val title = titles.getString(i)
            if (sameTitle(title, excludeTitle)) continue
            if (wantAnimal && title.contains(',') && !title.lowercase().contains("вид")) continue
            val pair = fetchSummary(title) ?: continue
            val extract = pair.second
            if (wantAnimal && looksLikePersonBio(title, extract) && !looksLikeAnimalArticle(extract)) {
                continue
            }
            if (wantAnimal && looksLikeAnimalArticle(extract)) {
                return pair
            }
            if (animalFallback == null && !(wantAnimal && looksLikePersonBio(title, extract))) {
                animalFallback = pair
            }
            if (!wantAnimal) return pair
        }
        return animalFallback
    }

    /** Дельфин → ещё пробуем статью «Дельфины» (часто так названа вики). */
    private fun pluralAnimalTitle(canon: String): String {
        val c = canon.trim()
        if (c.isEmpty()) return c
        val plural = when {
            c.endsWith("а") || c.endsWith("я") -> c.dropLast(1) + "и"
            c.endsWith("ь") -> c.dropLast(1) + "и"
            else -> c + "ы"
        }
        return plural.replaceFirstChar { it.titlecase(java.util.Locale("ru")) }
    }

    private fun isLikelyAnimalQuery(query: String): Boolean {
        val f = fold(query)
        if (LEMMA_TOPICS.keys.any { f == it || f.contains(it) }) return true
        if (LEMMA_TOPICS.values.any { fold(it) == f || f.contains(fold(it)) }) return true
        return listOf(
            "животн", "звер", "птиц", "рыб", "кит", "дельфин", "кошк", "собак",
            "волк", "лис", "медвед", "слон", "пингвин", "акул",
        ).any { f.contains(it) }
    }

    private fun looksLikePersonBio(title: String, extract: String): Boolean {
        val e = fold(extract)
        val t = fold(title)
        if (t.contains(",") && !e.contains("млекопитающ") && !e.contains("животн") &&
            !e.contains("вид ") && !e.contains("семейнств")
        ) {
            return true
        }
        val hints = listOf(
            "поэт", "писатель", "актёр", "актер", "политик", "учёный", "ученый",
            "музыкант", "певец", "журналист", "активист", "перформер",
            "родился", "родилась", "советский", "российский писатель", "биографи",
        )
        if (hints.any { e.contains(it) }) return true
        return false
    }

    private fun looksLikeAnimalArticle(extract: String): Boolean {
        val e = fold(extract)
        return listOf(
            "млекопитающ", "животн", "вид ", "семейнств", "отряд", "китообразн",
            "птиц", "хищн", "ареал", "питают",
        ).any { e.contains(it) }
    }

    /**
     * Полнотекстовый поиск: выбираем статью, отличную от exclude и ближе к аспекту.
     */
    private fun wikipediaSearchAnswer(
        search: String,
        excludeTitle: String,
        topic: String,
        preferAspect: Boolean,
    ): Pair<String, String>? {
        val q = URLEncoder.encode(search, Charsets.UTF_8.name())
        val url =
            "https://ru.wikipedia.org/w/api.php?action=query&generator=search" +
                "&gsrsearch=$q&gsrlimit=5&prop=extracts&exintro=1&explaintext=1" +
                "&exsentences=4&format=json"
        val json = SimpleHttp.getJsonOrNull(url, ttlMs = 60 * 60_000L) ?: return null
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys().asSequence().toList()
        val topicFold = fold(topic)
        val scored = keys.mapNotNull { key ->
            val page = pages.optJSONObject(key) ?: return@mapNotNull null
            val title = page.optString("title").trim()
            val extract = page.optString("extract").trim()
            if (title.isBlank() || extract.length < 40) return@mapNotNull null
            if (sameTitle(title, excludeTitle)) return@mapNotNull null
            val score = scorePage(title, extract, search, topicFold, preferAspect)
            Triple(score, title, extract)
        }.sortedByDescending { it.first }
        val best = scored.firstOrNull() ?: return null
        if (best.first < 1) return null
        val text = sanitizeForSpeech(formatWikiExtract(best.second, best.third))
        return best.second to text
    }

    private fun containsWord(hay: String, needle: String): Boolean {
        if (needle.isBlank() || hay.isBlank()) return false
        // целые слова: «пушкин» ≠ «пушкиния»
        return Regex(
            """(?<![а-яёa-z0-9])${Regex.escape(needle)}(?![а-яёa-z0-9])""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(hay)
    }

    private fun scorePage(
        title: String,
        extract: String,
        search: String,
        topicFold: String,
        preferAspect: Boolean,
    ): Int {
        val t = fold(title)
        val e = fold(extract)
        val words = fold(search).split(' ').filter { it.length > 2 }
        var score = 0
        for (w in words) {
            if (containsWord(t, w)) score += 3
            if (containsWord(e, w)) score += 1
        }
        if (topicFold.isNotBlank()) {
            val topicParts = topicFold.split(' ').filter { it.length > 2 }
            val titleHas = topicParts.any { containsWord(t, it) }
            val extractHas = topicParts.any { containsWord(e, it) }
            if (titleHas) score += 6
            if (extractHas) score += 3
            // без темы в заголовке/тексте — почти наверняка мимо (Пушкиния и т.п.)
            if (!titleHas && !extractHas) score -= 12
        }
        if (preferAspect) {
            for (hint in listOf(
                "интеллект", "умн", "мозг", "когнитив", "поведен", "питан", "ареал",
                "родил", "рождени", "москв", "биограф", "китообразн",
            )) {
                if (t.contains(hint)) score += 5
                if (e.contains(hint)) score += 2
            }
        }
        return score
    }

    private fun fetchSummary(title: String): Pair<String, String>? {
        val enc = URLEncoder.encode(title.replace(' ', '_'), Charsets.UTF_8.name())
        val summaryUrl = "https://ru.wikipedia.org/api/rest_v1/page/summary/$enc"
        val json = SimpleHttp.getJsonOrNull(summaryUrl, ttlMs = 60 * 60_000L) ?: return null
        val extract = json.optString("extract").trim()
        if (extract.isBlank()) return null
        val resolved = json.optString("title").ifBlank { title }
        val text = sanitizeForSpeech(formatWikiExtract(resolved, extract))
        return resolved to text
    }

    /**
     * Встроенные короткие факты (офлайн / при 429). Ключ — канон или lemma.
     */
    private fun localFact(query: String, topic: String): Pair<String, String>? {
        val keys = buildList {
            add(canonicalizeTopic(query))
            add(canonicalizeTopic(topic))
            val stripped = query.lowercase()
                .removePrefix("расскажи коротко про ").removePrefix("расскажи кратко про ")
                .removePrefix("расскажи про ").removePrefix("про ")
                .trim()
            add(canonicalizeTopic(stripped))
            if (topic.isNotBlank()) add(canonicalizeTopic(topic))
        }.map { fold(it) }.filter { it.isNotBlank() }.distinct()

        val aspect = extractAspect(query)
        for (k in keys) {
            val canonKey = LEMMA_TOPICS[k] ?: k.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(java.util.Locale("ru")) else it.toString()
            }
            val title = LEMMA_TOPICS[fold(canonKey)] ?: canonKey
            if (aspect.isNotBlank()) {
                LOCAL_ASPECTS["${fold(title)}|$aspect"]?.let { return title to it }
                LOCAL_ASPECTS["$k|$aspect"]?.let { return title to it }
            }
            LOCAL_FACTS[fold(title)]?.let { return title to it }
            LOCAL_FACTS[k]?.let { return title to it }
        }
        // Follow-up «почему они умные» + topic дельфины
        if (topic.isNotBlank() && aspect.isNotBlank()) {
            val tCanon = canonicalizeTopic(topic)
            LOCAL_ASPECTS["${fold(tCanon)}|$aspect"]?.let { return tCanon to it }
        }
        return null
    }

    /**
     * Без дубля «Дельфины. Дельфины — …»: если extract уже начинается с названия — только extract.
     */
    internal fun formatWikiExtract(title: String, extract: String): String {
        val e = extract.replace('\u00a0', ' ').trim()
        if (e.isBlank()) return title
        // У Wikipedia lead уже «Имя — …»; не клеим заголовок ещё раз
        if (e.contains('—') || e.contains('–')) return e
        val tFold = fold(title)
        val eFold = fold(e)
        if (tFold.isNotBlank() && eFold.startsWith(tFold)) return e
        val shortTitle = title.substringBefore(',').trim()
        if (shortTitle.isNotBlank() && eFold.startsWith(fold(shortTitle))) return e
        // «Пушкин, Александр…» vs extract «Александр Сергеевич Пушкин —»
        val afterComma = title.substringAfter(',', "").trim()
        if (afterComma.isNotBlank() && eFold.startsWith(fold(afterComma))) return e
        return "$title — $e"
    }

    private fun sameTitle(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        return fold(a) == fold(b)
    }

    private fun fold(s: String): String =
        s.lowercase()
            .replace('\u00a0', ' ')
            .replace("́", "")
            .replace("\u0301", "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun duckDuckGo(query: String): String? {
        val q = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1"
        val json = SimpleHttp.getJsonOrNull(url, ttlMs = 60 * 60_000L) ?: return null
        val abstract = json.optString("AbstractText").trim()
        if (abstract.isNotBlank()) return abstract
        val answer = json.optString("Answer").trim()
        if (answer.isNotBlank()) return answer
        return null
    }

    internal fun sanitizeForSpeech(raw: String): String {
        var t = raw
            .replace('\u00a0', ' ')
            .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), " ")
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .replace(Regex("""https?://\S+"""), " ")
            .replace(Regex("""www\.\S+"""), " ")
            .replace(Regex("""\[\d+]"""), " ")
            .replace(Regex("""\(\s*источник[^)]*\)""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""[#*_`>|]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        // «Дельфи́ны» → читаемее без ударений для TTS
        t = t.replace("́", "").replace("\u0301", "")
        if (t.length <= MAX_SPEECH_CHARS) return t
        val window = t.take(MAX_SPEECH_CHARS)
        val sentenceEnd = window.lastIndexOfAny(charArrayOf('.', '!', '?', '…'))
        if (sentenceEnd >= MIN_SPEECH_CHARS) {
            return window.take(sentenceEnd + 1).trim()
        }
        val space = window.lastIndexOf(' ')
        return if (space >= MIN_SPEECH_CHARS) {
            window.take(space).trimEnd(',', ';', ':') + "…"
        } else {
            window.trimEnd() + "…"
        }
    }

    companion object {
        private const val TAG = "KnowledgeTool"
        private const val MAX_SPEECH_CHARS = 280
        private const val MIN_SPEECH_CHARS = 80

        private val LEMMA_TOPICS = mapOf(
            "дельфинов" to "Дельфин",
            "дельфины" to "Дельфин",
            "дельфина" to "Дельфин",
            "дельфин" to "Дельфин",
            "кошек" to "Кошка",
            "кошки" to "Кошка",
            "кошка" to "Кошка",
            "котов" to "Кот",
            "собак" to "Собака",
            "собаки" to "Собака",
            "собака" to "Собака",
            "волков" to "Волк",
            "волки" to "Волк",
            "волк" to "Волк",
            "лисиц" to "Лисица",
            "лисы" to "Лисица",
            "медведей" to "Медведь",
            "медведи" to "Медведь",
            "слонов" to "Слон",
            "слоны" to "Слон",
            "птиц" to "Птицы",
            "китов" to "Кит",
            "киты" to "Кит",
        )

        /** Краткие офлайн-факты (ключ — fold канона). */
        private val LOCAL_FACTS = mapOf(
            "дельфин" to "Дельфины — водные млекопитающие из отряда китообразных. " +
                "Они живут в морях и океанах, дышат воздухом и славятся высоким интеллектом.",
            "кошка" to "Кошка — домашнее хищное млекопитающее семейства кошачьих. " +
                "Один из самых популярных питомцев человека.",
            "кот" to "Кот — самец домашней кошки. Кошки — мелкие хищные млекопитающие, " +
                "живущие рядом с людьми тысячелетиями.",
            "собака" to "Собака — домашнее животное семейства псовых, потомок волка. " +
                "Близкий компаньон человека.",
            "волк" to "Волк — крупный хищник семейства псовых. Живёт стаями в лесах и тундре.",
            "слон" to "Слон — крупнейшее наземное млекопитающее. Известен хоботом и высоким интеллектом.",
            "кит" to "Киты — крупные морские млекопитающие отряда китообразных. Дышат воздухом через дыхало.",
            "медведь" to "Медведь — крупный хищник семейства медвежьих. Встречается в лесах Евразии и Америки.",
            "лисица" to "Лисица — небольшой хищник семейства псовых с пушистым хвостом. Живёт в лесах и полях.",
            "птицы" to "Птицы — теплокровные позвоночные с перьями и крыльями. Большинство умеет летать.",
        )

        /** Аспектные факты: «канон|аспект». */
        private val LOCAL_ASPECTS = mapOf(
            "дельфин|интеллект" to "Дельфины считают одними из самых умных животных: у них сложный мозг, " +
                "они учатся, играют и общаются звуковыми сигналами.",
            "дельфин|питание" to "Дельфины в основном питаются рыбой и кальмарами, охотясь группами.",
            "дельфин|ареал" to "Дельфины обитают почти во всех океанах и морях; есть и пресноводные речные виды.",
            "кошка|интеллект" to "Кошки хорошо ориентируются в пространстве и запоминают маршруты, " +
                "хотя обучаются иначе, чем собаки.",
            "собака|интеллект" to "Собаки хорошо понимают команды и эмоции человека, за что их ценят как компаньонов.",
        )
    }
}
