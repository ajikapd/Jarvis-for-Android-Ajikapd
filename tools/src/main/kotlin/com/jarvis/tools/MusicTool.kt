package com.jarvis.tools

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import com.jarvis.core.common.Tool
import com.jarvis.core.common.model.OnlineRequirement
import com.jarvis.core.common.model.ParameterType
import com.jarvis.core.common.model.RiskLevel
import com.jarvis.core.common.model.ToolCall
import com.jarvis.core.common.model.ToolDefinition
import com.jarvis.core.common.model.ToolParameter
import com.jarvis.core.common.model.ToolResult
import com.jarvis.core.common.model.ToolResultStatus
import com.jarvis.core.nlp.MusicQueryMatcher
import com.jarvis.screencontrol.JarvisNotificationListenerService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Музыка через Кион / MediaSession / PLAY_FROM_SEARCH (без встроенного плеера).
 * scope: track | artist | album | favorites | downloaded
 */
class MusicTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        id = "music",
        name = "Музыка",
        description = "Включить трек/артиста/альбом (Кион или любой плеер), пауза, дальше, статус",
        parameters = listOf(
            ToolParameter(
                "action",
                "play, pause, resume, next, previous или status",
                ParameterType.ENUM,
                required = false,
                allowedValues = listOf("play", "pause", "resume", "next", "previous", "status"),
            ),
            ToolParameter("query", "Трек, артист или альбом", ParameterType.STRING, false),
            ToolParameter(
                "scope",
                "track, artist, album, favorites, downloaded",
                ParameterType.ENUM,
                required = false,
                allowedValues = listOf("track", "artist", "album", "favorites", "downloaded"),
            ),
        ),
        riskLevel = RiskLevel.SAFE,
        onlineRequirement = OnlineRequirement.LOCAL_PREFERRED,
    )

    override fun execute(call: ToolCall): ToolResult {
        val action = call.arguments["action"]?.toString()?.lowercase()
            ?: if (call.arguments["query"] != null) "play" else "status"
        val query = call.arguments["query"]?.toString()?.trim().orEmpty()
        val scope = call.arguments["scope"]?.toString()?.lowercase().orEmpty()
            .ifBlank { if (query.isBlank()) "favorites" else "track" }
        return try {
            when (action) {
                "play" -> play(call.requestId, query, scope)
                "pause" -> transport(call.requestId, "pause") { it.pause() }
                "resume", "unpause" -> transport(call.requestId, "resume") { it.play() }
                "next" -> transport(call.requestId, "next") { it.skipToNext() }
                "previous", "prev" -> transport(call.requestId, "previous") { it.skipToPrevious() }
                "status" -> status(call.requestId)
                else -> ToolResult(
                    call.requestId, definition.id, ToolResultStatus.AMBIGUOUS,
                    "Скажи: включи [трек/артиста/альбом], избранное, скачанное, пауза, продолжить, дальше.",
                )
            }
        } catch (t: Throwable) {
            ToolResult(call.requestId, definition.id, ToolResultStatus.FAILED, t.message)
        }
    }

    private fun play(requestId: String, query: String, scope: String): ToolResult {
        // 1) Кион — preferred (фон, избранное/скачанное)
        if (isInstalled(PKG_KION)) {
            val kion = playViaKion(query, scope)
            if (kion != null && !kion.startsWith("ERR:")) {
                rememberPackage(PKG_KION)
                return ToolResult(
                    requestId, definition.id, ToolResultStatus.SUCCESS,
                    "Ок: $kion.",
                    mapOf("query" to query, "scope" to scope, "package" to PKG_KION, "track" to kion),
                )
            }
            Log.w(TAG, "Kion play weak: $kion — trying generic")
        }

        // 2) Избранное/скачанное без Киона — честный отказ
        if (scope in setOf("favorites", "downloaded") && query.isBlank()) {
            return ToolResult(
                requestId, definition.id, ToolResultStatus.FAILED,
                if (isInstalled(PKG_KION)) {
                    "Не удалось открыть $scope в Кионе. Открой приложение один раз и повтори."
                } else {
                    "Для избранного/скачанного нужен Кион. Или скажи название трека."
                },
            )
        }

        val searchQ = query.ifBlank { "music" }

        // 3) Активная MediaSession любого плеера — playFromSearch
        val sessionHit = playViaActiveSessionSearch(searchQ)
        if (sessionHit != null) {
            return ToolResult(
                requestId, definition.id, ToolResultStatus.SUCCESS,
                "Включаю: $sessionHit.",
                mapOf("query" to query, "scope" to scope, "track" to sessionHit, "via" to "session"),
            )
        }

        // 4) Системный INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
        val intentHit = playViaSystemSearchIntent(searchQ)
        return if (intentHit) {
            ToolResult(
                requestId, definition.id, ToolResultStatus.SUCCESS,
                "Ищу и включаю «$searchQ» в музыкальном приложении.",
                mapOf("query" to query, "scope" to scope, "via" to "intent"),
            )
        } else {
            ToolResult(
                requestId, definition.id, ToolResultStatus.FAILED,
                "Не нашёл плеер. Установи Кион/YouTube Music/Spotify или открой плеер один раз.",
            )
        }
    }

    /** Кион MediaBrowser; null / ERR:… при неудаче. */
    private fun playViaKion(query: String, scope: String): String? {
        pauseOtherPlayers(except = PKG_KION)
        return withBrowser { browser, ctrl ->
            when (scope) {
                "favorites" -> playChildren(browser, ctrl, "__TRACKS__", preferShuffle = true)
                "downloaded" -> playDownloaded(browser, ctrl)
                "album" -> playSearchAs(browser, ctrl, query, prefer = Prefer.ALBUM)
                "artist" -> playSearchAs(browser, ctrl, query, prefer = Prefer.ARTIST)
                else -> {
                    if (query.isBlank()) {
                        playChildren(browser, ctrl, "__TRACKS__", preferShuffle = true)
                    } else {
                        playTrackQuery(browser, ctrl, query)
                    }
                }
            }
        }
    }

    private fun playViaActiveSessionSearch(query: String): String? {
        val ctrl = activeController() ?: return null
        val prev = ctrl.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        runCatching {
            ctrl.transportControls.playFromSearch(query, searchExtras(query))
        }.onFailure {
            Log.w(TAG, "session playFromSearch failed: ${it.message}")
            return null
        }
        // дождаться смены метаданных или PLAYING
        val deadline = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            val st = ctrl.playbackState?.state
            val title = ctrl.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            if (st == PlaybackState.STATE_PLAYING ||
                (title.isNotBlank() && !title.equals(prev, ignoreCase = true))
            ) {
                rememberPackage(ctrl.packageName)
                val artist = ctrl.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                return if (artist.isNotBlank() && title.isNotBlank()) "$artist — $title"
                else title.ifBlank { query }
            }
        }
        // команда ушла — считаем успехом с query
        rememberPackage(ctrl.packageName)
        return query
    }

    private fun playViaSystemSearchIntent(query: String): Boolean {
        val preferred = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_PLAYER, null)
            ?.takeIf { isInstalled(it) }
            ?: listOf(PKG_KION, PKG_YT_MUSIC, PKG_SPOTIFY, PKG_YANDEX)
                .firstOrNull { isInstalled(it) }

        fun buildIntent(pkg: String?): android.content.Intent =
            android.content.Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!pkg.isNullOrBlank()) setPackage(pkg)
            }

        val attempts = listOfNotNull(preferred, null).distinct()
        for (pkg in attempts) {
            val ok = runCatching {
                context.startActivity(buildIntent(pkg))
                true
            }.getOrDefault(false)
            if (ok) {
                Log.i(TAG, "PLAY_FROM_SEARCH intent pkg=$pkg q=$query")
                if (!pkg.isNullOrBlank()) rememberPackage(pkg)
                return true
            }
        }
        return false
    }

    private enum class Prefer { TRACK, ARTIST, ALBUM }

    private data class BrowseResult(
        val items: List<MediaBrowserCompat.MediaItem> = emptyList(),
        val error: String? = null,
    )

    private fun withBrowser(block: (MediaBrowserCompat, MediaControllerCompat) -> String?): String? {
        val result = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        val browserRef = AtomicReference<MediaBrowserCompat?>(null)

        fun finish(value: String?) {
            if (finished.compareAndSet(false, true)) {
                result.set(value)
                done.countDown()
            }
        }

        val rootHints = Bundle().apply {
            // Подсказки в стиле Android Auto — Кион отдаёт playback по этому каналу
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 2)
        }

        main.post {
            lateinit var browser: MediaBrowserCompat
            browser = MediaBrowserCompat(
                context,
                ComponentName(PKG_KION, SVC_KION),
                object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() {
                        Log.i(TAG, "connected root=${browser.root}")
                        val ctrl = runCatching {
                            MediaControllerCompat(context, browser.sessionToken)
                        }.getOrNull()
                        if (ctrl == null) {
                            finish("ERR:Нет MediaSession Киона.")
                            return
                        }
                        Thread({
                            val out = runCatching { block(browser, ctrl) }.getOrElse {
                                Log.e(TAG, "play block failed", it)
                                "ERR:${it.message ?: "ошибка плеера"}"
                            }
                            main.post {
                                runCatching { browser.disconnect() }
                                finish(out)
                            }
                        }, "jarvis-kion-play").start()
                    }

                    override fun onConnectionFailed() {
                        Log.w(TAG, "connection failed")
                        finish("ERR:Кион не отвечает. Открой приложение один раз и повтори.")
                    }

                    override fun onConnectionSuspended() {
                        Log.w(TAG, "connection suspended")
                    }
                },
                rootHints,
            )
            browserRef.set(browser)
            runCatching { browser.connect() }.onFailure {
                finish("ERR:${it.message}")
            }
        }

        done.await(45, TimeUnit.SECONDS)
        runCatching { browserRef.get()?.disconnect() }
        return result.get()
    }

    private fun playTrackQuery(
        browser: MediaBrowserCompat,
        ctrl: MediaControllerCompat,
        query: String,
    ): String? {
        // 1) Локальная библиотека / избранное — лучший fuzzy-match, не first-hit
        val library = loadChildren(browser, "__TRACKS__", timeoutMs = 5000).items.filter { it.isPlayable }
        pickBestItem(library, query, MusicQueryMatcher.MIN_ACCEPT)?.let { scored ->
            Log.i(TAG, "library hit score=${scored.score} «${scored.label}» for «$query»")
            return playMediaId(ctrl, scored.item.mediaId, scored.item.description, scored.label)
        }
        // 2) Скачанные
        val dl = loadDownloadedTracks(browser).filter { it.isPlayable }
        pickBestItem(dl, query, MusicQueryMatcher.MIN_ACCEPT)?.let { scored ->
            Log.i(TAG, "downloaded hit score=${scored.score} «${scored.label}» for «$query»")
            return playMediaId(ctrl, scored.item.mediaId, scored.item.description, scored.label)
        }
        // 3) Каталожный поиск → всегда __TRACKS__:<id>, никогда __SEARCH__:
        return playSearchAs(browser, ctrl, query, Prefer.TRACK)
    }

    private fun playSearchAs(
        browser: MediaBrowserCompat,
        ctrl: MediaControllerCompat,
        query: String,
        prefer: Prefer,
    ): String? {
        if (query.isBlank()) return "ERR:Не понял, что включить."
        val variants = MusicQueryMatcher.queryVariants(query)
        Log.i(TAG, "search variants=$variants prefer=$prefer")

        val pooled = LinkedHashMap<String, MediaBrowserCompat.MediaItem>()
        var lastError: String? = null
        for (v in variants) {
            val found = search(browser, v, timeoutMs = 4500)
            if (found.error != null) lastError = found.error
            for (item in found.items) {
                val key = item.mediaId ?: "${item.description?.title}|${item.description?.subtitle}"
                pooled.putIfAbsent(key, item)
            }
        }
        if (pooled.isEmpty()) {
            return "ERR:" + (lastError ?: "Ничего не нашёл по «$query».")
        }
        val items = pooled.values.toList()

        val item = when (prefer) {
            Prefer.ARTIST -> {
                val artists = items.filter {
                    it.isBrowsable || it.mediaId?.contains("ARTIST", true) == true
                }
                pickBestItem(artists.ifEmpty { items }, query, MusicQueryMatcher.MIN_SEARCH)?.item
                    ?: artists.firstOrNull()
                    ?: items.first()
            }
            Prefer.ALBUM -> {
                val albums = items.filter {
                    it.isBrowsable || it.mediaId?.contains("ALBUM", true) == true
                }
                pickBestItem(albums.ifEmpty { items }, query, MusicQueryMatcher.MIN_SEARCH)?.item
                    ?: albums.firstOrNull()
                    ?: items.first()
            }
            Prefer.TRACK -> {
                val playable = items.filter { it.isPlayable }
                val scored = pickBestItem(playable.ifEmpty { items }, query, MusicQueryMatcher.MIN_SEARCH)
                if (scored == null) {
                    val weak = pickBestItem(playable.ifEmpty { items }, query, minScore = 1)
                    return if (weak != null) {
                        "ERR:По «$query» близкого трека нет. Похоже: «${weak.label}» — уточни название."
                    } else {
                        "ERR:Ничего похожего на «$query»."
                    }
                }
                Log.i(TAG, "search hit score=${scored.score} «${scored.label}»")
                scored.item
            }
        }

        // Если browsable (артист/альбом) — лучший playable child под запрос
        if (item.isBrowsable && !item.mediaId.isNullOrBlank()) {
            val kids = loadChildren(browser, item.mediaId!!, timeoutMs = 5000).items.filter { it.isPlayable }
            val child = pickBestItem(kids, query, MusicQueryMatcher.MIN_SEARCH)
                ?: kids.firstOrNull()?.let {
                    MusicQueryMatcher.Scored(it, 0, itemLabel(it))
                }
            if (child != null) {
                return playResolved(ctrl, child.item, child.label, query, preferSearch = false)
            }
        }

        // Каталожный search: mediaId у Киона часто врёт (FATHER→чужой id).
        // Сначала playFromSearch по точному «artist title», потом mediaId.
        return playResolved(ctrl, item, itemLabel(item).ifBlank { query }, query, preferSearch = true)
    }

    private fun playResolved(
        ctrl: MediaControllerCompat,
        item: MediaBrowserCompat.MediaItem,
        label: String,
        originalQuery: String,
        preferSearch: Boolean,
    ): String? {
        val title = item.description?.title?.toString().orEmpty()
        val artist = item.description?.subtitle?.toString().orEmpty()
        val precise = when {
            title.isNotBlank() && artist.isNotBlank() -> "$title $artist"
            title.isNotBlank() -> title
            else -> originalQuery
        }
        if (preferSearch && precise.isNotBlank()) {
            Log.i(TAG, "prefer playFromSearch «$precise» (label=$label)")
            val viaSearch = playFromSearchQuery(ctrl, precise, expectedTitle = title.ifBlank { precise })
            if (viaSearch != null && !viaSearch.startsWith("ERR:")) return viaSearch
            Log.w(TAG, "playFromSearch weak — fallback mediaId")
        }
        val trackId = toPlayableTrackId(item)
            ?: return if (preferSearch) {
                playFromSearchQuery(ctrl, originalQuery, expectedTitle = title.ifBlank { originalQuery })
            } else {
                "ERR:Нашёл «$title», но не могу включить в фоне."
            }
        return playMediaId(ctrl, trackId, item.description, label.ifBlank { precise })
    }

    private fun playFromSearchQuery(
        ctrl: MediaControllerCompat,
        searchQuery: String,
        expectedTitle: String,
    ): String? {
        val prevTitle = ctrl.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty()
        Log.i(TAG, "playFromSearch «$searchQuery» expected='$expectedTitle' prev='$prevTitle'")
        runCatching { ctrl.transportControls.stop() }
        Thread.sleep(250)
        runCatching { ctrl.transportControls.playFromSearch(searchQuery, searchExtras(searchQuery)) }
        runCatching { ctrl.transportControls.play() }

        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            val st = ctrl.playbackState?.state
            val err = ctrl.playbackState?.errorMessage?.toString().orEmpty()
            if (err.isNotBlank() && (
                    err.contains("подписк", true) || err.contains("Android Auto", true)
                    )
            ) {
                return "ERR:$err"
            }
            val metaTitle = ctrl.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty()
            val metaArtist = ctrl.metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty()
            val playing = st == PlaybackStateCompat.STATE_PLAYING ||
                st == PlaybackStateCompat.STATE_BUFFERING
            val matched = expectedTitle.isBlank() ||
                MusicQueryMatcher.score(expectedTitle, metaTitle, metaArtist) >= 55 ||
                MusicQueryMatcher.score(searchQuery, metaTitle, metaArtist) >= 55
            val switched = metaTitle.isNotBlank() && metaTitle != prevTitle
            if (playing && (matched || switched)) {
                return when {
                    metaTitle.isNotBlank() && metaArtist.isNotBlank() -> "$metaArtist — $metaTitle"
                    metaTitle.isNotBlank() -> metaTitle
                    else -> expectedTitle.ifBlank { searchQuery }
                }
            }
            if (st == PlaybackStateCompat.STATE_PAUSED || st == PlaybackStateCompat.STATE_STOPPED) {
                runCatching { ctrl.transportControls.play() }
            }
            Thread.sleep(250)
        }
        val metaTitle = ctrl.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty()
        return "ERR:Не включил «$searchQuery». Сейчас: «${metaTitle.ifBlank { "тишина" }}»."
    }

    private fun pickBestItem(
        items: List<MediaBrowserCompat.MediaItem>,
        query: String,
        minScore: Int,
    ): MusicQueryMatcher.Scored<MediaBrowserCompat.MediaItem>? =
        MusicQueryMatcher.bestMatch(
            items,
            query,
            labelOf = { item ->
                val title = item.description?.title?.toString().orEmpty()
                val sub = item.description?.subtitle?.toString().orEmpty()
                title to sub
            },
            minScore = minScore,
        )

    private fun itemLabel(item: MediaBrowserCompat.MediaItem): String {
        val title = item.description?.title?.toString().orEmpty()
        val sub = item.description?.subtitle?.toString().orEmpty()
        return when {
            title.isNotBlank() && sub.isNotBlank() -> "$sub — $title"
            title.isNotBlank() -> title
            else -> sub
        }
    }

    private fun playDownloaded(browser: MediaBrowserCompat, ctrl: MediaControllerCompat): String? {
        val tracks = loadDownloadedTracks(browser)
        val playable = tracks.filter { it.isPlayable }
        if (playable.isEmpty()) {
            return "ERR:Нет скачанных треков. Скачай музыку в Кионе (Избранное → Скачанные)."
        }
        val item = playable.random()
        return playMediaId(ctrl, item.mediaId, item.description, item.description?.title?.toString() ?: "скачанное")
    }

    private fun playChildren(
        browser: MediaBrowserCompat,
        ctrl: MediaControllerCompat,
        parentId: String,
        preferShuffle: Boolean,
    ): String? {
        val kids = loadChildren(browser, parentId, timeoutMs = 5000).items.filter { it.isPlayable }
        if (kids.isEmpty()) {
            return "ERR:В «$parentId» пусто. Добавь треки в избранное Киона."
        }
        val item = if (preferShuffle) kids.random() else kids.first()
        // Shuffle mode if available
        if (preferShuffle) {
            runCatching {
                ctrl.transportControls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL)
            }
        }
        return playMediaId(ctrl, item.mediaId, item.description, item.description?.title?.toString() ?: "избранное")
    }

    private fun loadDownloadedTracks(browser: MediaBrowserCompat): List<MediaBrowserCompat.MediaItem> {
        val root = loadChildren(browser, "__DOWNLOADED__", timeoutMs = 4000).items
        if (root.any { it.isPlayable }) return root
        // иногда это папка → заходим глубже
        val out = ArrayList<MediaBrowserCompat.MediaItem>()
        for (folder in root.filter { it.isBrowsable && !it.mediaId.isNullOrBlank() }.take(4)) {
            out += loadChildren(browser, folder.mediaId!!, timeoutMs = 4000).items
        }
        // запасной путь: favorites downloaded folder name variants
        if (out.isEmpty()) {
            out += loadChildren(browser, "__FAVORITE__", timeoutMs = 3000).items
                .filter { it.mediaId?.contains("DOWN", true) == true || it.description?.title?.contains("кач", true) == true }
                .flatMap { f ->
                    if (f.mediaId.isNullOrBlank()) emptyList()
                    else loadChildren(browser, f.mediaId!!, timeoutMs = 3000).items
                }
        }
        return out
    }

    private fun toPlayableTrackId(item: MediaBrowserCompat.MediaItem): String? {
        val id = item.mediaId ?: return null
        when {
            id.startsWith("__TRACKS__:") -> return id
            id.startsWith("__SEARCH__:") -> {
                val n = id.substringAfter("__SEARCH__:")
                if (n.isNotBlank()) return "__TRACKS__:$n"
            }
        }
        val uri = item.description?.mediaUri?.toString().orEmpty()
        if (uri.isNotBlank() && uri.all { it.isDigit() }) return "__TRACKS__:$uri"
        if (id.all { it.isDigit() }) return "__TRACKS__:$id"
        // уже playable id другого формата
        if (item.isPlayable) return id
        return null
    }

    private fun playMediaId(
        ctrl: MediaControllerCompat,
        mediaId: String?,
        description: MediaDescriptionCompat?,
        fallbackName: String,
    ): String? {
        if (mediaId.isNullOrBlank()) return null
        val expectedTitle = description?.title?.toString().orEmpty()
        val prevTitle = ctrl.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty()
        Log.i(TAG, "playFromMediaId $mediaId expected='$expectedTitle' prev='$prevTitle'")

        // Жёсткий сброс — pause иногда не даёт Киону сменить mediaId
        runCatching { ctrl.transportControls.stop() }
        Thread.sleep(300)
        runCatching { ctrl.transportControls.playFromMediaId(mediaId, null) }
        Thread.sleep(200)
        runCatching { ctrl.transportControls.play() }

        val deadline = System.currentTimeMillis() + 10000
        var sawSwitch = false
        while (System.currentTimeMillis() < deadline) {
            val st = ctrl.playbackState?.state
            val err = ctrl.playbackState?.errorMessage?.toString().orEmpty()
            if (err.isNotBlank() && (
                    err.contains("подписк", true) || err.contains("Android Auto", true)
                    )
            ) {
                return "ERR:$err"
            }
            val metaTitle = ctrl.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty()
            val titleSwitched = metaTitle.isNotBlank() &&
                prevTitle.isNotBlank() &&
                metaTitle != prevTitle
            val titleMatchesExpected = expectedTitle.isNotBlank() && metaTitle.isNotBlank() &&
                MusicQueryMatcher.score(expectedTitle, metaTitle) >= 70
            // Если до этого ничего не играло — любое новое metadata = успех
            val freshStart = prevTitle.isBlank() && metaTitle.isNotBlank()
            if (titleSwitched || titleMatchesExpected || freshStart) sawSwitch = true

            val playing = st == PlaybackStateCompat.STATE_PLAYING ||
                st == PlaybackStateCompat.STATE_BUFFERING
            if (playing && sawSwitch) {
                return labelFrom(ctrl, description, fallbackName, preferDescription = true)
            }
            if (st == PlaybackStateCompat.STATE_PAUSED || st == PlaybackStateCompat.STATE_STOPPED) {
                runCatching { ctrl.transportControls.play() }
            }
            if (st == PlaybackStateCompat.STATE_ERROR) {
                runCatching { ctrl.transportControls.playFromMediaId(mediaId, null) }
                runCatching { ctrl.transportControls.play() }
            }
            Thread.sleep(250)
        }

        // Fallback: playFromSearch по названию выбранного трека
        val searchQ = expectedTitle.ifBlank { fallbackName }
        if (searchQ.isNotBlank()) {
            Log.i(TAG, "mediaId switch stalled — playFromSearch «$searchQ»")
            runCatching { ctrl.transportControls.stop() }
            Thread.sleep(200)
            runCatching {
                ctrl.transportControls.playFromSearch(searchQ, searchExtras(searchQ))
            }
            runCatching { ctrl.transportControls.play() }
            val fbDeadline = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < fbDeadline) {
                val metaTitle = ctrl.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty()
                val st = ctrl.playbackState?.state
                val matched = expectedTitle.isBlank() || metaTitle.isBlank() ||
                    MusicQueryMatcher.score(expectedTitle, metaTitle) >= 55 ||
                    MusicQueryMatcher.score(searchQ, metaTitle) >= 55
                val switched = metaTitle.isNotBlank() && metaTitle != prevTitle
                val playing = st == PlaybackStateCompat.STATE_PLAYING ||
                    st == PlaybackStateCompat.STATE_BUFFERING
                if (playing && (matched || switched)) {
                    return labelFrom(ctrl, description, fallbackName, preferDescription = false)
                }
                Thread.sleep(250)
            }
        }

        val metaTitle = ctrl.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty()
        Log.w(TAG, "play failed expected='$expectedTitle' still='$metaTitle'")
        return "ERR:Не удалось включить «${expectedTitle.ifBlank { fallbackName }}». " +
            "Сейчас: «${metaTitle.ifBlank { "тишина" }}»."
    }

    private fun labelFrom(
        ctrl: MediaControllerCompat,
        description: MediaDescriptionCompat?,
        fallback: String,
        preferDescription: Boolean = false,
    ): String {
        val descTitle = description?.title?.toString()
        val descArtist = description?.subtitle?.toString().orEmpty()
        val metaTitle = ctrl.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
        val metaArtist = ctrl.metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty()

        val title: String?
        val artist: String
        if (preferDescription && !descTitle.isNullOrBlank()) {
            val metaOk = !metaTitle.isNullOrBlank() &&
                MusicQueryMatcher.score(descTitle, metaTitle) >= 60
            if (metaOk) {
                title = metaTitle
                artist = metaArtist.ifBlank { descArtist }
            } else {
                title = descTitle
                artist = descArtist
            }
        } else {
            title = metaTitle ?: descTitle
            artist = metaArtist.ifBlank { descArtist }
        }
        return when {
            !title.isNullOrBlank() && artist.isNotBlank() -> "$artist — $title"
            !title.isNullOrBlank() -> title
            else -> fallback
        }
    }

    private fun search(browser: MediaBrowserCompat, query: String, timeoutMs: Long): BrowseResult {
        val latch = CountDownLatch(1)
        val items = AtomicReference<List<MediaBrowserCompat.MediaItem>>(emptyList())
        val err = AtomicReference<String?>(null)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                browser.search(
                    query,
                    searchExtras(query),
                    object : MediaBrowserCompat.SearchCallback() {
                        override fun onSearchResult(
                            q: String,
                            extras: Bundle?,
                            list: MutableList<MediaBrowserCompat.MediaItem>,
                        ) {
                            items.set(list.toList())
                            latch.countDown()
                        }

                        override fun onError(q: String, extras: Bundle?) {
                            err.set("Поиск не удался")
                            latch.countDown()
                        }
                    },
                )
            }.onFailure {
                err.set(it.message)
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return BrowseResult(items.get(), err.get())
    }

    private fun loadChildren(
        browser: MediaBrowserCompat,
        parentId: String,
        timeoutMs: Long,
    ): BrowseResult {
        val latch = CountDownLatch(1)
        val items = AtomicReference<List<MediaBrowserCompat.MediaItem>>(emptyList())
        val err = AtomicReference<String?>(null)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                browser.subscribe(
                    parentId,
                    object : MediaBrowserCompat.SubscriptionCallback() {
                        override fun onChildrenLoaded(
                            parent: String,
                            children: MutableList<MediaBrowserCompat.MediaItem>,
                        ) {
                            items.set(children.toList())
                            runCatching { browser.unsubscribe(parentId) }
                            latch.countDown()
                        }

                        override fun onError(id: String) {
                            err.set("Не открыл $id")
                            runCatching { browser.unsubscribe(parentId) }
                            latch.countDown()
                        }
                    },
                )
            }.onFailure {
                err.set(it.message)
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return BrowseResult(items.get(), err.get())
    }

    private fun searchExtras(query: String): Bundle = Bundle().apply {
        putString(SearchManager.QUERY, query)
        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putString(MediaStore.EXTRA_MEDIA_TITLE, query)
        putString(MediaStore.EXTRA_MEDIA_ARTIST, query)
        putString(MediaStore.EXTRA_MEDIA_ALBUM, query)
    }

    private fun transport(
        requestId: String,
        label: String,
        block: (MediaController.TransportControls) -> Unit,
    ): ToolResult {
        val ctrl = activeController()
        if (ctrl != null) {
            block(ctrl.transportControls)
            rememberPackage(ctrl.packageName)
            val title = ctrl.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val msg = when (label) {
                "pause" -> "Пауза."
                "resume" -> "Играю."
                "next" -> "Дальше."
                "previous" -> "Назад."
                else -> "Ок."
            }
            return ToolResult(requestId, definition.id, ToolResultStatus.SUCCESS, msg)
        }
        // Fallback: media keys + разбудить Кион через MediaBrowser play()
        val key = when (label) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        dispatchMediaKey(key)
        if (label == "resume" && isInstalled(PKG_KION)) {
            Thread({
                withBrowser { _, ctrlCompat ->
                    runCatching { ctrlCompat.transportControls.play() }
                    "ok"
                }
            }, "jarvis-music-resume-bg").start()
        }
        return ToolResult(requestId, definition.id, ToolResultStatus.SUCCESS, when (label) {
            "pause" -> "Пауза."
            "resume" -> "Играю."
            "next" -> "Дальше."
            "previous" -> "Назад."
            else -> "Ок."
        })
    }

    private fun status(requestId: String): ToolResult {
        val now = System.currentTimeMillis()
        synchronized(statusCacheLock) {
            val cached = statusCache
            if (cached != null && now - cached.atMs < STATUS_CACHE_TTL_MS) {
                return ToolResult(requestId, definition.id, ToolResultStatus.SUCCESS, cached.text)
            }
        }
        val ctrl = activeController()
            ?: return ToolResult(requestId, definition.id, ToolResultStatus.SUCCESS, "Сейчас ничего не играет.")
                .also { cacheStatus(it.message.orEmpty()) }
        rememberPackage(ctrl.packageName)
        val title = ctrl.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "без названия"
        val artist = ctrl.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val playing = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
        val state = if (playing) "играет" else "на паузе"
        val who = if (artist.isNotBlank()) "$artist — $title" else title
        val text = "Сейчас $state: $who."
        cacheStatus(text)
        return ToolResult(requestId, definition.id, ToolResultStatus.SUCCESS, text)
    }

    private fun cacheStatus(text: String) {
        synchronized(statusCacheLock) {
            statusCache = StatusCache(text, System.currentTimeMillis())
        }
    }

    private data class StatusCache(val text: String, val atMs: Long)
    private val statusCacheLock = Any()
    @Volatile private var statusCache: StatusCache? = null

    private fun activeController(): MediaController? {
        if (!JarvisNotificationListenerService.isConnected()) return null
        val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(context, JarvisNotificationListenerService::class.java)
        return runCatching {
            val all = mgr.getActiveSessions(cn)
            all.firstOrNull { c ->
                val st = c.playbackState?.state
                st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED ||
                    st == PlaybackState.STATE_BUFFERING
            } ?: all.firstOrNull { it.packageName == PKG_KION } ?: all.firstOrNull()
        }.getOrNull()
    }

    private fun allControllers(): List<MediaController> {
        if (!JarvisNotificationListenerService.isConnected()) return emptyList()
        val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(context, JarvisNotificationListenerService::class.java)
        return runCatching { mgr.getActiveSessions(cn) }.getOrDefault(emptyList())
    }

    private fun pauseOtherPlayers(except: String?) {
        for (c in allControllers()) {
            if (except != null && c.packageName == except) continue
            runCatching { c.transportControls.pause() }
        }
    }

    private fun rememberPackage(packageName: String?) {
        if (packageName.isNullOrBlank() || !isInstalled(packageName)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_PLAYER, packageName).apply()
    }

    private fun isInstalled(packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    private fun dispatchMediaKey(keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    companion object {
        private const val TAG = "Jarvis.Music"
        private const val PREFS = "jarvis_prefs"
        private const val PREF_PLAYER = "music_player_package"
        private const val STATUS_CACHE_TTL_MS = 12_000L
        const val PKG_KION = "ru.mts.music.android"
        private const val SVC_KION = "ru.mts.music.common.service.player.MusicService"
        private const val PKG_YT_MUSIC = "com.google.android.apps.youtube.music"
        private const val PKG_SPOTIFY = "com.spotify.music"
        private const val PKG_YANDEX = "ru.yandex.music"
    }
}
