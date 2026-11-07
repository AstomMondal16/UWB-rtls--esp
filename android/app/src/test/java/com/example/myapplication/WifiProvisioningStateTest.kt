package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test

class WifiProvisioningStateTest {
    @Test fun credentialsAppliedStillWaitsForDeviceConfirmation() {
        val state = WifiProvisioningState().begin()
        val applied = state.progress(state.attempt, "Testing Wi-Fi")
        assertTrue(applied.sending)
        assertFalse(applied.confirmed)
        assertEquals("Testing Wi-Fi", applied.message)
    }

    @Test fun successStopsSpinnerAndSurvivesNormalBluetoothCleanup() {
        val state = WifiProvisioningState().begin()
        val success = state.complete(state.attempt, true, "Wi-Fi connected")
        assertFalse(success.sending)
        assertTrue(success.confirmed)
        assertEquals(success, success.disconnected())
        assertEquals(success, success.progress(state.attempt, "Testing Wi-Fi"))
        assertEquals(success, success.complete(state.attempt, false, "Late failure"))
        assertEquals(success, success.timedOut(state.attempt))
    }

    @Test fun disconnectStopsSpinnerWithoutClaimingSuccess() {
        val state = WifiProvisioningState().begin().disconnected()
        assertFalse(state.sending)
        assertFalse(state.confirmed)
        assertTrue(state.message.contains("may already be online"))
        assertEquals(state, state.progress(state.attempt, "Late progress"))
    }

    @Test fun timeoutStopsSpinnerAndRejectsLateSuccess() {
        val state = WifiProvisioningState().begin()
        val timedOut = state.timedOut(state.attempt)
        assertFalse(timedOut.sending)
        assertFalse(timedOut.confirmed)
        assertTrue(timedOut.message.contains("timed out"))
        assertEquals(timedOut, timedOut.complete(state.attempt, true, "Late success"))
        assertEquals(120_000L, WifiProvisioningState.TIMEOUT_MS)
    }

    @Test fun failureStopsSpinnerAndKeepsItsExplanation() {
        val state = WifiProvisioningState().begin()
        val failure = state.complete(state.attempt, false, "Wrong password")
        assertFalse(failure.sending)
        assertFalse(failure.confirmed)
        assertEquals(failure, failure.disconnected())
        assertEquals("Wrong password", failure.message)
    }

    @Test fun selectingAnotherDeviceInvalidatesPendingCallbacks() {
        val first = WifiProvisioningState().begin()
        val reset = first.reset()
        assertFalse(reset.sending)
        assertEquals("", reset.message)
        val next = reset.begin()
        assertEquals(next, next.complete(first.attempt, true, "Old success"))
        assertEquals(next, next.progress(first.attempt, "Old progress"))
        assertEquals(next, next.timedOut(first.attempt))
        assertTrue(next.complete(next.attempt, true, "New success").confirmed)
    }
}
