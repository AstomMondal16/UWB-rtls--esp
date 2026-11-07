package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test

class DevicePollScheduleTest {
    @Test fun delayedWorkersStillDispatchTenPollsPerSecond() {
        val schedule = DevicePollSchedule()
        var lastWorkerStart: Long? = null
        var previousCount = 0
        var fixedCount = 0
        var workerFinishedAt = 0L
        for (now in 0L until 10_000L step 25) {
            // Previous gate timed from a worker that started just 1ms after dispatch.
            if (lastWorkerStart == null || now - lastWorkerStart >= 100) {
                previousCount++
                lastWorkerStart = now + 1
            }
            if (now >= workerFinishedAt && schedule.claim("tag", now, 100)) {
                fixedCount++
                workerFinishedAt = now + 1
            }
        }
        assertEquals(80, previousCount)
        assertEquals(100, fixedCount)
        println("POLL_TIMING_SIMULATION expected=100 before=$previousCount after=$fixedCount duration_ms=10000")
    }

    @Test fun schedulerJitterDoesNotAccumulateIntoSlowerPolling() {
        val schedule = DevicePollSchedule()
        val count = (0L until 10_000L step 26).count { schedule.claim("tag", it, 100) }
        assertEquals(100, count)
    }

    @Test fun overrunsDoNotProduceCatchUpBursts() {
        val schedule = DevicePollSchedule()
        assertTrue(schedule.claim("tag", 0, 100))
        assertTrue(schedule.claim("tag", 350, 100))
        assertFalse(schedule.claim("tag", 351, 100))
        assertFalse(schedule.claim("tag", 449, 100))
        assertTrue(schedule.claim("tag", 450, 100))
    }

    @Test fun periodsAndRateChangesAreIndependentForEachDevice() {
        val schedule = DevicePollSchedule()
        assertTrue(schedule.claim("a", 0, 100))
        assertTrue(schedule.claim("b", 0, 200))
        assertFalse(schedule.claim("a", 50, 100))
        assertFalse(schedule.claim("b", 100, 200))
        assertTrue(schedule.claim("a", 100, 100))
        assertTrue(schedule.claim("a", 110, 50))
        assertFalse(schedule.claim("a", 159, 50))
        assertTrue(schedule.claim("a", 160, 50))
        assertTrue(schedule.claim("b", 200, 200))
    }

    @Test fun refreshAndDeviceRemovalDiscardOldDeadlines() {
        val schedule = DevicePollSchedule()
        schedule.claim("a", 0, 100); schedule.claim("b", 0, 100)
        schedule.retainDevices(setOf("a"))
        assertFalse(schedule.claim("a", 10, 100))
        assertTrue(schedule.claim("b", 10, 100))
        schedule.clear()
        assertTrue(schedule.claim("a", 10, 100))
    }

    @Test fun invalidPeriodsAreRejected() {
        val schedule = DevicePollSchedule()
        for (period in listOf(0L, -1L))
            assertTrue(runCatching { schedule.claim("a", 0, period) }.exceptionOrNull() is IllegalArgumentException)
    }
}
