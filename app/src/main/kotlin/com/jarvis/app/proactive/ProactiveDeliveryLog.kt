package com.jarvis.app.proactive

import android.content.Context
import com.jarvis.core.proactive.DeliveryMode
import com.jarvis.core.proactive.DeliveryReason
import com.jarvis.core.privacy.SensitivityClassifier
import org.json.JSONArray
import org.json.JSONObject

/**
 * Б3: журнал доставки — SharedPreferences, кольцо на 100 записей.
 *
 * Тексты предложений не хранятся: только mode/reason-код и маскированный
 * короткий reason. Журнал нужен для дедупликации, отсрочки «Позже» и
 * диагностики.
 */
object ProactiveDeliveryLog {

    data class Entry(
        val ts: Long,
        val deliveryId: String,
        val fingerprint: String?,
        val patternKey: String?,
        val mode: String,
        val reason: String,
        val reasonText: String,
        val voiceShown: Boolean,
        val userAction: String?,
        val snoozeUntil: Long?,
    )

    private const val PREFS = "jarvis_proactive_delivery_v1"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 100
    private const val MAX_REASON_CHARS = 100

    private val lock = Any()

    fun append(
        context: Context,
        deliveryId: String,
        fingerprint: String?,
        patternKey: String?,
        mode: DeliveryMode,
        reason: DeliveryReason,
        reasonText: String,
        voiceShown: Boolean,
        userAction: String? = null,
        snoozeUntil: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): Entry {
        val entry = Entry(
            ts = now,
            deliveryId = deliveryId,
            fingerprint = fingerprint,
            patternKey = patternKey,
            mode = mode.name,
            reason = reason.name,
            reasonText = SensitivityClassifier.mask(reasonText).take(MAX_REASON_CHARS),
            voiceShown = voiceShown,
            userAction = userAction,
            snoozeUntil = snoozeUntil,
        )
        synchronized(lock) {
            val all = readRaw(context).toMutableList()
            all += entry
            while (all.size > MAX_ENTRIES) all.removeAt(0)
            writeRaw(context, all)
        }
        return entry
    }

    fun all(context: Context): List<Entry> = synchronized(lock) {
        readRaw(context)
    }

    fun find(context: Context, deliveryId: String): Entry? = synchronized(lock) {
        readRaw(context).lastOrNull { it.deliveryId == deliveryId }
    }

    /** Уже показывали это предложение (любая не-SUPPRESS запись). */
    fun hasDelivered(context: Context, fingerprint: String?): Boolean {
        if (fingerprint.isNullOrBlank()) return false
        return synchronized(lock) {
            readRaw(context).any {
                it.fingerprint == fingerprint && it.mode != DeliveryMode.SUPPRESS.name
            }
        }
    }

    /** Пользователь нажал «Позже», и час ещё не прошёл. */
    fun isSnoozed(
        context: Context,
        fingerprint: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (fingerprint.isNullOrBlank()) return false
        return synchronized(lock) {
            readRaw(context).any {
                it.fingerprint == fingerprint &&
                    it.userAction == ACTION_LATER &&
                    (it.snoozeUntil ?: 0L) > now
            }
        }
    }

    fun setAction(
        context: Context,
        deliveryId: String,
        action: String,
        snoozeUntil: Long? = null,
    ) {
        synchronized(lock) {
            val all = readRaw(context).map {
                if (it.deliveryId == deliveryId) {
                    it.copy(userAction = action, snoozeUntil = snoozeUntil)
                } else {
                    it
                }
            }
            writeRaw(context, all)
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ENTRIES)
                .apply()
        }
    }

    fun deliveryIds(context: Context): List<String> = all(context).map { it.deliveryId }.distinct()

    private fun readRaw(context: Context): List<Entry> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        Entry(
                            ts = o.optLong("ts"),
                            deliveryId = o.optString("deliveryId"),
                            fingerprint = o.optString("fingerprint").takeIf { it.isNotBlank() },
                            patternKey = o.optString("patternKey").takeIf { it.isNotBlank() },
                            mode = o.optString("mode"),
                            reason = o.optString("reason"),
                            reasonText = o.optString("reasonText"),
                            voiceShown = o.optBoolean("voiceShown"),
                            userAction = o.optString("userAction").takeIf { it.isNotBlank() },
                            snoozeUntil = if (o.has("snoozeUntil")) o.optLong("snoozeUntil") else null,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeRaw(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("ts", e.ts)
                    put("deliveryId", e.deliveryId)
                    put("fingerprint", e.fingerprint ?: "")
                    put("patternKey", e.patternKey ?: "")
                    put("mode", e.mode)
                    put("reason", e.reason)
                    put("reasonText", e.reasonText)
                    put("voiceShown", e.voiceShown)
                    put("userAction", e.userAction ?: "")
                    e.snoozeUntil?.let { put("snoozeUntil", it) }
                },
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, array.toString())
            .apply()
    }

    const val ACTION_YES = "yes"
    const val ACTION_NO = "no"
    const val ACTION_LATER = "later"
}
