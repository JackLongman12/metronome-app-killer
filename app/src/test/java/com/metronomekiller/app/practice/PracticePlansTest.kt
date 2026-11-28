package com.metronomekiller.app.practice

import org.junit.Assert.assertEquals
import org.junit.Test

class PracticePlansTest {
    @Test fun trainerClimbsThenHolds() {
        val p = TrainerPlan(from = 90, to = 100, step = 4, every = 2)
        assertEquals(listOf(90, 90, 94, 94, 98, 98, 100, 100, 100), (0..8).map(p::bpmFor))
    }

    @Test fun trainerRestarts() {
        val p = TrainerPlan(from = 100, to = 108, step = 4, every = 1, top = TrainerPlan.Top.RESTART)
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

    @Test fun countInComesBeforeTheGaps() {
        val c = GapCounter(GapPlan(on = 1, off = 1), lead = 2)
        val states = (0 until 5).map { c.nextBar(); c.countingIn to c.silent }
        assertEquals(listOf(true to false, true to false, false to false, false to true, false to false), states)
    }

    @Test fun trainerTimeToTop() {
        // 4 tempos (90, 94, 98, 100), 2 bars each: the top starts after 6 bars.
        val p = TrainerPlan(from = 90, to = 100, step = 4, every = 2)
        assertEquals(6.0, p.secondsToTop(0) { 1.0 }, 1e-9)
        assertEquals(2.0, p.secondsToTop(4) { 1.0 }, 1e-9)
        assertEquals(0.0, p.secondsToTop(7) { 1.0 }, 1e-9)
    }

    @Test fun trainerStepsForSkipping() {
        val p = TrainerPlan(from = 100, to = 108, step = 4, every = 3)
        assertEquals(2, p.stepOf(7))
        assertEquals(2, p.lastStep)
        assertEquals(4, p.copy(top = TrainerPlan.Top.BACK_DOWN).lastStep)
    }

    @Test fun pyramidGoesUpThenBackDownAndHolds() {
        val p = TrainerPlan(from = 100, to = 108, step = 4, every = 1, top = TrainerPlan.Top.BACK_DOWN)
        assertEquals(listOf(100, 104, 108, 104, 100, 100), (0..5).map(p::bpmFor))
        assertEquals(5, p.totalBars)
    }

    @Test fun ladderClimbsAndComesBack() {
        val l = LadderPlan(listOf(1, 2, 3, 4), every = 1, backDown = true)
        assertEquals(listOf(1, 2, 3, 4, 3, 2, 1, 2), (0..7).map(l::subFor))
        val up = LadderPlan(listOf(1, 2, 4), every = 2, backDown = false)
        assertEquals(listOf(1, 1, 2, 2, 4, 4, 1), (0..6).map(up::subFor))
    }

    @Test fun logSkipsTinySessionsAndKeepsTheRest() {
        val f = java.io.File.createTempFile("log", ".json").apply { delete() }
        val log = PracticeLog(f)
        log.add(LogEntry(1000, 5, "Gap click", "too short"))
        log.add(LogEntry(2000, 300, "Speed trainer", "90 → 130 BPM"))
        val all = PracticeLog(f).load()
        assertEquals(listOf("Speed trainer"), all.map { it.tool })
        assertEquals(300, PracticeLog.secondsSince(all, 1500))
        f.delete()
    }
}
