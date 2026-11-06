package com.jarvis.screencontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Низкоуровневые примитивы поверх [JarvisAccessibilityService]:
 * дерево экрана, жесты, ввод текста, глобальные действия, скриншот.
 */
object ScreenController {

    @Volatile
    private var service: JarvisAccessibilityService? = null

    fun bind(svc: JarvisAccessibilityService) {
        service = svc
    }

    fun unbind(svc: JarvisAccessibilityService) {
        if (service === svc) service = null
    }

    fun isAvailable(): Boolean = service != null

    fun requireService(): JarvisAccessibilityService =
        service ?: error("Accessibility service is not enabled")

    // ── Tree ──────────────────────────────────────────────────────────

    fun dumpTree(maxNodes: Int = 500): List<UiNode> {
        val svc = service ?: return emptyList()
        val out = ArrayList<UiNode>(128)
        val roots = mutableListOf<Pair<AccessibilityNodeInfo, Int>>() // root to priority
        val activeRoot = svc.rootInActiveWindow
        activeRoot?.let { roots += it to 10 }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            svc.windows?.forEach { window ->
                val root = window.root ?: return@forEach
                val priority = when (window.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> 100
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> 5
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> 1
                    AccessibilityWindowInfo.TYPE_SYSTEM -> 2
                    else -> 20
                }
                if (roots.none { sameRoot(it.first, root) }) {
                    roots += root to priority
                } else {
                    root.recycle()
                }
            }
        }
        // Сначала application-окна (настройки и т.п.), не шторка systemui.
        roots.sortByDescending { it.second }
        try {
            for ((root, _) in roots) {
                walk(root, 0, out, maxNodes)
                if (out.size >= maxNodes) break
            }
        } finally {
            roots.forEach { runCatching { it.first.recycle() } }
        }
        return out
    }

    /** Узлы активного окна; без AOD/systemui-оверлеев. */
    fun dumpActiveTree(maxNodes: Int = 500): List<UiNode> {
        dismissShadeIfNeeded()
        val all = dumpTree(maxNodes)
        if (all.isEmpty()) return emptyList()
        val overlayHints = listOf(
            "aod", "systemui", "keyguard", "miui.aod", "fingerprint", "biometrics",
        )
        fun isOverlay(pkg: String): Boolean =
            overlayHints.any { pkg.contains(it, ignoreCase = true) }

        val activePkg = activePackageName()
        if (activePkg != null) {
            val focused = all.filter { it.packageName == activePkg }
            if (focused.isNotEmpty()) return focused
        }
        val main = all.filter { !isOverlay(it.packageName) }
        return main.ifEmpty { all }
    }

    fun activePackageName(): String? {
        val svc = service ?: return null
        // Предпочитаем TYPE_APPLICATION окно
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val appWin = svc.windows
                ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.maxByOrNull { it.layer }
            val root = appWin?.root
            val pkg = root?.packageName?.toString()
            root?.recycle()
            if (!pkg.isNullOrBlank()) return pkg
        }
        return svc.rootInActiveWindow?.packageName?.toString()
            ?.takeUnless { pkg ->
                listOf("aod", "systemui", "keyguard").any { pkg.contains(it, ignoreCase = true) }
            }
    }

    /** Если фокус на шторке уведомлений — закрываем её, иначе «зрение» слепое. */
    fun dismissShadeIfNeeded() {
        val svc = service ?: return
        val pkg = svc.rootInActiveWindow?.packageName?.toString().orEmpty()
        if (pkg.contains("systemui", ignoreCase = true)) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            try {
                Thread.sleep(350)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun sameRoot(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean {
        val ra = Rect()
        val rb = Rect()
        a.getBoundsInScreen(ra)
        b.getBoundsInScreen(rb)
        return ra == rb && a.packageName == b.packageName
    }

    fun findFocusedEditable(): UiNode? =
        dumpTree().firstOrNull { it.focused && it.editable }
            ?: dumpTree().firstOrNull { it.editable }

    /**
     * Видимые узлы активного окна. В WebView скрытые страницы лежат в дереве с пустыми
     * границами, и полный обход занимает секунды; поиск по тексту WebView не поддерживает.
     */
    fun visibleNodes(maxNodes: Int = 500): List<UiNode> {
        val root = service?.rootInActiveWindow ?: return emptyList()
        val out = ArrayList<UiNode>()
        try {
            walkVisible(root, out, maxNodes)
        } finally {
            root.recycle()
        }
        return out
    }

    private fun walkVisible(node: AccessibilityNodeInfo, out: MutableList<UiNode>, maxNodes: Int) {
        if (out.size >= maxNodes || !node.isVisibleToUser) return
        val item = uiNode(node, 0)
        if (item.bounds.isEmpty) return
        out += item
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walkVisible(child, out, maxNodes)
            } finally {
                child.recycle()
            }
        }
    }

    private fun uiNode(node: AccessibilityNodeInfo, depth: Int): UiNode {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return UiNode(
            text = node.text?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            packageName = node.packageName?.toString().orEmpty(),
            bounds = bounds,
            clickable = node.isClickable,
            editable = node.isEditable || node.className?.toString()?.contains("EditText", true) == true,
            focused = node.isFocused,
            enabled = node.isEnabled,
            depth = depth,
            password = node.isPassword,
        )
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<UiNode>,
        maxNodes: Int,
    ) {
        if (out.size >= maxNodes) return
        out += uiNode(node, depth)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walk(child, depth + 1, out, maxNodes)
            } finally {
                child.recycle()
            }
            if (out.size >= maxNodes) return
        }
    }

    // ── Gestures ──────────────────────────────────────────────────────

    fun tap(x: Float, y: Float, longPress: Boolean = false): Boolean {
        val svc = service ?: return false
        val path = Path().apply { moveTo(x, y) }
        val duration = if (longPress) 600L else 50L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchSync(svc, gesture)
    }

    fun tapCenter(node: UiNode, longPress: Boolean = false): Boolean {
        val cx = node.bounds.exactCenterX()
        val cy = node.bounds.exactCenterY()
        if (cx <= 0f && cy <= 0f) return false
        // Prefer ACTION_CLICK when clickable
        if (!longPress && clickNodeByBounds(node)) return true
        return tap(cx, cy, longPress)
    }

    fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 350L,
    ): Boolean {
        val svc = service ?: return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, max(100L, durationMs))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchSync(svc, gesture)
    }

    fun swipeDirection(direction: String, distanceFraction: Float = 0.45f): Boolean {
        val svc = service ?: return false
        // Сначала пробуем ACTION_SCROLL на скроллируемом узле
        if (scrollNode(direction)) return true
        val dm = svc.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val distX = w * distanceFraction
        val distY = h * distanceFraction
        return when (direction.lowercase()) {
            "up" -> swipe(cx, cy + distY / 2, cx, cy - distY / 2)
            "down" -> swipe(cx, cy - distY / 2, cx, cy + distY / 2)
            "left" -> swipe(cx + distX / 2, cy, cx - distX / 2, cy)
            "right" -> swipe(cx - distX / 2, cy, cx + distX / 2, cy)
            else -> false
        }
    }

    /** ACTION_SCROLL_FORWARD/BACKWARD на первом scrollable-узле. */
    fun scrollNode(direction: String): Boolean {
        val svc = service ?: return false
        val root = svc.rootInActiveWindow ?: return false
        val forward = direction.lowercase() in setOf("down", "right", "forward", "вниз")
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return try {
            findScrollable(root)?.let { node ->
                try {
                    node.performAction(action)
                } finally {
                    node.recycle()
                }
            } ?: false
        } finally {
            root.recycle()
        }
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findScrollable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    /** Нажать Enter / подтвердить в поле ввода (IME_ACTION / клик по «Готово»). */
    fun pressEnter(): Boolean {
        val svc = service ?: return false
        val root = svc.rootInActiveWindow ?: return false
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            return try {
                // Настоящий Enter клавиатуры: без него поиск в приложениях не запускается.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                    focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                ) {
                    return true
                }
                val args = Bundle().apply {
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
                    )
                }
                // IME_ENTER через ACTION_NEXT / клик по кнопке
                if (focused.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args)) {
                    return true
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally {
                focused.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    fun drag(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 500L,
    ): Boolean = swipe(x1, y1, x2, y2, durationMs)

    private fun clickNodeByBounds(target: UiNode): Boolean {
        val svc = service ?: return false
        val root = svc.rootInActiveWindow ?: return false
        try {
            val node = findNodeMatching(root, target) ?: return false
            return try {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    var p: AccessibilityNodeInfo? = node.parent
                    var ok = false
                    while (p != null) {
                        if (p.isClickable) {
                            ok = p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            break
                        }
                        val next = p.parent
                        p.recycle()
                        p = next
                    }
                    p?.recycle()
                    ok
                }
            } finally {
                node.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun findNodeMatching(
        root: AccessibilityNodeInfo,
        target: UiNode,
    ): AccessibilityNodeInfo? {
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        val text = root.text?.toString().orEmpty()
        val desc = root.contentDescription?.toString().orEmpty()
        val id = root.viewIdResourceName.orEmpty()
        if (bounds == target.bounds &&
            text == target.text &&
            desc == target.contentDescription &&
            id == target.viewId
        ) {
            return AccessibilityNodeInfo.obtain(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeMatching(child, target)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun dispatchSync(svc: AccessibilityService, gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicReference(false)
        val main = Handler(Looper.getMainLooper())
        val posted = CountDownLatch(1)
        main.post {
            svc.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        ok.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        ok.set(false)
                        latch.countDown()
                    }
                },
                null,
            )
            posted.countDown()
        }
        posted.await(1, TimeUnit.SECONDS)
        latch.await(2, TimeUnit.SECONDS)
        return ok.get()
    }

    // ── Text input ───────────────────────────────────────────────────

    fun setTextOnFocused(text: String): Boolean {
        val svc = service ?: return false
        val root = svc.rootInActiveWindow ?: return false
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditable(root)
                ?: return false
            return try {
                setTextOnNode(svc, focused, text)
            } finally {
                focused.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    fun setTextOnNode(node: UiNode, text: String): Boolean {
        val svc = service ?: return false
        val root = svc.rootInActiveWindow ?: return false
        try {
            val n = findNodeMatching(root, node) ?: return false
            return try {
                setTextOnNode(svc, n, text)
            } finally {
                n.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun setTextOnNode(
        svc: AccessibilityService,
        node: AccessibilityNodeInfo,
        text: String,
    ): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // Fallback: paste via clipboard
        val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("jarvis", text))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable || root.className?.toString()?.contains("EditText", true) == true) {
            return AccessibilityNodeInfo.obtain(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    // ── Global actions ────────────────────────────────────────────────

    fun globalAction(action: String): Boolean {
        val svc = service ?: return false
        val code = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent", "overview" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "notification" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "qs" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return false
            else -> return false
        }
        return svc.performGlobalAction(code)
    }

    // ── Screenshot (API 30+) ──────────────────────────────────────────

    fun takeScreenshotBitmap(timeoutMs: Long = 4000L): Bitmap? {
        val svc = service ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>(null)
        val main = Handler(Looper.getMainLooper())
        main.post {
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                { it.run() },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hw = screenshot.hardwareBuffer
                            val color = screenshot.colorSpace
                            result.set(Bitmap.wrapHardwareBuffer(hw, color)?.copy(Bitmap.Config.ARGB_8888, false))
                            hw.close()
                        } catch (_: Throwable) {
                            result.set(null)
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                },
            )
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result.get()
    }
}
