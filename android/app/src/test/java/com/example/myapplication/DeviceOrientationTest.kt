package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DeviceOrientationTest {
    private val level = doubleArrayOf(0.0,0.0,1.0)
    private val still = DoubleArray(3)

    @Test fun initializesFromGravityAndStaysFiniteAtVerticalTilt() {
        val tracker = DeviceOrientation()
        val q = tracker.update(0, doubleArrayOf(0.0,sqrt(.5),sqrt(.5)), still)!!.rotation
        assertEquals(45.0, q.eulerDegrees().first, 1e-6)
        val vertical = DeviceOrientation().update(0, doubleArrayOf(-1.0,0.0,0.0), still)!!.rotation
        assertEquals(90.0, vertical.eulerDegrees().second, 1e-6)
        assertEquals(1.0, vertical.w*vertical.w + vertical.x*vertical.x + vertical.y*vertical.y + vertical.z*vertical.z, 1e-9)
    }

    @Test fun integratesDegreesPerSecondAcrossTheFullReportingInterval() {
        val tracker = DeviceOrientation()
        tracker.update(0, level, still)
        val q = tracker.update(350, level, doubleArrayOf(0.0,0.0,90.0))!!.rotation
        assertEquals(31.5, q.eulerDegrees().third, .05)
        assertEquals(0.0, q.eulerDegrees().first, 1e-9)
        assertEquals(0.0, q.eulerDegrees().second, 1e-9)
    }

    @Test fun invalidValuesAndDuplicatePacketsCannotCorruptTheEstimate() {
        val tracker = DeviceOrientation()
        assertNull(tracker.update(0, DoubleArray(3), still))
        val initial = tracker.update(0, level, still)!!
        assertNull(tracker.update(100, doubleArrayOf(Double.NaN,0.0,1.0), still))
        assertNull(tracker.update(100, level, doubleArrayOf(0.0,0.0,Double.POSITIVE_INFINITY)))
        assertEquals(initial, tracker.update(0, level, doubleArrayOf(0.0,0.0,90.0)))
        assertNotNull(tracker.update(100, level, still))
    }

    @Test fun rebootAndLongGapsResetTheHeadingInsteadOfInventingMissingMotion() {
        val tracker = DeviceOrientation()
        tracker.update(1000, level, still)
        val moved = tracker.update(1100, level, doubleArrayOf(0.0,0.0,90.0))!!
        assertEquals(9.0, moved.rotation.eulerDegrees().third, .01)
        val reboot = tracker.update(0, level, still)!!
        assertTrue(reboot.generation > moved.generation)
        val gap = tracker.update(4000, level, doubleArrayOf(0.0,0.0,90.0))!!
        assertTrue(gap.generation > reboot.generation)
        assertEquals(0.0, gap.rotation.eulerDegrees().third, 1e-9)
    }

    @Test fun highLinearAccelerationDoesNotBecomeAnInstantTilt() {
        val tracker = DeviceOrientation()
        tracker.update(0, level, still)
        val q = tracker.update(350, doubleArrayOf(2.0,0.0,2.0), still)!!.rotation
        assertEquals(OrientationQuaternion(), q)
    }

    @Test fun gravityFeedbackConvergesToAChangedTiltWithoutChangingVectorLength() {
        val tracker = DeviceOrientation()
        tracker.update(0, level, still)
        var estimate: OrientationEstimate? = null
        for (i in 1..100) estimate = tracker.update(i*100L, doubleArrayOf(0.0,.5,sqrt(.75)), still)
        val q = estimate!!.rotation
        assertEquals(30.0, q.eulerDegrees().first, 1.0)
        val point = q.rotate(1.0,2.0,3.0)
        assertEquals(14.0, point.first*point.first + point.second*point.second + point.third*point.third, 1e-9)
    }

    @Test fun zeroReferenceAndRotationPreserveGeometry() {
        val q = OrientationQuaternion(cos(PI/4),0.0,0.0,sin(PI/4))
        val point = q.rotate(1.0,0.0,0.0)
        assertEquals(0.0, point.first, 1e-9)
        assertEquals(1.0, point.second, 1e-9)
        val zeroed = q.relativeTo(q).eulerDegrees()
        assertEquals(0.0, zeroed.first, 1e-9)
        assertEquals(0.0, zeroed.second, 1e-9)
        assertEquals(0.0, zeroed.third, 1e-9)
    }

    @Test fun stationaryYawStopsAccumulatingSmallGyroBiasAndTemperatureDrift() {
        val tracker = DeviceOrientation()
        tracker.update(0, level, still)
        var settledYaw: Double? = null
        for (i in 1..6000) {
            val estimate = tracker.update(i*100L, level, doubleArrayOf(0.0,0.0,.05 + i*.00001))!!
            if (i == 20) {
                assertTrue(estimate.stationary)
                settledYaw = estimate.rotation.eulerDegrees().third
            }
            if (i >= 20) assertEquals(settledYaw!!, estimate.rotation.eulerDegrees().third, 1e-9)
        }
    }

    @Test fun turningAfterBiasLearningPreservesTheTurnAndThenHoldsTheNewHeading() {
        val tracker = DeviceOrientation()
        val bias = doubleArrayOf(0.0,0.0,.05)
        tracker.update(0, level, bias)
        var estimate = tracker.update(100, level, bias)!!
        for (i in 2..600) estimate = tracker.update(i*100L, level, bias)!!
        val startYaw = estimate.rotation.eulerDegrees().third
        for (i in 601..800) {
            estimate = tracker.update(i*100L, level, doubleArrayOf(0.0,0.0,.55))!!
            assertFalse(estimate.stationary)
        }
        assertEquals(10.0, estimate.rotation.eulerDegrees().third - startYaw, .01)
        for (i in 801..830) estimate = tracker.update(i*100L, level, bias)!!
        val stoppedYaw = estimate.rotation.eulerDegrees().third
        assertTrue(estimate.stationary)
        for (i in 831..1000) estimate = tracker.update(i*100L, level, bias)!!
        assertEquals(stoppedYaw, estimate.rotation.eulerDegrees().third, 1e-9)
        assertTrue(stoppedYaw > 9.9)
    }

    @Test fun steadySlowTurnsAboveTheStillnessThresholdAreNeverLearnedAsBias() {
        val tracker = DeviceOrientation()
        tracker.update(0, level, still)
        var estimate: OrientationEstimate? = null
        for (i in 1..300) {
            estimate = tracker.update(i*100L, level, doubleArrayOf(0.0,0.0,.5))!!
            assertFalse(estimate.stationary)
        }
        assertEquals(15.0, estimate!!.rotation.eulerDegrees().third, .01)
    }

    @Test fun changingAccelerationPreventsStationaryBiasLearning() {
        val tracker = DeviceOrientation()
        tracker.update(0, level, still)
        for (i in 1..100) {
            val accel = doubleArrayOf(if (i % 2 == 0) .03 else -.03,0.0,1.0)
            assertFalse(tracker.update(i*100L, accel, doubleArrayOf(0.0,0.0,.1))!!.stationary)
        }
    }

    @Test fun duplicatePacketsDoNotSettleTheTrackerAndGapsClearLearnedBias() {
        val tracker = DeviceOrientation()
        val bias = doubleArrayOf(0.0,0.0,.05)
        tracker.update(0, level, bias)
        val first = tracker.update(350, level, bias)!!
        repeat(100) { assertEquals(first, tracker.update(350, level, bias)) }
        assertFalse(tracker.update(700, level, bias)!!.stationary)
        var estimate: OrientationEstimate? = null
        for (i in 3..200) estimate = tracker.update(i*350L, level, bias)!!
        assertTrue(estimate!!.stationary)
        val reset = tracker.update(74000, level, bias)!!
        assertFalse(reset.stationary)
        assertTrue(reset.generation > estimate.generation)
        val turn = tracker.update(74350, level, doubleArrayOf(0.0,0.0,.5))!!
        assertEquals(.175, turn.rotation.eulerDegrees().third, .001)
    }
}
