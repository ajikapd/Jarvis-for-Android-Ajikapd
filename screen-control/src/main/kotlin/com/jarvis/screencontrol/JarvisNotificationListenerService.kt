package com.jarvis.screencontrol

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jarvis.core.announce.IncomingMessage
import android.util.Log
import com.jarvis.core.companion.ObservationSource
import com.jarvis.core.companion.ObservationType
import com.jarvis.core.companion.PhoneContextBus
import com.jarvis.core.companion.PhoneObservation
import com.jarvis.core.companion.Sensitivity
import java.util.LinkedHashMap

/**
 * Слушает активные уведомления. Снимок доступен через [snapshot].
 * Поддерживает текстовый reply через [RemoteInput].
 *
 * Companion Stage 3: posted/removed → [PhoneContextBus] (с фильтром, без полного текста).
 *
 * Приватность: кэш ограничен [NotificationPrivacy.MAX_CACHE_ENTRIES] записями (FIFO),
 * текст/заголовок проходят через [NotificationPrivacy.sanitize]; содержимое
 * уведомлений чувствительных пакетов в кэш не попадает.
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    @Volatile
    private var lastPostedKey: String? = null

    @Volatile
    private var lastPostedAtElapsed: Long = 0L

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "Notification listener connected")
        refresh()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val info = toInfo(sbn)
        synchronized(cache) { cache[keyOf(sbn)] = info }
        publishToBus(sbn, posted = true)
        notifyMessage(sbn, info)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        synchronized(cache) { cache.remove(keyOf(sbn)) }
        publishToBus(sbn, posted = false)
    }

    private fun notifyMessage(sbn: StatusBarNotification, info: NotificationInfo) {
        val listener = messageListener ?: return
        if (shouldIgnore(sbn) || sbn.isOngoing) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val group = sbn.notification.extras?.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) == true
        listener(keyOf(sbn), IncomingMessage(sbn.packageName.orEmpty(), info.title, info.text, group))
    }

    private fun publishToBus(sbn: StatusBarNotification, posted: Boolean) {
        if (shouldIgnore(sbn)) return
        val key = keyOf(sbn)
        if (posted) {
            val now = SystemClock.elapsedRealtime()
            if (key == lastPostedKey && now - lastPostedAtElapsed < 1_200L) return
            lastPostedKey = key
            lastPostedAtElapsed = now
        }
        val pkg = sbn.packageName.orEmpty().ifBlank { return }
        val sensitive = com.jarvis.core.privacy.SensitivityClassifier.isSensitivePackage(pkg)
        val shortTitle = NotificationPrivacy.summaryTitle(
            sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE),
            pkg,
        )
        val summary = if (posted) {
            when {
                sensitive -> "Уведомление: $pkg · скрыто"
                shortTitle.isNotBlank() -> "Уведомление: $pkg · $shortTitle"
                else -> "Уведомление: $pkg"
            }
        } else {
            "Уведомление снято: $pkg"
        }
        PhoneContextBus.publish(
            PhoneObservation(
                source = ObservationSource.NOTIFICATION_LISTENER,
                type = if (posted) ObservationType.NOTIFICATION_POSTED else ObservationType.NOTIFICATION_REMOVED,
                packageName = pkg,
                summary = summary,
                entities = mapOf(
                    "package" to pkg,
                    "ongoing" to sbn.isOngoing.toString(),
                    "has_title" to (shortTitle.isNotBlank()).toString(),
                ),
                sensitivity = if (sensitive) Sensitivity.SECRET else Sensitivity.PRIVATE,
                confidence = 0.85f,
            ),
        )
        Log.d(TAG, "${if (posted) "POSTED" else "REMOVED"} $pkg")
    }

    private fun shouldIgnore(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return true
        val n = sbn.notification
        val flags = n.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        if (sbn.isOngoing && n.category == Notification.CATEGORY_SERVICE) return true
        // Системный шум без полезного контекста
        val pkg = sbn.packageName.orEmpty()
        if (pkg == "android" || pkg.startsWith("com.android.systemui")) return true
        return false
    }

    fun refresh() {
        val fresh = activeNotifications?.map { keyOf(it) to toInfo(it) }.orEmpty()
        synchronized(cache) {
            cache.clear()
            fresh.forEach { (k, v) -> cache[k] = v }
        }
    }

    fun list(): List<NotificationInfo> =
        synchronized(cache) { cache.values.sortedByDescending { it.postTime } }

    fun dismiss(key: String): Boolean = try {
        cancelNotification(key)
        synchronized(cache) { cache.remove(key) }
        true
    } catch (_: Throwable) {
        false
    }

    fun clearAll(): Boolean = try {
        cancelAllNotifications()
        synchronized(cache) { cache.clear() }
        true
    } catch (_: Throwable) {
        false
    }

    fun sendAction(key: String, actionIndex: Int): Boolean {
        val info = synchronized(cache) { cache[key] } ?: return false
        val action = info.actions.getOrNull(actionIndex) ?: return false
        val pi = action.actionIntent ?: return false
        return try {
            pi.send()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Ответ текстом в уведомление (WhatsApp/Telegram/SMS и т.п.). */
    fun replyText(key: String, text: String): Boolean {
        if (text.isBlank()) return false
        val sbn = activeNotifications?.firstOrNull { it.key == key } ?: run {
            refresh()
            return false
        }
        val actions = sbn.notification.actions ?: return false
        val action = actions.firstOrNull { a ->
            a.remoteInputs?.any { true } == true
        } ?: return false
        val remoteInputs = action.remoteInputs ?: return false
        val fill = Intent()
        val results = Bundle()
        for (ri in remoteInputs) {
            results.putCharSequence(ri.resultKey, text)
        }
        RemoteInput.addResultsToIntent(remoteInputs, fill, results)
        return try {
            action.actionIntent?.send(this, 0, fill)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "replyText failed: ${t.message}")
            false
        }
    }

    private fun keyOf(sbn: StatusBarNotification): String = sbn.key

    private fun toInfo(sbn: StatusBarNotification): NotificationInfo {
        val n = sbn.notification
        val extras = n.extras
        val title = NotificationPrivacy.sanitize(extras?.getCharSequence(Notification.EXTRA_TITLE), sbn.packageName)
        val text = NotificationPrivacy.sanitize(extras?.getCharSequence(Notification.EXTRA_TEXT), sbn.packageName)
        val actions = n.actions?.mapIndexed { i, a ->
            NotificationActionInfo(
                index = i,
                title = NotificationPrivacy.sanitize(a.title, sbn.packageName),
                actionIntent = a.actionIntent,
                canReply = a.remoteInputs?.isNotEmpty() == true,
            )
        }.orEmpty()
        return NotificationInfo(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            text = text,
            postTime = sbn.postTime,
            actions = actions,
        )
    }

    companion object {
        private const val TAG = "Jarvis.NLS"
        @Volatile var instance: JarvisNotificationListenerService? = null
            private set

        /** Сообщения из мессенджеров для голосового объявления. */
        @Volatile var messageListener: ((String, IncomingMessage) -> Unit)? = null

        /** На что отвечать по фразе «ответь, что понял». */
        @Volatile var lastAnnouncedKey: String? = null

        /** FIFO-кэш: вытесняется самая старая запись, содержимое уже обезличено. */
        private val cache = object : LinkedHashMap<String, NotificationInfo>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NotificationInfo>?): Boolean =
                size > NotificationPrivacy.MAX_CACHE_ENTRIES
        }

        fun isConnected(): Boolean = instance != null

        fun snapshot(): List<NotificationInfo> {
            instance?.refresh()
            return instance?.list().orEmpty()
        }
    }
}

data class NotificationInfo(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val actions: List<NotificationActionInfo>,
)

data class NotificationActionInfo(
    val index: Int,
    val title: String,
    val actionIntent: android.app.PendingIntent?,
    val canReply: Boolean = false,
)
