package com.metronomekiller.app.practice

import org.junit.Assert.assertEquals
import org.junit.Test

class PracticePlansTest {
    @Test fun trainerClimbsThenHolds() {
        val p = TrainerPlan(from = 90, to = 100, step = 4, every = 2)
        assertEquals(listOf(90, 90, 94, 94, 98, 98, 100, 100, 100), (0..8).map(p::bpmFor))
    }

    @Test fun trainerRestarts() {
        val p = TrainerPlan(from = 100, to = 108, step = 4, every = 1, restart = true)
        assertEquals(listOf(100, 104, 108, 100, 104), (0..4).map(p::bpmFor))
    }

    @Test fun trainerCanSlowDown() {
        val p = TrainerPlan(from = 120, to = 110, step = 5, every = 1)
        assertEquals(listOf(120, 115, 110, 110), (0..3).map(p::bpmFor))
    }

    /** Must match the C++ engine's gap logic: on, on, off, off, ... */
    @Test fun gapPattern() {
        val c = GapCounter(GapPlan(on = 2, off = 1))
        val silent = (0 until 6).map { c.nextBar(); c.silent }
        assertEquals(listOf(false, false, true, false, false, true), silent)
    }

    @Test fun gapsGrowEachRound() {
        val c = GapCounter(GapPlan(on = 1, off = 1, grow = true))
        val silent = (0 until 9).map { c.nextBar(); c.silent }
        // round 0: on, off · round 1: on, off, off · round 2: on, off, off, off
        assertEquals(listOf(false, true, false, true, true, false, true, true, true), silent)
    }
}
