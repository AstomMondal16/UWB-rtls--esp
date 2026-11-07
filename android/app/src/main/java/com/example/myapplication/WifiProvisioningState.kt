package com.example.myapplication

/** A late callback cannot revive a completed, cancelled or replaced attempt. */
internal data class WifiProvisioningState(
    val attempt: Long = 0,
    val sending: Boolean = false,
    val confirmed: Boolean = false,
    val message: String = ""
) {
    fun reset() = WifiProvisioningState(attempt = attempt + 1)

    fun begin() = WifiProvisioningState(attempt + 1, sending = true,
        message = "Sending credentials and waiting for Wi-Fi…")

    fun progress(id: Long, text: String): WifiProvisioningState =
        if (id == attempt && sending) copy(message = text) else this

    fun complete(id: Long, success: Boolean, text: String): WifiProvisioningState =
        if (id == attempt && sending) copy(sending = false, confirmed = success, message = text) else this

    fun disconnected(): WifiProvisioningState = complete(attempt, false,
        "Bluetooth disconnected before setup was confirmed. The device may already be online; use Scan network to check.")

    fun timedOut(id: Long): WifiProvisioningState = complete(id, false,
        "Setup confirmation timed out. The device may already be online; use Scan network to check.")

    companion object { const val TIMEOUT_MS = 120_000L }
}
