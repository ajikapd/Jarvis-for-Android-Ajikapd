package com.jarvis.tools

import android.content.Context
import android.content.ContextWrapper
import com.jarvis.core.common.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Прямые проверки уровня риска без выполнения инструментов.
 *
 * Конструкторы [SmsTool] и [TelegramSendTool] требуют `Context`, но значение
 * нужно только внутри `execute()`. Чтобы не тащить Robolectric/Mockito и не
 * менять продакшн-код, тест создаёт «пустой» `ContextWrapper` через
 * `Unsafe.allocateInstance` (конструктор не вызывается, поля не трогаются).
 * `sun.misc.Unsafe` недоступен на compile classpath Android-модуля, поэтому
 * обращаемся к нему рефлексией — runtime unit-тестов это обычная JVM 17.
 */
class ToolRiskLevelTest {

    private fun emptyContext(): Context {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, ContextWrapper::class.java) as Context
    }

    @Test
    fun smsIsConfirm() {
        val tool = SmsTool(emptyContext())
        assertEquals("sms", tool.definition.id)
        assertEquals(RiskLevel.CONFIRM, tool.definition.riskLevel)
    }

    @Test
    fun telegramSendIsConfirm() {
        val tool = TelegramSendTool(emptyContext())
        assertEquals("telegram.send", tool.definition.id)
        assertEquals(RiskLevel.CONFIRM, tool.definition.riskLevel)
    }

    @Test
    fun callIsStillConfirmForComparison() {
        val tool = CallTool(emptyContext())
        assertEquals("phone.call", tool.definition.id)
        assertEquals(RiskLevel.CONFIRM, tool.definition.riskLevel)
    }
}
