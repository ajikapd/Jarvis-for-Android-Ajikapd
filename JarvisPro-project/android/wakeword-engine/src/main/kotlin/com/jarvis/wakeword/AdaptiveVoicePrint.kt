package com.jarvis.wakeword

import kotlin.math.sqrt

/**
 * Эталон владельца, который учится со временем.
 *
 *  - [owner]: средний отпечаток записанных образцов голоса, не меняется сам по себе;
 *  - [learnedOwner]: уверенно подтверждённые «Джарвис» владельца (за ними шла принятая команда);
 *  - [impostors]: чужие голоса и шумы, на которые детектор срабатывал (команду после них
 *    отклонила проверка голоса, или оценка была заведомо чужой).
 *
 * Решение: голос похож на владельца не меньше порога и при этом не ближе к запомненным
 * чужим, чем к владельцу. Так знакомые домашние голоса и шумы отсекаются всё лучше,
 * а владелец, говорящий по-разному (утром, тихо, издалека), узнаётся всё увереннее.
 *
 * Чтобы ошибка не закреплялась: в чужие не попадает то, что явно похоже на владельца,
 * а в свои — то, что похоже на чужих.
 */
class AdaptiveVoicePrint(
    private val owner: FloatArray,
    val threshold: Float,
    learnedOwner: List<FloatArray> = emptyList(),
    impostors: List<FloatArray> = emptyList(),
) {
    data class Score(val owner: Float, val impostor: Float?) {
        /** Насколько голос ближе к владельцу, чем к знакомым чужим. */
        val margin: Float get() = impostor?.let { owner - it } ?: Float.POSITIVE_INFINITY
    }

    private val ownerMemory = VoiceMemory(OWNER_CAPACITY, learnedOwner.map(::unit))
    private val impostorMemory = VoiceMemory(IMPOSTOR_CAPACITY, impostors.map(::unit))
    private val ownerUnit = unit(owner)

    val learnedOwnerCount: Int get() = ownerMemory.size
    val impostorCount: Int get() = impostorMemory.size
    val learnedOwner: List<FloatArray> get() = ownerMemory.samples
    val impostors: List<FloatArray> get() = impostorMemory.samples

    fun score(embedding: FloatArray): Score {
        val e = unit(embedding)
        val base = dot(e, ownerUnit)
        // Выученные свои помогают узнавать владельца в непривычных условиях, но не тянут
        // оценку ниже записанного эталона.
        val learned = topMean(e, ownerMemory.samples)
        val own = if (learned == null) base else maxOf(base, (base + learned) / 2)
        return Score(own, topMean(e, impostorMemory.samples))
    }

    fun accepts(score: Score): Boolean = score.owner >= threshold && score.margin > IMPOSTOR_MARGIN

    /** «Джарвис» подтверждён принятой командой. Запоминаем, только если голос уверенно свой. */
    fun learnOwner(embedding: FloatArray): Boolean {
        val s = score(embedding)
        if (s.owner < threshold + OWNER_LEARN_MARGIN || s.margin < OWNER_LEARN_MARGIN) return false
        ownerMemory.add(unit(embedding))
        return true
    }

    /** Срабатывание оказалось чужим. Явно похожее на владельца не запоминаем: это скорее его ошибка. */
    fun learnImpostor(embedding: FloatArray): Boolean {
        if (score(embedding).owner >= threshold + IMPOSTOR_GUARD) return false
        impostorMemory.add(unit(embedding))
        return true
    }

    private fun topMean(e: FloatArray, items: List<FloatArray>): Float? {
        if (items.size < MIN_MEMORY) return null
        val sims = items.map { dot(e, it) }.sortedDescending()
        return sims.take(TOP_K).average().toFloat()
    }

    companion object {
        const val OWNER_CAPACITY = 40
        const val IMPOSTOR_CAPACITY = 60
        /** Память начинает влиять, когда в ней хотя бы столько образцов. */
        private const val MIN_MEMORY = 3
        private const val TOP_K = 3
        /** Голос должен быть ближе к владельцу, чем к знакомым чужим, хотя бы на столько. */
        private const val IMPOSTOR_MARGIN = 0.0f
        /** Свои учим только с запасом над порогом. */
        private const val OWNER_LEARN_MARGIN = 0.08f
        /** Выше порога на столько — уже похоже на владельца, в чужие не берём. */
        private const val IMPOSTOR_GUARD = 0.12f

        fun unit(v: FloatArray): FloatArray {
            var sum = 0.0
            for (x in v) sum += x * x
            val n = sqrt(sum).toFloat().takeIf { it > 0f } ?: return v
            return FloatArray(v.size) { v[it] / n }
        }

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
            return s
        }
    }
}
