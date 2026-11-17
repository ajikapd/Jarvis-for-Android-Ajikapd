package com.jarvis.app.pro

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.jarvis.app.AndroidReminderBook
import com.jarvis.app.BrainGraph
import com.jarvis.app.PrefsCustomRoutines
import com.jarvis.app.PrefsNoteBook
import com.jarvis.app.VoiceCatalog
import com.jarvis.app.companion.CompanionMemory
import com.jarvis.core.memory.MemoryQuery
import com.jarvis.screencontrol.JarvisNotificationListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Данные телефона для экранов Pro. Каждый запрос — kind + аргументы JSON, ответ — JSON.
 * Ошибка доступа возвращается как {"need":"разрешение"} — экран показывает кнопку «Разрешить».
 */
class ProData(private val ctx: Context) {

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    private fun need(p: String) = JSONObject().put("need", p)
    private fun ok(msg: String? = null) = JSONObject().put("ok", true).apply { if (msg != null) put("msg", msg) }
    private val zone get() = ZoneId.systemDefault()

    fun handle(kind: String, a: JSONObject): JSONObject = try {
        when (kind) {
            "status" -> status()
            "reminders" -> reminders()
            "reminderAdd" -> reminderAdd(a)
            "reminderDel" -> ok().put("removed", AndroidReminderBook(ctx).remove(a.optLong("id")))
            "calendar" -> calendar(a.optInt("days", 7))
            "notes" -> notes()
            "noteAdd" -> { PrefsNoteBook(ctx).add(a.optString("list", "дела"), listOf(a.optString("item"))); ok() }
            "noteDel" -> ok().put("removed", PrefsNoteBook(ctx).remove(a.optString("list", "дела"), a.optString("item")))
            "notifs" -> notifs()
            "notifReply" -> ok().put("sent", JarvisNotificationListenerService.instanceReply(a.optString("key"), a.optString("text")))
            "notifDismiss" -> ok().put("done", JarvisNotificationListenerService.instanceDismiss(a.optString("key")))
            "notifAction" -> ok().put("done", JarvisNotificationListenerService.instanceAction(a.optString("key"), a.optInt("i")))
            "contacts" -> contacts()
            "routines" -> routines()
            "routineAdd" -> {
                PrefsCustomRoutines(ctx).put(a.optString("name"), a.optJSONArray("steps")?.let { j -> (0 until j.length()).map { j.optString(it) } }.orEmpty()); ok()
            }
            "routineDel" -> ok().put("removed", PrefsCustomRoutines(ctx).remove(a.optString("name")))
            "memory" -> memory()
            "memoryDel" -> ok().put("removed", CompanionMemory.get(ctx).repository().delete(a.optString("id")))
            "modes" -> modes()
            "modeSet" -> modeSet(a.optString("mode"))
            "voices" -> voices()
            "voiceSet" -> { VoiceCatalog.select(ctx, a.optString("id")); ok("Голос выбран. Он включится при следующем ответе.") }
            "bt" -> bluetooth()
            "spend" -> spend(a.optInt("days", 30))
            "tool" -> {
                val id = a.optString("id")
                // Только инструменты «на чтение»: SMS, звонки, Telegram, клики по экрану — через Джарвиса с подтверждением.
                if (id !in SAFE_TOOLS) JSONObject().put("err", "Инструмент $id недоступен с экрана") else tool(id, a.optJSONObject("args") ?: JSONObject())
            }
            "openApp" -> openApp(a)
            "dial" -> { start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", a.optString("phone"), null))); ok() }
            "smsTo" -> { start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", a.optString("phone"), null)).putExtra("sms_body", a.optString("text"))); ok() }
            "web" -> { val u = webUri(a.optString("url")) ?: return JSONObject().put("err", "Плохая ссылка"); start(Intent(Intent.ACTION_VIEW, u)); ok() }
            else -> JSONObject().put("err", "unknown $kind")
        }
    } catch (e: SecurityException) {
        JSONObject().put("err", "Нет разрешения")
    } catch (e: Throwable) {
        JSONObject().put("err", e.message ?: e.javaClass.simpleName)
    }

    /** Только http/https: intent:, file:, content: и javascript: со страницы не открываем. */
    private fun webUri(s: String): Uri? = Uri.parse(s.trim()).takeIf { it.scheme == "http" || it.scheme == "https" }

    private fun start(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun status(): JSONObject {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return JSONObject()
            .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", bm.isCharging)
            .put("notifAccess", JarvisNotificationListenerService.isConnected())
            .put("dndAccess", nm.isNotificationPolicyAccessGranted)
            .put("perms", JSONObject().apply {
                for ((k, p) in PERMS) put(k, this@ProData.has(p))
            })
    }

    private fun reminders(): JSONObject {
        val arr = JSONArray()
        AndroidReminderBook(ctx).all().sortedBy { it.at }.forEach {
            arr.put(JSONObject().put("id", it.id).put("at", it.at.atZone(zone).toInstant().toEpochMilli()).put("text", it.text).put("daily", it.daily))
        }
        return JSONObject().put("items", arr)
    }

    private fun reminderAdd(a: JSONObject): JSONObject {
        if (a.optString("text").isBlank() || a.optLong("at") <= 0L) return JSONObject().put("err", "Нет текста или времени")
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(a.optLong("at")), zone)
        val r = AndroidReminderBook(ctx).add(at, a.optString("text"), a.optBoolean("daily"), false)
        return ok().put("id", r.id)
    }

    private fun calendar(days: Int): JSONObject {
        if (!has(Manifest.permission.READ_CALENDAR)) return need("calendar")
        val now = System.currentTimeMillis()
        val start = java.time.LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start)
            android.content.ContentUris.appendId(it, now + days * 86_400_000L)
        }.build()
        val arr = JSONArray()
        ctx.contentResolver.query(
            uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
            null, null, CalendarContract.Instances.BEGIN + " ASC",
        )?.use { c ->
            while (c.moveToNext() && arr.length() < 60) {
                arr.put(JSONObject().put("title", c.getString(0) ?: "").put("begin", c.getLong(1)).put("end", c.getLong(2))
                    .put("loc", c.getString(3) ?: "").put("allDay", c.getInt(4) == 1))
            }
        }
        return JSONObject().put("items", arr)
    }

    private fun notes(): JSONObject {
        val b = PrefsNoteBook(ctx)
        val o = JSONObject()
        for (l in listOf("покупки", "дела", "заметки")) o.put(l, JSONArray(b.items(l)))
        return JSONObject().put("lists", o)
    }

    private fun appName(pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun notifs(): JSONObject {
        if (!JarvisNotificationListenerService.isConnected()) return need("notifications")
        val arr = JSONArray()
        JarvisNotificationListenerService.snapshot()
            .filter { it.packageName != ctx.packageName && it.title.isNotBlank() }
            .sortedByDescending { it.postTime }
            .take(40)
            .forEach { n ->
                arr.put(JSONObject().put("key", n.key).put("pkg", n.packageName).put("app", appName(n.packageName))
                    .put("title", n.title).put("text", n.text).put("time", n.postTime)
                    .put("canReply", JarvisNotificationListenerService.canReply(n.key))
                    .put("actions", JSONArray(n.actions.map { it.title })))
            }
        return JSONObject().put("items", arr)
    }

    @SuppressLint("Range")
    private fun contacts(): JSONObject {
        if (!has(Manifest.permission.READ_CONTACTS)) return need("contacts")
        val arr = JSONArray()
        val seen = HashSet<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.STARRED,
                ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED, ContactsContract.CommonDataKinds.Phone.LAST_TIME_CONTACTED),
            null, null,
            ContactsContract.CommonDataKinds.Phone.STARRED + " DESC, " + ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { c ->
            while (c.moveToNext() && arr.length() < 200) {
                val id = c.getString(0) ?: continue
                if (!seen.add(id)) continue
                arr.put(JSONObject().put("id", id).put("name", c.getString(1) ?: "").put("phone", c.getString(2) ?: "")
                    .put("starred", c.getInt(3) == 1))
            }
        }
        // дни рождения
        val bd = HashMap<String, String>()
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Event.CONTACT_ID, ContactsContract.CommonDataKinds.Event.START_DATE),
            ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.CommonDataKinds.Event.TYPE + "=" + ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY,
            arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE), null,
        )?.use { c -> while (c.moveToNext()) bd[c.getString(0) ?: ""] = c.getString(1) ?: "" }
        // последние звонки
        val calls = HashMap<String, JSONObject>()
        if (has(Manifest.permission.READ_CALL_LOG)) {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                null, null, CallLog.Calls.DATE + " DESC",
            )?.use { c ->
                var n = 0
                while (c.moveToNext() && n++ < 300) {
                    val name = c.getString(0) ?: continue
                    if (!calls.containsKey(name)) calls[name] = JSONObject().put("at", c.getLong(1)).put("dur", c.getLong(2)).put("type", c.getInt(3))
                }
            }
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            bd[o.getString("id")]?.let { o.put("birthday", it) }
            calls[o.getString("name")]?.let { o.put("lastCall", it) }
        }
        return JSONObject().put("items", arr).put("callLog", has(Manifest.permission.READ_CALL_LOG))
    }

    private fun routines(): JSONObject {
        val r = PrefsCustomRoutines(ctx)
        val arr = JSONArray()
        r.names().forEach { n -> arr.put(JSONObject().put("name", n).put("steps", JSONArray(r.steps(n).orEmpty()))) }
        return JSONObject().put("items", arr)
    }

    private fun memory(): JSONObject {
        val arr = JSONArray()
        CompanionMemory.get(ctx).repository().query(MemoryQuery(limit = 100)).sortedByDescending { it.updatedAtMillis }.forEach {
            arr.put(JSONObject().put("id", it.id).put("text", it.content).put("kind", it.type.name).put("at", it.updatedAtMillis))
        }
        return JSONObject().put("items", arr)
    }

    private fun modes(): JSONObject {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val am = ctx.getSystemService(AudioManager::class.java)
        return JSONObject()
            .put("dnd", nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
            .put("dndAccess", nm.isNotificationPolicyAccessGranted)
            .put("ringer", am.ringerMode)
            .put("mode", ctx.getSharedPreferences("jarvis_pro", Context.MODE_PRIVATE).getString("pro_mode", "normal"))
            .put("writeSettings", Settings.System.canWrite(ctx))
    }

    /** Режимы: тишина, громкость, яркость — по-настоящему. */
    private fun modeSet(mode: String): JSONObject {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val am = ctx.getSystemService(AudioManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted && mode != "normal") {
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return need("dnd")
        }
        fun bright(v: Int) { if (Settings.System.canWrite(ctx)) runCatching {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v) } }
        when (mode) {
            "sleep" -> { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY); bright(20) }
            "work" -> { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY); am.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
            "drive" -> { if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL); am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.7).toInt(), 0) }
            "focus" -> { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE) }
            // без доступа к «Не беспокоить» смена звонка при включённой тишине бросает SecurityException
            else -> { if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL); runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } }
        }
        ctx.getSharedPreferences("jarvis_pro", Context.MODE_PRIVATE).edit().putString("pro_mode", mode).apply()
        return ok().put("mode", mode)
    }

    private fun voices(): JSONObject {
        val sel = VoiceCatalog.selectedId(ctx)
        val arr = JSONArray()
        VoiceCatalog.ALL.forEach { v ->
            // Вшитые (downloadId == null): фирменный и «из фильма» — всегда выбираемы; clips в APK.
            val ready = VoiceCatalog.isReady(ctx, v) || v.downloadId == null
            arr.put(JSONObject().put("id", v.id).put("name", v.title).put("about", v.about).put("on", v.id == sel)
                .put("ready", ready).put("download", v.downloadId ?: ""))
        }
        return JSONObject().put("items", arr)
    }

    @SuppressLint("MissingPermission")
    private fun bluetooth(): JSONObject {
        if (android.os.Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) return need("bluetooth")
        val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return JSONObject().put("items", JSONArray()).put("off", true)
        val am = ctx.getSystemService(AudioManager::class.java)
        val connectedNames = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.productName.toString() }.toSet()
        val arr = JSONArray()
        ad.bondedDevices.orEmpty().forEach { d ->
            val cls = d.bluetoothClass?.majorDeviceClass ?: 0
            val kind = when (cls) {
                android.bluetooth.BluetoothClass.Device.Major.WEARABLE -> "watch"
                android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO ->
                    if ((d.name ?: "").contains(Regex("(?i)car|auto|авто|kia|hyundai|toyota|lada|skoda|vw|bmw|audi|mazda|renault|haval|chery|geely"))) "car" else "audio"
                else -> "other"
            }
            arr.put(JSONObject().put("name", d.name ?: d.address).put("addr", d.address).put("kind", kind)
                .put("connected", connectedNames.contains(d.name)))
        }
        return JSONObject().put("items", arr).put("on", ad.isEnabled)
    }

    /** Расходы из SMS банков: «Покупка 450р», «Списание 1 200,00 RUB», «Оплата … 399 ₽». */
    private fun spend(days: Int): JSONObject {
        if (!has(Manifest.permission.READ_SMS)) return need("sms")
        val from = System.currentTimeMillis() - days * 86_400_000L
        val arr = JSONArray()
        val spendRx = Regex("(?i)(покупка|оплата|списание|перевод|снятие|pokupka|oplata|spisanie)")
        val incomeRx = Regex("(?i)(зачисление|поступление|пополнение|возврат)")
        val sumRx = Regex("(\\d[\\d\\s]{0,9}(?:[.,]\\d{1,2})?)\\s?(?:р\\b|руб|₽|rub|rur)", RegexOption.IGNORE_CASE)
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            Telephony.Sms.DATE + ">?", arrayOf(from.toString()), Telephony.Sms.DATE + " DESC",
        )?.use { c ->
            while (c.moveToNext() && arr.length() < 300) {
                val body = c.getString(1) ?: continue
                val income = incomeRx.containsMatchIn(body)
                if (!income && !spendRx.containsMatchIn(body)) continue
                val m = sumRx.find(body) ?: continue
                val sum = m.groupValues[1].replace(Regex("\\s"), "").replace(',', '.').toDoubleOrNull() ?: continue
                val where = body.substring(m.range.last + 1).trim().take(40)
                arr.put(JSONObject().put("bank", c.getString(0) ?: "").put("sum", sum).put("income", income)
                    .put("where", where).put("at", c.getLong(2)))
            }
        }
        return JSONObject().put("items", arr)
    }

    private fun tool(id: String, args: JSONObject): JSONObject {
        val map = HashMap<String, Any>()
        args.keys().forEach { k -> map[k] = args.get(k) }
        val r = BrainGraph.runTool(ctx, id, map)
        return JSONObject().put("status", r.status.name).put("msg", r.message ?: "")
    }

    private fun openApp(a: JSONObject): JSONObject {
        val pm = ctx.packageManager
        for (p in a.optString("pkg").split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            pm.getLaunchIntentForPackage(p)?.let { start(it); return ok().put("opened", p) }
        }
        val url = a.optString("url")
        webUri(url)?.let { start(Intent(Intent.ACTION_VIEW, it)); return ok().put("opened", url) }
        return JSONObject().put("err", "Приложение не установлено")
    }

    companion object {
        private val SAFE_TOOLS = setOf("weather", "briefing", "web.search", "web.answer", "rates", "news", "system.status")
        val PERMS = mapOf(
            "calendar" to Manifest.permission.READ_CALENDAR,
            "contacts" to Manifest.permission.READ_CONTACTS,
            "calllog" to Manifest.permission.READ_CALL_LOG,
            "sms" to Manifest.permission.READ_SMS,
            "bluetooth" to Manifest.permission.BLUETOOTH_CONNECT,
            "location" to Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
