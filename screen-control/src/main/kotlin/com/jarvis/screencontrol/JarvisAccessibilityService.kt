package com.jarvis.screencontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.core.companion.ObservationSource
import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneContextBus
import com.jarvis.core.privacy.SensitivityClassifier
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.companion.Sensitivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Служба спец. возможностей Jarvis — «зрение» и «руки» на экране.
 *
 * Stage 4: richer observe (FG + window/activity + лёгкий content hint),
 * только perception → [PhoneContextBus]. Без execute/autonomy.
 * Dedup/coalesce дополнительно в [com.jarvis.core.companion.ContextAggregator].
 */
class JarvisAccessibilityService : AccessibilityService() {

    @Volatile private var lastFgPackage: String? = null
    @Volatile private var lastFgAtElapsed: Long = 0L
    @Volatile private var lastWindowKey: String? = null
    @Volatile private var lastWindowAtElapsed: Long = 0L
    @Volatile private var lastContentKey: String? = null
    @Volatile private var lastContentAtElapsed: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
            packageNames = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                AccessibilityServiceInfo::class.java
                    .getMethod("setAccessibilityTool", Boolean::class.javaPrimitiveType)
                    .invoke(info, true)
            }
        }
        serviceInfo = info
        ScreenController.bind(this)
        val winCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            windows?.size ?: -1
        } else {
            -1
        }
        val active = rootInActiveWindow?.packageName?.toString()
        Log.i(TAG, "Accessibility service connected windows=$winCount active=$active")
        if (!active.isNullOrBlank()) {
            publishAppForeground(active, "A11y connected")
        }
    }

    /**
     * Чтение дерева окна (rootInActiveWindow, обход детей) — это обращения к чужому процессу.
     * На главном потоке они вешали окно Джарвиса («не отвечает» в отчётах с Xiaomi и OPPO),
     * поэтому выполняем их на отдельном потоке и не копим очередь: пока один разбор идёт,
     * новые события того же вида пропускаем.
     */
    private val worker = HandlerThread("jarvis-a11y").apply { start() }
    private val workerHandler = Handler(worker.looper)
    private val windowsJobQueued = AtomicBoolean(false)
    private val contentJobQueued = AtomicBoolean(false)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowStateChanged(event)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Событие переиспользуется системой после возврата — берём из него строку сразу.
                val fallback = event.packageName?.toString()
                if (windowsJobQueued.compareAndSet(false, true)) {
                    workerHandler.post {
                        windowsJobQueued.set(false)
                        runCatching { onWindowsChanged(fallback) }
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
                if (pkg in IGNORED_PACKAGES) return
                if (SystemClock.elapsedRealtime() - lastContentAtElapsed < LOCAL_CONTENT_DEBOUNCE_MS) return
                if (contentJobQueued.compareAndSet(false, true)) {
                    workerHandler.post {
                        contentJobQueued.set(false)
                        runCatching { onWindowContentChanged(pkg) }
                    }
                }
            }
        }
    }

    private fun onWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (pkg in IGNORED_PACKAGES) return
        val now = SystemClock.elapsedRealtime()
        val className = event.className?.toString()?.substringAfterLast('.')
        if (pkg != lastFgPackage || now - lastFgAtElapsed >= LOCAL_FG_DEBOUNCE_MS) {
            lastFgPackage = pkg
            lastFgAtElapsed = now
            publishAppForeground(pkg, className)
        }
        val windowKey = "$pkg|${className.orEmpty()}"
        if (windowKey != lastWindowKey || now - lastWindowAtElapsed >= LOCAL_WINDOW_DEBOUNCE_MS) {
            lastWindowKey = windowKey
            lastWindowAtElapsed = now
            publishWindowChanged(pkg, className)
        }
    }

    private fun onWindowsChanged(eventPackage: String?) {
        // Только смена активного пакета через root — без спама.
        val pkg = rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?: eventPackage?.takeIf { it.isNotBlank() }
            ?: return
        if (pkg in IGNORED_PACKAGES) return
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastFgPackage && now - lastFgAtElapsed < LOCAL_FG_DEBOUNCE_MS) return
        lastFgPackage = pkg
        lastFgAtElapsed = now
        publishAppForeground(pkg, "windows_changed")
    }

    private fun onWindowContentChanged(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastContentAtElapsed < LOCAL_CONTENT_DEBOUNCE_MS) return
        val hint = lightweightContentHint(pkg)
        val key = "$pkg|$hint"
        if (key == lastContentKey) return
        lastContentKey = key
        lastContentAtElapsed = now
        // Содержимое чувствительного пакета не описываем вовсе — только факт смены окна.
        val sensitivePackage = SensitivityClassifier.isSensitivePackage(pkg)
        // Не дублируем APP_FOREGROUND — только WINDOW_CHANGED с коротким hint.
        PhoneContextBus.publish(
            PhoneObservation(
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.WINDOW_CHANGED,
                packageName = pkg,
                screenId = hint,
                summary = if (sensitivePackage) {
                    "Контент: $pkg · скрыто"
                } else {
                    "Контент: $pkg${hint?.let { " · $it" } ?: ""}"
                },
                entities = buildMap {
                    put("package", pkg)
                    put("kind", "content")
                    if (!hint.isNullOrBlank()) put("hint", hint.take(48))
                },
                sensitivity = if (sensitivePackage) Sensitivity.SECRET else Sensitivity.NORMAL,
                confidence = 0.55f,
            ),
        )
        Log.d(TAG, "WINDOW_CONTENT $pkg hint=$hint")
    }

    /**
     * Короткий текстовый намёк для события WINDOW_CONTENT.
     *
     * Возвращает null (событие публикуется без текста) если:
     * - пакет чувствительный (банк/платёжное/менеджер паролей/аутентификатор);
     * - на экране есть поле ввода пароля (isPassword);
     * - [SensitivityClassifier] считает текст SECRET (код, номер карты).
     */
    private fun lightweightContentHint(pkg: String): String? {
        if (SensitivityClassifier.isSensitivePackage(pkg)) return null
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != pkg) return null
        return runCatching {
            if (hasPasswordField(root, depth = 0)) return@runCatching null
            val title = root.contentDescription?.toString()?.trim().orEmpty()
                .ifBlank { root.text?.toString()?.trim().orEmpty() }
                .ifBlank {
                    // Первый текстовый ребёнок (ограниченный обход).
                    firstText(root, depth = 0)
                }
            if (SensitivityClassifier.classify(title, pkg) == Sensitivity.SECRET) {
                null
            } else {
                title.take(40).takeIf { it.isNotBlank() }
            }
        }.getOrNull().also {
            runCatching { root.recycle() }
        }
    }

    /** Поле пароля в ограниченном поддереве — текст не читаем вовсе. */
    private fun hasPasswordField(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 3) return false
        if (runCatching { node.isPassword }.getOrDefault(false)) return true
        for (i in 0 until node.childCount.coerceAtMost(6)) {
            val child = node.getChild(i) ?: continue
            val found = hasPasswordField(child, depth + 1)
            runCatching { child.recycle() }
            if (found) return true
        }
        return false
    }

    private fun firstText(node: AccessibilityNodeInfo, depth: Int): String {
        if (depth > 3) return ""
        val own = node.text?.toString()?.trim().orEmpty()
        if (own.length >= 2) return own
        for (i in 0 until node.childCount.coerceAtMost(6)) {
            val child = node.getChild(i) ?: continue
            val t = firstText(child, depth + 1)
            runCatching { child.recycle() }
            if (t.isNotBlank()) return t
        }
        return ""
    }

    private fun publishAppForeground(pkg: String, detail: String?) {
        PhoneContextBus.publish(
            PhoneObservation(
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.APP_FOREGROUND,
                packageName = pkg,
                screenId = detail,
                summary = "На переднем плане: $pkg",
                entities = buildMap {
                    put("package", pkg)
                    if (!detail.isNullOrBlank()) put("class", detail)
                },
                sensitivity = Sensitivity.NORMAL,
                confidence = 0.9f,
            ),
        )
        Log.d(TAG, "APP_FOREGROUND $pkg detail=$detail")
    }

    private fun publishWindowChanged(pkg: String, className: String?) {
        PhoneContextBus.publish(
            PhoneObservation(
                source = ObservationSource.ACCESSIBILITY,
                type = ObservationType.WINDOW_CHANGED,
                packageName = pkg,
                screenId = className,
                summary = "Окно: $pkg${className?.let { " · $it" } ?: ""}",
                entities = buildMap {
                    put("package", pkg)
                    put("kind", "window_state")
                    if (!className.isNullOrBlank()) put("class", className)
                },
                sensitivity = Sensitivity.NORMAL,
                confidence = 0.8f,
            ),
        )
        Log.d(TAG, "WINDOW_CHANGED $pkg class=$className")
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ScreenController.unbind(this)
        Log.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ScreenController.unbind(this)
        worker.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Jarvis.A11y"
        private const val LOCAL_FG_DEBOUNCE_MS = 700L
        private const val LOCAL_WINDOW_DEBOUNCE_MS = 1_200L
        private const val LOCAL_CONTENT_DEBOUNCE_MS = 4_000L
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.miui.home",
            "com.mi.android.globallauncher",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.lbe.security.miui",
        )
    }
}
