package com.jarvis.core.privacy

import com.jarvis.core.companion.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Синтетические строки: коды/карты — тестовые, телефоны — заведомо нетестовые
 * (используются только как «не должно срабатывать»).
 */
class SensitivityClassifierTest {

    private fun secret(text: String?, pkg: String? = null) {
        assertEquals("SECRET expected for: $text", Sensitivity.SECRET, SensitivityClassifier.classify(text, pkg))
    }

    private fun normal(text: String?, pkg: String? = null) {
        assertEquals("NORMAL expected for: $text", Sensitivity.NORMAL, SensitivityClassifier.classify(text, pkg))
    }

    private fun private(text: String?) {
        assertEquals("PRIVATE expected for: $text", Sensitivity.PRIVATE, SensitivityClassifier.classify(text))
    }

    private fun masked(text: String?): String = SensitivityClassifier.mask(text)

    // ──────────────────────────────────────────── секреты: коды

    @Test
    fun russianOtpIsSecret() {
        secret("Ваш код 482913, никому не сообщайте")
        assertFalse(masked("Ваш код 482913, никому не сообщайте").contains("482913"))
    }

    @Test
    fun russianOtpKeepsWording() {
        assertEquals(
            "Ваш код [скрыто], никому не сообщайте",
            masked("Ваш код 482913, никому не сообщайте"),
        )
    }

    @Test
    fun englishOtpIsSecret() {
        secret("Code: 7731 do not share")
        assertEquals("Code: [скрыто] do not share", masked("Code: 7731 do not share"))
    }

    @Test
    fun otpWordIsSecret() {
        secret("OTP 5591")
        secret("ОТП 5591")
        assertEquals("OTP [скрыто]", masked("OTP 5591"))
    }

    @Test
    fun pinIsSecret() {
        secret("PIN 8842")
    }

    @Test
    fun passwordWithDigitsIsSecret() {
        secret("Пароль: 123456")
        secret("Password 9087")
    }

    @Test
    fun confirmationCodeIsSecret() {
        secret("Подтверждение: код 9034")
        secret("Код подтверждения 5512")
    }

    @Test
    fun verificationCodeIsSecret() {
        secret("verification code 55217")
        secret("Your verification number is 734198")
    }

    @Test
    fun oneTimeCodeIsSecret() {
        secret("Одноразовый код 445566")
        secret("Проверочный код 7712")
    }

    @Test
    fun eightDigitCodeIsSecret() {
        secret("код 12345678")
    }

    // ────────────────────────────────── секреты: карты (Луна)

    @Test
    fun visaTestCardWithSpacesIsSecret() {
        secret("карта 4111 1111 1111 1111")
        assertEquals("карта [скрыто]", masked("карта 4111 1111 1111 1111"))
    }

    @Test
    fun visaTestCardWithoutSpacesIsSecret() {
        secret("4111111111111111")
        assertEquals("[скрыто]", masked("4111111111111111"))
    }

    @Test
    fun mastercardTestCardIsSecret() {
        secret("5555 5555 5555 4444")
    }

    @Test
    fun cardWithHyphensIsSecret() {
        secret("4111-1111-1111-1111")
        assertEquals("[скрыто]", masked("4111-1111-1111-1111"))
    }

    @Test
    fun nineteenDigitCardIsSecret() {
        // 4000 0000 0000 0000 006 — валидный по Луну 19-значный тестовый номер
        secret("4000000000000000006")
        assertTrue(masked("4000000000000000006").contains("[скрыто]"))
    }

    @Test
    fun cardAndCodeAreBothMasked() {
        assertEquals("код [скрыто], карта [скрыто]", masked("код 1234, карта 4111 1111 1111 1111"))
    }

    @Test
    fun invalidLuhnIsNotCard() {
        // не проходит Луна → это не карта (и нет триггера рядом)
        normal("номер 1234 5678 9012 3456")
    }

    // ─────────────────────────────── секреты: чувствительные пакеты

    @Test
    fun sensitiveBankPackageMakesAnyTextSecret() {
        secret("Зачисление", "ru.sberbankmobile")
        secret("Выполнено", "com.idamob.tinkoff.android")
        secret("Покупка", "ru.vtb24.mobilebanking.android")
        secret("Операция", "ru.alfabank.mobile.android")
    }

    @Test
    fun sensitivePackageBeatsEmptyText() {
        secret("", "com.x8bit.bitwarden")
        secret(null, "com.azure.authenticator")
    }

    @Test
    fun packageHeuristics() {
        assertTrue(SensitivityClassifier.isSensitivePackage("com.some.bank.app"))
        assertTrue(SensitivityClassifier.isSensitivePackage("com.example.payapp"))
        assertTrue(SensitivityClassifier.isSensitivePackage("org.example.wallet"))
        assertTrue(SensitivityClassifier.isSensitivePackage("com.example.authenticator"))
        assertTrue(SensitivityClassifier.isSensitivePackage("com.lastpass.lpandroid"))
        assertTrue(SensitivityClassifier.isSensitivePackage("keepass2android.keepass2android"))
    }

    @Test
    fun plainPackagesAreNotSensitive() {
        assertFalse(SensitivityClassifier.isSensitivePackage("com.jarvis.assistant"))
        assertFalse(SensitivityClassifier.isSensitivePackage("org.telegram.messenger"))
        assertFalse(SensitivityClassifier.isSensitivePackage("com.android.settings"))
        assertFalse(SensitivityClassifier.isSensitivePackage(null))
        assertFalse(SensitivityClassifier.isSensitivePackage(""))
    }

    @Test
    fun plainTextInPlainPackageStaysNormal() {
        normal("Привет, как дела?", "org.telegram.messenger")
    }

    // ────────────────────────────── не должно срабатывать: телефоны/время

    @Test
    fun phoneNumberIsNotMasked() {
        normal("позвони 89161234567")
        assertEquals("позвони 89161234567", masked("позвони 89161234567"))
    }

    @Test
    fun formattedPhoneNumberIsNotMasked() {
        normal("+7 916 123-45-67")
        assertEquals("+7 916 123-45-67", masked("+7 916 123-45-67"))
        normal("8 916 123 45 67")
    }

    @Test
    fun timeIsNotMasked() {
        normal("встреча в 15:30")
        assertEquals("встреча в 15:30", masked("встреча в 15:30"))
    }

    @Test
    fun dateIsNotMasked() {
        normal("13 ноября 2026")
        normal("встреча в 15:30 13 ноября")
        assertEquals("встреча в 15:30 13 ноября", masked("встреча в 15:30 13 ноября"))
    }

    @Test
    fun pricesAreNotMasked() {
        normal("500 рублей")
        normal("1 299 ₽")
        assertEquals("1 299 ₽", masked("1 299 ₽"))
        normal("купи за 2500 руб")
    }

    @Test
    fun yearAndVersionAreNotMasked() {
        normal("2026")
        normal("версия 2.2.2")
        normal("Android 16")
    }

    @Test
    fun plainSpeechNumbersAreNotMasked() {
        normal("поставь будильник на 7")
        normal("у меня 3 яблока и 4 груши")
        normal("включи музыку")
    }

    @Test
    fun shortNumberNearWordCodeIsNotSecret() {
        normal("код города 495")
        normal("пин-код 12")
    }

    // ─────────────────────────────── не должно срабатывать: похожие слова

    @Test
    fun barcodeIsNotCode() {
        normal("barcode 1234567")
        assertEquals("barcode 1234567", masked("barcode 1234567"))
    }

    @Test
    fun codexIsNotCode() {
        normal("кодекс 1234")
    }

    @Test
    fun metroCardIsNotBankCard() {
        normal("карта метро 1234")
    }

    // ─────────────────────────────────────────────── PRIVATE: баланс

    @Test
    fun balanceIsPrivate() {
        private("баланс 12 340 рублей")
        private("Баланс: 100 ₽")
        private("остаток на счёте 350 руб")
        private("доступно 1000")
        private("Balance: 1 299 ₽")
    }

    @Test
    fun balanceIsNotMaskedButClassified() {
        assertEquals("баланс 12 340 рублей", masked("баланс 12 340 рублей"))
        private("баланс 12 340 рублей")
    }

    @Test
    fun balanceWordWithoutSumIsNormal() {
        normal("проверь баланс")
    }

    // ───────────────────────────────────────────── маска: общие свойства

    @Test
    fun maskKeepsTextWhenNothingSecret() {
        val text = "Напомни купить молоко и позвонить маме в 19:00"
        assertEquals(text, masked(text))
    }

    @Test
    fun maskHandlesNullAndBlank() {
        assertEquals("", masked(null))
        assertEquals("", masked(""))
        assertEquals("   ", masked("   "))
    }

    @Test
    fun maskHidesAllCodesInLongText() {
        val text = "Первое: код 1111. Второе: code 222333. Третье: 4111 1111 1111 1111."
        val out = masked(text)
        assertFalse(out.contains("1111. Второе"))
        assertFalse(out.contains("222333"))
        assertFalse(out.contains("4111 1111 1111 1111"))
        assertTrue(out.contains("[скрыто]"))
    }

    @Test
    fun luhnHelper() {
        assertTrue(SensitivityClassifier.luhn("4111111111111111"))
        assertTrue(SensitivityClassifier.luhn("5555555555554444"))
        assertFalse(SensitivityClassifier.luhn("4111111111111112"))
        assertFalse(SensitivityClassifier.luhn("12345"))
    }
}
