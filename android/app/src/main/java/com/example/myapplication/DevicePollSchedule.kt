package com.example.myapplication

/** Keeps each device's polling phase independent of worker start delays. */
internal class DevicePollSchedule {
    private data class Deadline(val dueMs: Long, val periodMs: Long)
    private val deadlines = mutableMapOf<String, Deadline>()

    @Synchronized fun claim(key: String, nowMs: Long, periodMs: Long): Boolean {
        require(periodMs > 0)
        val previous = deadlines[key]?.takeIf { it.periodMs == periodMs }
        val due = previous?.dueMs ?: nowMs
        if (nowMs < due) return false
        val next = due + periodMs
        // After an overrun, start a fresh period instead of replaying missed polls.
        deadlines[key] = Deadline(if (next <= nowMs) nowMs + periodMs else next, periodMs)
        return true
    }

    @Synchronized fun retainDevices(keys: Set<String>) { deadlines.keys.retainAll(keys) }
    @Synchronized fun clear() { deadlines.clear() }
}
