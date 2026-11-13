package com.jarvis.wakeword

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AdaptiveVoicePrintTest {
    /** Вектор под углом [deg] к оси владельца в плоскости [axis]. */
    private fun at(deg: Double, axis: Int = 1): FloatArray {
        val r = Math.toRadians(deg)
        return FloatArray(4).also { it[0] = cos(r).toFloat(); it[axis] = sin(r).toFloat() }
    }

    private fun print() = AdaptiveVoicePrint(owner = at(0.0), threshold = 0.36f)

    @Test fun ownerAccepted() {
        val p = print()
        assertTrue(p.accepts(p.score(at(20.0))))
    }

    @Test fun knownImpostorRejectedAfterLearning() {
        val p = print()
        // Чужой голос рядом с порогом: сначала проходит.
        val stranger = at(66.0, axis = 2)
        assertTrue(p.accepts(p.score(stranger)))
        repeat(3) { i -> assertTrue(p.learnImpostor(at(64.0 + i, axis = 2))) }
        assertFalse(p.accepts(p.score(stranger)))
        // Владелец не пострадал.
        assertTrue(p.accepts(p.score(at(25.0))))
    }

    @Test fun clearOwnerNeverLearnedAsImpostor() {
        val p = print()
        assertFalse(p.learnImpostor(at(10.0)))
    }

    @Test fun ownerLearningOnlyWithMargin() {
        val p = print()
        assertFalse(p.learnOwner(at(66.0)))
        assertTrue(p.learnOwner(at(15.0)))
    }

    @Test fun learnedOwnerHelpsUnusualVoice() {
        val p = print()
        // Шёпот: далеко от записанного эталона, но в стороне, где владелец уже бывал.
        val whisper = at(70.0, axis = 3)
        assertFalse(p.accepts(p.score(whisper)))
        val q = AdaptiveVoicePrint(at(0.0), 0.36f, learnedOwner = List(3) { at(72.0 + it, axis = 3) })
        assertTrue(q.accepts(q.score(whisper)))
    }
}
