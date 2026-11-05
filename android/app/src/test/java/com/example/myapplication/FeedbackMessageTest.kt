package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackMessageTest {
    @Test fun connectionAndScanSummariesFitWithoutRawIdentifiers() {
        assertEquals("Connection failed", feedbackSummary("Connection failed for A4:CB:8F:E0:D3:C4: failed to connect to /192.168.1.133"))
        assertEquals("No devices found", feedbackSummary("Scan complete \u00b7 0 found after checking 3 broadcast target(s)"))
        assertEquals("Network scan failed", feedbackSummary("Scan failed: permission denied"))
    }

    @Test fun knownErrorsStaySpecificAndOtherMessagesRemainIntact() {
        assertEquals("Could not read CIR", feedbackSummary("CIR request failed: timeout"))
        assertEquals("Device control failed", feedbackSummary("Control failed: HTTP 500"))
        assertEquals("CSV export failed", feedbackSummary("CSV export failed: storage unavailable"))
        assertEquals("Waiting for devices", feedbackSummary("Waiting for devices"))
        assertEquals("", feedbackSummary(""))
    }
    @Test fun discoveryAndConnectionFailureProduceOnePrimaryStatusWithBothDetails() {
        val scan = "Scan complete \u00b7 1 found"
        val error = "Connection failed for A4:CB:8F:E0:D3:C4: timeout"
        val feedback = dashboardFeedback(scan, error, "", true)
        assertEquals(error, feedback.message)
        assertEquals("Connection failed", feedbackSummary(feedback.message))
        assertEquals(listOf(error, scan), feedback.details.split("\n\n"))
        assertFalse(feedback.busy)
    }

    @Test fun normalDiscoveryRemainsSingleAndRepeatedDetailsAreNotDuplicated() {
        val scan = "Scan complete \u00b7 1 found"
        assertEquals(DashboardFeedback(scan, scan, false), dashboardFeedback(scan, "", "", false))
        val error = "Connection failed for device: timeout"
        assertEquals(error, dashboardFeedback(error, error, "", false).details)
        assertEquals(DashboardFeedback("", "", false), dashboardFeedback("", "", "", false))
    }

    @Test fun calibrationAndExportProgressUseTheSameStatusRow() {
        val calibration = dashboardFeedback("Scan complete", "", "25/100 samples", false)
        assertEquals("Calibration \u00b7 25/100 samples", calibration.message)
        assertTrue(calibration.busy)
        assertTrue(calibration.details.contains("Scan complete"))
        assertTrue(dashboardFeedback("Exporting 404 CSV records...", "", "", false).busy)
    }

    @Test fun operationFailureIsNotHiddenByCalibrationProgress() {
        val failure = "CSV export failed: storage unavailable"
        val feedback = dashboardFeedback(failure, "", "25/100 samples", false)
        assertEquals(failure, feedback.message)
        assertFalse(feedback.busy)
        assertTrue(feedback.details.contains("Calibration"))
    }

}
