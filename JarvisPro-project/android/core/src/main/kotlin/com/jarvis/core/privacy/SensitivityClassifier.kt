package com.jarvis.core.privacy

import com.jarvis.core.companion.Sensitivity

/**
 * Эвристический классификатор чувствительности текста. Чистый Kotlin, без Android.
 *
 * Задача: до записи наблюдений и до отправки чего-либо в облако понять, содержит ли
 * строка секрет (одноразовый код, номер карты, содержимое банковского приложения)
 * или приватные данные (баланс).
 *
 * Переиспользуется существующий [Sensitivity] (PUBLIC/NORMAL/PRIVATE/SECRET):
 * новых значений не нужно — SECRET уже означает «не хранить и не отправлять»,
 * PRIVATE — «локально и с ограничением».
 *
 * Классификатор намеренно консервативен по цифрам: телефонные номера, время, даты,
 * цены, годы и версии не должны попадать в SECRET.
 */
object SensitivityClassifier {

    /** Замена секретного фрагмента. */
    const val MASK: String = "[скрыто]"

    // ────────────────────────────────────────────────────────────── пакеты

    /**
     * Заведомо чувствительные пакеты (банки, платёжные приложения, менеджеры паролей,
     * аутентификаторы). Список НЕПОЛНЫЙ — это нормально: ниже есть эвристика по
     * подстрокам, а сам набор дополняется по мере появления новых приложений.
     */
    private val sensitivePackages: Set<String> = setOf(
        // банки / платежи (RU)
        "ru.sberbankmobile",
        "ru.sberbankmobile.invest",
        "com.idamob.tinkoff.android",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "ru.rostel",
        "ru.raiffeisennews",
        "ru.rshb.mobilebank",
        "ru.gazprombank.android.mobilebank",
        "ru.mkb.mobilebank",
        "ru.letobank",
        "ru.psbank",
        "ru.yandex.money",
        "com.yoo.money",
        // платёжные (международные)
        "com.paypal.android.p2pmobile",
        "com.google.android.apps.walletnfcrel",
        // менеджеры паролей
        "com.x8bit.bitwarden",
        "com.lastpass.lpandroid",
        "com.android.keepass",
        "keepass2android.keepass2android",
        "com.onepassword.android",
        "com.agilebits.onepassword",
        // аутентификаторы / OTP
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "org.fedorahosted.freeotp",
        "com.beemdevelopment.aegis",
    )

    /** Эвристика по подстрокам: список брендов/категорий заведомо неполный. */
    private val sensitivePackageSubstrings: List<String> = listOf(
        "bank", "sber", "tinkoff", "tbank", "vtb", "alfa", "gazprom", "raiffeisen",
        "rshb", "rosbank", "pay", "wallet", "otp", "authenticator", "keepass", "password",
    )

    fun isSensitivePackage(packageName: String?): Boolean {
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        if (pkg.isEmpty()) return false
        if (pkg in sensitivePackages) return true
        return sensitivePackageSubstrings.any { pkg.contains(it) }
    }

    // ──────────────────────────────────────────────────────── классификация

    /**
     * @param text содержимое (текст уведомления, экрана, ответа инструмента)
     * @param packageName пакет-источник, если известен
     */
    fun classify(text: String?, packageName: String? = null): Sensitivity {
        if (isSensitivePackage(packageName)) return Sensitivity.SECRET
        val raw = text.orEmpty()
        if (raw.isBlank()) return Sensitivity.NORMAL
        if (validCardRanges(raw).isNotEmpty()) return Sensitivity.SECRET
        if (hasOtp(raw)) return Sensitivity.SECRET
        if (hasBalance(raw)) return Sensitivity.PRIVATE
        return Sensitivity.NORMAL
    }

    /**
     * Маскирует секретные фрагменты (коды и номера карт) на [MASK].
     * Остальной текст, включая телефоны/время/цены, не меняется.
     */
    fun mask(text: String?): String {
        val raw = text ?: return ""
        if (raw.isEmpty()) return raw
        val cards = validCardRanges(raw)
        val withoutCards = if (cards.isEmpty()) raw else replaceRanges(raw, cards)
        val otp = otpRanges(withoutCards)
        return if (otp.isEmpty()) withoutCards else replaceRanges(withoutCards, otp)
    }

    // ─────────────────────────────────────────────────────────────── правила

    /**
     * Триггеры рядом с кодом. Латиница — по границам слова (чтобы «barcode» не считался
     * «code»), кириллица — с узким допуском окончаний (чтобы «кодекс» не считался «код»).
     */
    private val otpTriggerRegex = Regex(
        "(?<![a-zа-яё0-9])(?:" +
            "код[а-я]{0,2}|парол[а-я]{0,3}|пин[а-я]{0,2}|отп[а-я]{0,1}|подтвержд[а-я]{0,4}|" +
            "проверочн[а-я]{0,4}|одноразов[а-я]{0,4}|никому\\s+не\\s+сообщ[а-я]{0,4}|" +
            "confirmation|verification|passcode|password|codes|code|otp|pin|cvv|cvc|confirm" +
            ")(?![a-zа-яё])",
        RegexOption.IGNORE_CASE,
    )

    /** 4-8 цифр, не являющиеся частью более длинного числа (телефон 11 цифр не подходит). */
    private val digitRunRegex = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    /** 13-19 цифр с одиночными пробелами/дефисами между ними. */
    private val cardRegex = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")

    private val balanceTriggerRegex = Regex(
        "(?<![a-zа-яё])(?:баланс[а-я]{0,2}|остат[а-я]{0,4}|доступн[а-я]{0,3}|balance|available)(?![a-zа-яё])",
        RegexOption.IGNORE_CASE,
    )

    private const val TRIGGER_WINDOW: Int = 40

    private fun hasOtp(text: String): Boolean = otpRanges(text).isNotEmpty()

    private fun otpRanges(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (m in digitRunRegex.findAll(text)) {
            val start = m.range.first
            val end = m.range.last
            val from = (start - TRIGGER_WINDOW).coerceAtLeast(0)
            val to = (end + TRIGGER_WINDOW).coerceAtMost(text.length - 1)
            val window = text.substring(from, to + 1)
            if (otpTriggerRegex.containsMatchIn(window)) out += start..end
        }
        return out
    }

    private fun hasBalance(text: String): Boolean {
        for (m in balanceTriggerRegex.findAll(text)) {
            val from = m.range.last + 1
            val to = (from + TRIGGER_WINDOW).coerceAtMost(text.length)
            if (from >= to) continue
            val tail = text.substring(from, to)
            val digits = tail.count { it.isDigit() }
            if (digits >= 2) return true
        }
        return false
    }

    private fun validCardRanges(text: String): List<IntRange> =
        cardRegex.findAll(text)
            .filter { luhn(it.value.filter { ch -> ch.isDigit() }) }
            .map { it.range }
            .toList()

    /** Проверка Луна; длины вне 13..19 не считаются картой. */
    internal fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        if (digits.any { !it.isDigit() }) return false
        var sum = 0
        var double = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }

    private fun replaceRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val sorted = ranges.sortedBy { it.first }
        val sb = StringBuilder(text.length)
        var cursor = 0
        for (r in sorted) {
            if (r.first < cursor) continue
            sb.append(text, cursor, r.first)
            sb.append(MASK)
            cursor = r.last + 1
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }
}
