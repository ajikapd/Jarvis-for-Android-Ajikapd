package com.jarvis.screencontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.jarvis.core.nlp.Transliterator
import kotlin.math.min

/**
 * Координатор: цель (текст/описание) → fuzzy-поиск узла → жест.
 * Если узел не найден — Intent-fallback на системные экраны.
 */
class ActionCoordinator(
    private val context: Context,
) {

    data class ActionResult(
        val success: Boolean,
        val message: String,
        val matchedText: String? = null,
        val usedFallback: Boolean = false,
    )

    fun findAndClick(
        query: String,
        longPress: Boolean = false,
        scrollAttempts: Int = 5,
        waitTimeoutMs: Long = 4_000L,
    ): ActionResult {
        if (!ScreenController.isAvailable()) {
            tryIntentFallback(query)?.let { return it }
            return ActionResult(false, "Служба спец. возможностей Jarvis не включена.")
        }
        ScreenController.dismissShadeIfNeeded()
        // Координаты: «в центре», «30 70», «x=0.5 y=0.2»
        parseTapPoint(query)?.let { (x, y) ->
            val ok = ScreenController.tap(x, y, longPress)
            return if (ok) ActionResult(true, "Нажал по координатам.")
            else ActionResult(false, "Тап по координатам не выполнен.")
        }

        // HyperOS: дерево часто появляется с задержкой после launch
        if (waitTimeoutMs > 0) {
            waitForNode(query, timeoutMs = waitTimeoutMs, forClick = true)
        }

        repeat(scrollAttempts + 1) { attempt ->
            val node = findBestNode(query, preferEditable = false, forClick = true)
            if (node != null) {
                val ok = ScreenController.tapCenter(node, longPress)
                return if (ok) {
                    ActionResult(
                        true,
                        "Нажал «${node.text.ifBlank { node.contentDescription }.ifBlank { query }}».",
                        node.text,
                    )
                } else {
                    ActionResult(false, "Нашёл «${node.searchable}», но нажать не удалось.")
                }
            }
            if (attempt < scrollAttempts) {
                // Ищем ниже по списку — свайп вверх.
                ScreenController.swipeDirection("up")
                Thread.sleep(450)
            }
        }
        val fallback = tryIntentFallback(query)
        if (fallback != null) return fallback

        // Phase 3: дерево пустое/бедное — OCR по скриншоту + тап по боксу.
        val ocrHit = findAndClickByOcr(query, longPress)
        if (ocrHit != null) return ocrHit

        return ActionResult(false, "На экране не нашёл элемент «$query».")
    }

    /** OCR-fallback: скриншот → ML Kit → тап в центр найденного текста. */
    fun findAndClickByOcr(query: String, longPress: Boolean = false): ActionResult? {
        if (!ScreenController.isAvailable()) return null
        val blocks = runCatching { ScreenOcr.recognizeScreen() }.getOrDefault(emptyList())
        if (blocks.isEmpty()) return null
        val hit = ScreenOcr.findBest(query, blocks) ?: return null
        val cx = hit.bounds.exactCenterX()
        val cy = hit.bounds.exactCenterY()
        val ok = ScreenController.tap(cx, cy, longPress)
        return if (ok) {
            ActionResult(
                true,
                "Нажал по OCR «${hit.text.take(40)}».",
                hit.text,
                usedFallback = true,
            )
        } else {
            ActionResult(false, "OCR нашёл «${hit.text.take(40)}», но тап не прошёл.", hit.text, true)
        }
    }

    /**
     * Ждёт появления узла по тексту/описанию/viewId с exponential backoff.
     * [packageHint] — опционально ждать, пока active package содержит подстроку.
     */
    fun waitForNode(
        query: String,
        timeoutMs: Long = 5_000L,
        packageHint: String? = null,
        viewId: String? = null,
        forClick: Boolean = false,
    ): ActionResult {
        if (!ScreenController.isAvailable()) {
            return ActionResult(false, "Служба спец. возможностей Jarvis не включена.")
        }
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(200L)
        var backoff = 120L
        var sawPackage = packageHint.isNullOrBlank()
        while (System.currentTimeMillis() < deadline) {
            ScreenController.dismissShadeIfNeeded()
            if (!packageHint.isNullOrBlank() && !sawPackage) {
                val pkg = ScreenController.activePackageName().orEmpty()
                if (pkg.contains(packageHint, ignoreCase = true)) {
                    sawPackage = true
                }
            }
            if (sawPackage || packageHint.isNullOrBlank()) {
                if (!viewId.isNullOrBlank()) {
                    val byId = ScreenController.dumpActiveTree()
                        .firstOrNull { it.viewId.contains(viewId, ignoreCase = true) }
                    if (byId != null) {
                        return ActionResult(true, "Узел id«$viewId» на экране.", byId.text)
                    }
                }
                if (query.isNotBlank()) {
                    val node = findBestNode(query, preferEditable = false, forClick = forClick)
                    if (node != null) {
                        return ActionResult(
                            true,
                            "Узел «${node.searchable.ifBlank { query }}» на экране.",
                            node.text,
                        )
                    }
                } else if (!viewId.isNullOrBlank() || !packageHint.isNullOrBlank()) {
                    if (sawPackage && viewId.isNullOrBlank()) {
                        return ActionResult(true, "Пакет «$packageHint» активен.")
                    }
                }
            }
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            backoff = (backoff * 2).coerceAtMost(700L)
        }
        val what = when {
            query.isNotBlank() -> "«$query»"
            !viewId.isNullOrBlank() -> "id«$viewId»"
            !packageHint.isNullOrBlank() -> "пакет «$packageHint»"
            else -> "узел"
        }
        return ActionResult(false, "Таймаут ${timeoutMs}мс: $what не появился.")
    }

    /** Ждёт смены/появления active package (после launch). */
    fun waitForPackage(packageHint: String, timeoutMs: Long = 4_000L): ActionResult =
        waitForNode(query = "", timeoutMs = timeoutMs, packageHint = packageHint)

    /** Краткое «зрение»: кликабельные/редактируемые элементы на экране. */
    fun describeScreen(maxItems: Int = 30): ActionResult {
        if (!ScreenController.isAvailable()) {
            return ActionResult(false, "Служба спец. возможностей Jarvis не включена.")
        }
        val tree = ScreenController.dumpActiveTree()
        val activePkg = ScreenController.activePackageName()
            ?: tree.firstOrNull { it.packageName.isNotBlank() }?.packageName
        val pkgShort = activePkg?.substringAfterLast('.')
        val noise = setOf(
            "scrim", "blur", "aod_", "legacy_window", "shade_", "notification_animation",
            "keyguard", "footer_clip", "header_clip", "fold_header",
        )
        val nodes = tree
            .asSequence()
            .filter { it.enabled }
            .map { node ->
                val label = listOf(node.text, node.contentDescription)
                    .firstOrNull { it.isNotBlank() }
                    ?: node.viewId.substringAfterLast('/').takeIf {
                        it.isNotBlank() && (node.clickable || node.editable || it.contains("search", true))
                    }
                    ?: ""
                label.trim()
            }
            .filter { it.length in 1..80 }
            .filterNot { it.matches(Regex("""^\d+$""")) }
            .filterNot { label -> noise.any { label.contains(it, ignoreCase = true) } }
            .distinct()
            .take(maxItems)
            .toList()

        // Phase 3: пустое/бедное дерево → OCR по скриншоту.
        if (nodes.size < 3) {
            val ocr = describeByOcr(maxItems, pkgShort)
            if (ocr != null) return ocr
        }

        if (tree.isEmpty()) {
            return ActionResult(
                false,
                "Не вижу содержимое экрана. Открой любой экран поверх Jarvis и повтори «что на экране».",
            )
        }
        if (nodes.isEmpty()) {
            val pkgs = tree.map { it.packageName.substringAfterLast('.') }.distinct().take(6)
            val onlySystem = pkgs.all {
                it.contains("systemui", true) || it.contains("aod", true) ||
                    it.contains("plugin", true) || it.contains("keyguard", true)
            }
            val hint = if (onlySystem) {
                " Вижу только системные окна — в Настройках → Спец. возможности выключи и снова включи Jarvis (после установки HyperOS часто режет доступ)."
            } else {
                ""
            }
            return ActionResult(
                false,
                "Экран открыт (${pkgs.joinToString(",")}), но текстовых элементов не видно.$hint",
            )
        }
        val prefix = if (pkgShort != null) "Экран «$pkgShort»: " else "На экране: "
        return ActionResult(
            true,
            prefix + nodes.joinToString("; ") + ".",
            matchedText = nodes.joinToString("|"),
        )
    }

    private fun describeByOcr(maxItems: Int, pkgShort: String?): ActionResult? {
        val blocks = runCatching { ScreenOcr.recognizeScreen() }.getOrDefault(emptyList())
        if (blocks.isEmpty()) return null
        val labels = blocks
            .map { it.text.trim().replace('\n', ' ') }
            .filter { it.length in 1..80 }
            .distinct()
            .take(maxItems)
        if (labels.isEmpty()) return null
        val prefix = when {
            pkgShort != null -> "Экран «$pkgShort» (OCR): "
            else -> "На экране (OCR): "
        }
        return ActionResult(
            true,
            prefix + labels.joinToString("; ") + ".",
            matchedText = labels.joinToString("|"),
            usedFallback = true,
        )
    }

    fun scrollUntil(query: String, direction: String = "up", maxAttempts: Int = 8): ActionResult {
        if (!ScreenController.isAvailable()) {
            return ActionResult(false, "Служба спец. возможностей Jarvis не включена.")
        }
        repeat(maxAttempts) { attempt ->
            val node = findBestNode(query)
            if (node != null) {
                return ActionResult(true, "Нашёл «${node.searchable}» после прокрутки ($attempt).", node.text)
            }
            ScreenController.swipeDirection(direction)
            Thread.sleep(400)
        }
        return ActionResult(false, "Не нашёл «$query» при прокрутке.")
    }

    fun tapFraction(fx: Float, fy: Float, longPress: Boolean = false): ActionResult {
        if (!ScreenController.isAvailable()) {
            return ActionResult(false, "Служба спец. возможностей Jarvis не включена.")
        }
        val dm = context.resources.displayMetrics
        val x = (dm.widthPixels * fx.coerceIn(0.05f, 0.95f))
        val y = (dm.heightPixels * fy.coerceIn(0.05f, 0.95f))
        val ok = ScreenController.tap(x, y, longPress)
        return if (ok) ActionResult(true, "Нажал по точке экрана.")
        else ActionResult(false, "Тап не выполнен.")
    }

    private fun parseTapPoint(query: String): Pair<Float, Float>? {
        val q = query.lowercase().trim()
        val dm = context.resources.displayMetrics
        when {
            q.contains("центр") || q == "посередине" ->
                return dm.widthPixels / 2f to dm.heightPixels / 2f
            q.contains("верх") && q.contains("лев") ->
                return dm.widthPixels * 0.15f to dm.heightPixels * 0.12f
            q.contains("верх") && q.contains("прав") ->
                return dm.widthPixels * 0.85f to dm.heightPixels * 0.12f
            q.contains("низ") && q.contains("лев") ->
                return dm.widthPixels * 0.15f to dm.heightPixels * 0.88f
            q.contains("низ") && q.contains("прав") ->
                return dm.widthPixels * 0.85f to dm.heightPixels * 0.88f
        }
        val frac = Regex("""(?:x\s*=\s*)?([01](?:\.\d+)?)\s*[;,\s]\s*(?:y\s*=\s*)?([01](?:\.\d+)?)""")
            .find(q)
        if (frac != null) {
            val fx = frac.groupValues[1].toFloatOrNull() ?: return null
            val fy = frac.groupValues[2].toFloatOrNull() ?: return null
            return dm.widthPixels * fx to dm.heightPixels * fy
        }
        return null
    }

    fun findAndType(query: String?, text: String): ActionResult {
        if (!ScreenController.isAvailable()) {
            return ActionResult(false, "Служба спец. возможностей Jarvis не включена.")
        }
        if (text.isBlank()) return ActionResult(false, "Нечего вводить.")
        if (!query.isNullOrBlank()) {
            waitForNode(query, timeoutMs = 2_000L)
            val field = findBestNode(query, preferEditable = true)
            if (field != null) {
                ScreenController.tapCenter(field)
                Thread.sleep(200)
                val ok = ScreenController.setTextOnNode(field, text) || ScreenController.setTextOnFocused(text)
                return if (ok) {
                    ActionResult(true, "Ввёл текст в «${field.searchable}».")
                } else {
                    ActionResult(false, "Поле найдено, но ввод не удался.")
                }
            }
        }
        val ok = ScreenController.setTextOnFocused(text)
        return if (ok) {
            ActionResult(true, "Ввёл текст в активное поле.")
        } else {
            ActionResult(false, "Нет активного поля ввода на экране.")
        }
    }

    /**
     * Ввод в нижнее поле чата (compose): hint «Сообщение» часто не в a11y-дереве.
     * Берём самый нижний editable узел.
     */
    fun findAndTypeInBottomField(text: String): ActionResult {
        if (!ScreenController.isAvailable()) {
            return ActionResult(false, "Служба спец. возможностей Jarvis не включена.")
        }
        if (text.isBlank()) return ActionResult(false, "Нечего вводить.")
        val nodes = ScreenController.dumpActiveTree().ifEmpty { ScreenController.dumpTree() }
        val field = nodes
            .filter { it.editable && it.enabled }
            .maxByOrNull { it.bounds.centerY() }
        if (field != null) {
            ScreenController.tapCenter(field)
            Thread.sleep(250)
            val ok = ScreenController.setTextOnNode(field, text) || ScreenController.setTextOnFocused(text)
            if (ok) return ActionResult(true, "Ввёл текст в поле сообщения.")
        }
        return findAndType(null, text)
    }

    fun swipe(direction: String): ActionResult {
        if (!ScreenController.isAvailable()) {
            return ActionResult(false, "Служба спец. возможностей Jarvis не включена.")
        }
        val ok = ScreenController.swipeDirection(direction)
        return if (ok) ActionResult(true, "Свайп: $direction")
        else ActionResult(false, "Свайп «$direction» не выполнен.")
    }

    fun global(action: String): ActionResult {
        if (action.equals("home", true)) {
            return try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                if (ScreenController.isAvailable()) ScreenController.globalAction("home")
                ActionResult(true, "Нажал «Домой».", usedFallback = !ScreenController.isAvailable())
            } catch (t: Throwable) {
                ActionResult(false, t.message ?: "home failed")
            }
        }
        if (!ScreenController.isAvailable()) {
            return ActionResult(false, "Служба спец. возможностей Jarvis не включена.")
        }
        val ok = ScreenController.globalAction(action)
        return if (ok) ActionResult(true, "Выполнил действие «$action».")
        else ActionResult(false, "Неизвестное или недоступное действие «$action».")
    }

    fun findBestNode(
        query: String,
        preferEditable: Boolean = false,
        forClick: Boolean = false,
    ): UiNode? {
        val nodes = ScreenController.dumpActiveTree().ifEmpty { ScreenController.dumpTree() }
        if (nodes.isEmpty()) return null
        val q = query.trim()
        if (q.isBlank()) return null
        val variants = expandQuery(q)
        var scored = nodes.mapNotNull { node ->
            val score = variants.maxOf { matchScore(it, node) }
            if (score <= 0) null else node to score
        }
        if (scored.isEmpty()) return null

        // Клик по результату поиска: не жать EditText, куда сами же ввели запрос
        // (иначе «нажми избранное» попадает в поле поиска, а не в чат).
        if (forClick || !preferEditable) {
            val nonEdit = scored.filter { !it.first.editable && !it.first.focused }
            if (nonEdit.isNotEmpty()) scored = nonEdit
        }

        val ordered = if (preferEditable) {
            scored.sortedWith(
                compareByDescending<Pair<UiNode, Int>> { if (it.first.editable) 1 else 0 }
                    .thenByDescending { it.second },
            )
        } else if (forClick) {
            // Список поиска: верхний хит важнее «лучшего» fuzzy ниже по экрану
            val screenH = context.resources.displayMetrics.heightPixels
            val searchBarMaxY = screenH * 0.14f
            val qLower = q.lowercase()
            val isChromeQuery = qLower.contains("поиск") || qLower.contains("search") ||
                qLower.contains("назад") || qLower.contains("back") ||
                qLower.contains("отправ") || qLower.contains("send")
            val listHits = if (!isChromeQuery) {
                val belowBar = scored.filter { it.first.bounds.centerY() > searchBarMaxY }
                if (belowBar.isNotEmpty()) belowBar else scored
            } else {
                scored
            }
            listHits.sortedWith(
                compareByDescending<Pair<UiNode, Int>> { if (!it.first.editable) 2 else 0 }
                    .thenByDescending { exactLabelMatch(q, it.first) }
                    .thenBy { it.first.bounds.top } // первый в списке
                    .thenByDescending { if (it.first.clickable) 1 else 0 }
                    .thenByDescending { it.second },
            )
        } else {
            scored.sortedWith(
                compareByDescending<Pair<UiNode, Int>> { if (!it.first.editable) 2 else 0 }
                    .thenByDescending { if (it.first.clickable) 1 else 0 }
                    .thenByDescending { exactLabelMatch(q, it.first) }
                    .thenByDescending { it.second }
                    .thenByDescending { it.first.bounds.centerY() },
            )
        }
        return ordered.firstOrNull()?.first
    }

    private fun exactLabelMatch(query: String, node: UiNode): Int {
        val q = query.lowercase().trim()
        val labels = listOf(node.text, node.contentDescription)
            .map { it.lowercase().trim().lineSequence().firstOrNull()?.trim().orEmpty() }
            .filter { it.isNotEmpty() }
        return when {
            labels.any { it == q } -> 3
            labels.any { it.startsWith(q) && it.length <= q.length + 8 } -> 2
            labels.any { it.equals(q, ignoreCase = true) } -> 3
            else -> 0
        }
    }

    /** Синонимы для частых UI-элементов (HyperOS/EN). */
    private fun expandQuery(query: String): List<String> {
        val q = query.lowercase().trim()
        val extra = when {
            q.contains("избран") || q.contains("saved") ->
                listOf(
                    "Избранное", "избранное", "Избранные",
                    "Saved Messages", "Saved messages", "Saved",
                )
            q.contains("поиск") || q == "search" || q.contains("искать") ->
                listOf("поиск", "search", "искать", "найти", "Search", "search_src_text", "search_bar")
            q.contains("отправ") || q == "send" ->
                listOf("отправить", "send", "отправка", "Send")
            q.contains("сохран") || q == "save" ->
                listOf("сохранить", "save", "Save", "готово", "done")
            q.contains("отмен") || q == "cancel" ->
                listOf("отмена", "cancel", "Cancel", "закрыть")
            q.contains("назад") || q == "back" ->
                listOf("назад", "back", "navigate up", "перейти вверх")
            q.contains("меню") || q == "menu" || q.contains("ещё") || q.contains("еще") ->
                listOf("меню", "menu", "ещё", "еще", "more", "More options")
            else -> emptyList()
        }
        return (listOf(query) + extra).distinct()
    }

    private fun matchScore(query: String, node: UiNode): Int {
        val candidates = listOf(node.text, node.contentDescription, node.viewId.substringAfterLast('/'))
            .filter { it.isNotBlank() }
        if (candidates.isEmpty()) return 0
        val qNorm = Transliterator.compareKey(query)
        val qLower = query.lowercase()
        var best = 0
        for (c in candidates) {
            val cLower = c.lowercase()
            val cNorm = Transliterator.compareKey(c)
            val score = when {
                cLower == qLower -> 100
                cNorm == qNorm && qNorm.isNotEmpty() -> 95
                cLower.startsWith(qLower) -> 85
                cLower.contains(qLower) -> 75
                qLower.contains(cLower) && cLower.length >= 3 -> 60
                cNorm.contains(qNorm) && qNorm.length >= 3 -> 55
                fuzzyClose(qLower, cLower) -> 45
                else -> 0
            }
            best = maxOf(best, score)
        }
        return best
    }

    private fun fuzzyClose(a: String, b: String): Boolean {
        if (a.length < 3 || b.length < 3) return false
        if (kotlin.math.abs(a.length - b.length) > 3) return false
        return levenshtein(a, b) <= min(2, min(a.length, b.length) / 3)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** Intent-fallback, когда на текущем экране узла нет. */
    fun tryIntentFallback(query: String): ActionResult? {
        val q = query.lowercase()
        val intent: Intent? = when {
            q.contains("сеть") && (q.contains("и") || q.contains("wi") || q.length < 8) ->
                Intent(Settings.ACTION_WIFI_SETTINGS)

            q.contains("wi-fi") || q.contains("wifi") || q.contains("вайфай") ||
                q.contains("вай-фай") || (q.contains("wi") && q.contains("fi")) ||
                (q.contains("настрой") && q.contains("wi")) || q == "сеть" || q == "интернет" ->
                Intent(Settings.ACTION_WIFI_SETTINGS)

            q.contains("bluetooth") || q.contains("блютус") || q.contains("синезуб") ->
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

            q.contains("самолёт") || q.contains("самолет") || q.contains("airplane") ||
                q.contains("авиарежим") || q.contains("режим полёт") || q.contains("режим полет") ->
                Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)

            q.contains("яркост") || q.contains("дисплей") || (q.contains("экран") && q.contains("настрой")) ->
                Intent(Settings.ACTION_DISPLAY_SETTINGS)

            q.contains("звук") || q.contains("громкост") ->
                Intent(Settings.ACTION_SOUND_SETTINGS)

            q.contains("локац") || q.contains("геолокац") || q.contains("gps") ->
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

            q.contains("приложен") && q.contains("настрой") ->
                Intent(Settings.ACTION_APPLICATION_SETTINGS)

            q.contains("настройк") && !q.contains("wi") ->
                Intent(Settings.ACTION_SETTINGS)

            q.contains("звонилк") || q.contains("телефон") && q.contains("открой") ->
                Intent(Intent.ACTION_DIAL)

            else -> null
        }
        if (intent == null) return null
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionResult(true, "Открыл системный экран для «$query».", usedFallback = true)
        } catch (t: Throwable) {
            ActionResult(false, "Не удалось открыть настройки: ${t.message}")
        }
    }

    fun openWifiSettings(): ActionResult = tryIntentFallback("wifi")
        ?: ActionResult(false, "Не удалось открыть Wi‑Fi")

    fun openAppDetails(packageName: String): ActionResult {
        return try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionResult(true, "Открыл сведения о приложении $packageName.", usedFallback = true)
        } catch (t: Throwable) {
            ActionResult(false, t.message ?: "fail")
        }
    }
}
