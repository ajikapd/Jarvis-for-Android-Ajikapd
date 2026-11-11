package com.jarvis.app

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import com.jarvis.app.proactive.PhoneStateReader
import com.jarvis.app.proactive.ProactiveVoice
import com.jarvis.core.announce.CallAnnouncer
import com.jarvis.core.announce.IncomingMessage
import com.jarvis.core.announce.MessageAnnouncer
import com.jarvis.core.announce.QuietHours
import com.jarvis.core.common.model.UserRequest
import com.jarvis.core.proactive.InterruptionKind
import com.jarvis.core.proactive.RingerKind
import com.jarvis.screencontrol.JarvisNotificationListenerService
import java.util.Calendar

/** Всё, что Джарвис говорит сам, пока слушает: звонки, сообщения, встречи, сводка после будильника. */
class AnnouncementWatcher(context: Context) {
    private val ctx = context.applicationContext
    // Календарь и будильники опрашиваем не на главном потоке: на слабых телефонах запрос
    // к календарю занимал секунды и вешал окно (ANR на Redmi 8).
    private val thread = HandlerThread("jarvis-announce").apply { start() }
    private val handler = Handler(thread.looper)
    private val messages = MessageAnnouncer()
    private val announcedEvents = HashSet<String>()
    private var announcedRing = false
    private var awaitedAlarmAt = 0L
    private var briefedAlarmAt = 0L

    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state != TelephonyManager.EXTRA_STATE_RINGING) {
                announcedRing = false
                return
            }
            // Система шлёт событие дважды, номер есть только в одном из них.
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (announcedRing || number == null || !JarvisSettings.announceCalls(ctx)) return
            announcedRing = true
            ProactiveVoice.speak(ctx, CallAnnouncer.phrase(contactName(number)))
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            runCatching { checkEvents() }.onFailure { Log.w(TAG, "calendar: ${it.message}") }
            runCatching { checkAlarm() }.onFailure { Log.w(TAG, "alarm: ${it.message}") }
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        ctx.registerReceiver(callReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        JarvisNotificationListenerService.messageListener = ::onMessage
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        runCatching { ctx.unregisterReceiver(callReceiver) }
        JarvisNotificationListenerService.messageListener = null
        handler.removeCallbacks(tick)
        thread.quitSafely()
    }

    private fun onMessage(key: String, message: IncomingMessage) {
        if (!JarvisSettings.announceMessages(ctx) || !mayTalk() || QuietHours.isQuiet(hourNow())) return
        val phrase = messages.phrase(message, System.currentTimeMillis()) ?: return
        if (ProactiveVoice.speak(ctx, phrase).requested) JarvisNotificationListenerService.lastAnnouncedKey = key
    }

    /** Беззвучный режим и «Не беспокоить» значат, что сейчас говорить нельзя. */
    private fun mayTalk(): Boolean {
        val phone = PhoneStateReader.read(ctx)
        val filterOk = phone.interruptionFilter == InterruptionKind.ALL ||
            phone.interruptionFilter == InterruptionKind.UNKNOWN
        return phone.ringerMode == RingerKind.NORMAL && filterOk && !phone.callActive
    }

    private fun hourNow() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    private fun contactName(number: String): String? {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun checkEvents() {
        if (!JarvisSettings.announceHints(ctx) || !mayTalk()) return
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uri, now)
        ContentUris.appendId(uri, now + EVENT_LEAD_MS)
        ctx.contentResolver.query(
            uri.build(),
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(2) != 0) continue
                val begin = c.getLong(1)
                val title = c.getString(0).orEmpty().ifBlank { "встреча" }
                if (begin >= now && announcedEvents.add("$begin|$title")) {
                    val minutes = ((begin - now) / MINUTE_MS).coerceAtLeast(1)
                    ProactiveVoice.speak(ctx, "Сэр, через $minutes мин: $title.")
                    return
                }
            }
        }
    }

    /** Подходит к любому приложению часов: смотрим на ближайший системный будильник. */
    private fun checkAlarm() {
        val manager = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val next = manager.nextAlarmClock
        if (next != null && next.showIntent?.creatorPackage != ctx.packageName && next.triggerTime > now) {
            awaitedAlarmAt = next.triggerTime
        }
        val passed = now - awaitedAlarmAt
        val due = awaitedAlarmAt != 0L && passed in BRIEF_AFTER_MS..BRIEF_UNTIL_MS && briefedAlarmAt != awaitedAlarmAt
        if (!due || hourNow() !in MORNING || !JarvisSettings.announceHints(ctx) || !mayTalk()) return
        briefedAlarmAt = awaitedAlarmAt
        Thread({
            val reply = runCatching { BrainGraph.build(ctx).process(UserRequest("сводка на день")).replyText }.getOrNull()
            if (!reply.isNullOrBlank()) ProactiveVoice.speak(ctx, reply)
        }, "jarvis-briefing").start()
    }

    private companion object {
        const val TAG = "Jarvis.Announce"
        const val MINUTE_MS = 60_000L
        const val TICK_MS = MINUTE_MS
        const val EVENT_LEAD_MS = 15 * MINUTE_MS
        const val BRIEF_AFTER_MS = 2 * MINUTE_MS
        const val BRIEF_UNTIL_MS = 30 * MINUTE_MS
        val MORNING = 4..11
    }
}
