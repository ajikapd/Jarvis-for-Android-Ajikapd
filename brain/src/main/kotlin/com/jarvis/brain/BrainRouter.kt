package com.jarvis.brain

import com.jarvis.core.common.model.Intent
import com.jarvis.core.common.model.UserRequest

/**
 * Результат Brain Router-а (JARVIS_BRAIN_ARCHITECTURE §2.1):
 * тип решения + конфиденция + (опционально) извлечённый интент.
 *
 * Три сценария:
 * - SKILL_CALL: Brain уверен в конкретном скилле → executing.
 * - CLARIFY: намерение нечёткое или не хватает параметров → быстрый вопрос.
 * - CHAT: разговорный ответ → передаём в Dialogue LLM.
 */
sealed interface BrainResult {

    data class SkillCall(
        val intent: Intent,
        val confidence: Float
    ) : BrainResult

    data class Clarify(
        val question: String,
        val confidence: Float
    ) : BrainResult

    data class Chat(
        val confidence: Float
    ) : BrainResult
}

/**
 * Интерфейс Brain Router (JARVIS_BRAIN_ARCHITECTURE §2).
 *
 * Единственная задача: посмотреть на фразу + контекст и решить:
 * - skill_call с извлечёнными параметрами?
 * - clarify с коротким уточнением?
 * - chat → передать в Dialogue LLM?
 *
 * Реализации: [RuleBasedBrain] (детерминированный, быстрый, текущий)
 * и [LlmBrain] (LLM через llama.cpp, запланирован).
 */
interface BrainRouter {

    /**
     * Определяет намерение пользователя по тексту.
     * @param request распознанная фраза
     * @param history последние реплики диалога (summary + raw)
     * @param topSkills релевантные скиллы от Retriever (max 5-8)
     */
    fun route(
        request: UserRequest,
        history: List<String>,
        topSkills: List<SkillInfo>
    ): BrainResult

    /**
     * ReAct: после успешного tool — нужен ли ещё один шаг по исходному запросу.
     * null = стоп (RuleBased всегда null).
     */
    fun continueAfterTool(
        originalRequest: UserRequest,
        history: List<String>,
        topSkills: List<SkillInfo>,
        lastToolId: String,
        lastToolMessage: String,
    ): BrainResult? = null

    /** Краткое описание скилла для промпта Brain. */
    data class SkillInfo(
        val id: String,
        val name: String,
        val description: String,
        val examples: List<String>,
        val parameters: List<SkillParam> = emptyList()
    )

    /** Параметр скилла для LLM-деклараций (FunctionGemma schema). */
    data class SkillParam(
        val name: String,
        val description: String,
        val type: String = "STRING",
        val required: Boolean = false,
        val allowedValues: List<String> = emptyList()
    )

    companion object {
        const val MAX_SKILLS = 8
    }
}