package com.jarvis.app.pro

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Лицензия Jarvis Pro: ключ выдаёт Telegram-бот @jarvis_ajikapd_bot после оплаты.
 *
 * Ключ: JP1.<код телефона>.<M|L>.<день окончания>.<подпись>
 *  - код телефона — 8 символов, считается из ANDROID_ID: ключ работает только на этом телефоне;
 *  - M — подписка (день окончания = дни с 1970 года), L — навсегда (99999);
 *  - подпись ECDSA P-256: подделать без секретного ключа бота невозможно.
 * Проверка без интернета. Перевод часов назад не помогает: запоминаем самое позднее виденное время.
 */
object ProLicense {
    /** Открытый ключ бота (data/pro-public.txt). Меняется только вместе с секретным ключом бота. */
    private const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEgqFVTHZr9ulsiWSxeTX2JHiPOql4SM5owBuT+p32Vr5+yQ9IX27mDyvbbtqOSc2DAahshUBCZbTuqd8PercYoQ=="
    private const val PKG = "com.jarvis.pro"
    const val BOT = "jarvis_ajikapd_bot"
    private const val PREFS = "pro_license"
    private const val DAY_MS = 86_400_000L
    private const val FOREVER = 99999L

    enum class Status { OK, NO_KEY, WRONG_PHONE, EXPIRED, BAD_KEY, CLOCK }

    data class Info(val status: Status, val forever: Boolean = false, val expDay: Long = 0) {
        val active get() = status == Status.OK
    }

    /** Код этого телефона, например «ABCD-EFGH». */
    @SuppressLint("HardwareIds")
    fun deviceCode(ctx: Context): String {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "no-id"
        val hash = MessageDigest.getInstance("SHA-256").digest("$id|$PKG".toByteArray())
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buf = 0; var bits = 0; var i = 0
        while (sb.length < 8) {
            buf = (buf shl 8) or (hash[i++].toInt() and 0xFF); bits += 8
            while (bits >= 5 && sb.length < 8) { bits -= 5; sb.append(abc[(buf shr bits) and 31]) }
        }
        return sb.toString()
    }
    fun prettyCode(ctx: Context) = deviceCode(ctx).let { it.substring(0, 4) + "-" + it.substring(4) }

    fun savedKey(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("key", null)

    /** Проверить ключ; при успехе сохранить. */
    fun activate(ctx: Context, key: String): Info {
        val info = check(ctx, key.trim())
        if (info.active) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("key", key.trim()).apply()
        return info
    }

    fun current(ctx: Context): Info = savedKey(ctx)?.let { check(ctx, it) } ?: Info(Status.NO_KEY)
    fun isActive(ctx: Context) = current(ctx).active

    /** Дней до конца (для подписки), -1 — навсегда. */
    fun daysLeft(info: Info): Long = if (info.forever) -1 else info.expDay - System.currentTimeMillis() / DAY_MS

    private fun check(ctx: Context, key: String): Info {
        val p = key.split('.')
        if (p.size != 5 || p[0] != "JP1" || (p[2] != "M" && p[2] != "L")) return Info(Status.BAD_KEY)
        val exp = p[3].toLongOrNull() ?: return Info(Status.BAD_KEY)
        if (!signatureOk("$PKG|${p[0]}.${p[1]}.${p[2]}.${p[3]}", p[4])) return Info(Status.BAD_KEY)
        if (p[1] != deviceCode(ctx)) return Info(Status.WRONG_PHONE)
        val forever = p[2] == "L" && exp == FOREVER
        if (forever) return Info(Status.OK, true, exp)
        val now = guardedNow(ctx) ?: return Info(Status.CLOCK, false, exp)
        return if (now / DAY_MS <= exp) Info(Status.OK, false, exp) else Info(Status.EXPIRED, false, exp)
    }

    /** Текущее время; null — часы переведены назад больше чем на 2 дня. */
    private fun guardedNow(ctx: Context): Long? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val seen = sp.getLong("seen", 0L)
        if (now > seen) sp.edit().putLong("seen", now).apply()
        return if (seen - now > 2 * DAY_MS) null else now
    }

    private fun signatureOk(message: String, sigB64: String): Boolean = try {
        val raw = Base64.decode(sigB64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update(message.toByteArray())
            raw.size == 64 && verify(rawToDer(raw))
        }
    } catch (e: Exception) { false }

    /** Подпись бота — 64 байта (r||s); Android ждёт формат DER. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        fun part(b: ByteArray): ByteArray {
            var s = 0
            while (s < b.size - 1 && b[s] == 0.toByte()) s++
            val v = b.copyOfRange(s, b.size)
            return if (v[0].toInt() and 0x80 != 0) byteArrayOf(0) + v else v
        }
        val r = part(raw.copyOfRange(0, 32)); val s = part(raw.copyOfRange(32, 64))
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
