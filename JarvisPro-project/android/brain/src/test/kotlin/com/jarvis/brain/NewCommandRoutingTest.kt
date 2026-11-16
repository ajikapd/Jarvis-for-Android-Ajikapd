package com.jarvis.brain

import com.jarvis.core.runtime.RouteDecision
import com.jarvis.core.runtime.semantic.FastSemanticClassifier
import com.jarvis.core.runtime.semantic.SemanticInput
import org.junit.Assert.assertTrue
import org.junit.Test

/** Быстрый классификатор стоит раньше Brain и не должен уводить эти фразы в план или уточнение. */
class NewCommandRoutingTest {
    @Test fun notHijacked() {
        val phrases = listOf(
            "добавь в покупки хлеб и сыр", "запиши в заметки найти подарок", "что в списке покупок",
            "узнай кто выиграл матч", "включи на ютубе обзор и новости", "найди в яндекс картах аптеку",
            "вызови такси", "открой сайт хабр точка ком", "запомни адрес дома ленина пять",
            "когда я говорю поехали включи блютуз и открой музыку", "создай сценарий вечер выключи фонарик и сделай потише",
            "переведи на английский доброе утро", "включи переводчик на немецкий", "ответь что понял",
            "не объявляй звонки", "через час выключи фонарик", "завтра в восемь утра включи музыку и сделай громче",
        )
        for (say in phrases) {
            val route = FastSemanticClassifier().interpret(SemanticInput(say, say))?.suggestedRoute
            assertTrue("$say -> $route", route == null || route == RouteDecision.FAST_SKILL)
        }
    }
}
