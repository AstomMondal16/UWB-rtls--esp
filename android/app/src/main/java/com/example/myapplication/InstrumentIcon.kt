package com.example.myapplication

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Google Material Symbols Rounded; source/license are in android/THIRD_PARTY_NOTICES.md. */
@Composable
internal fun InstrumentIcon(kind: String, tint: Color, modifier: Modifier = Modifier) {
    val resource = when (kind) {
        "menu" -> R.drawable.ic_more_vert_24
        "monitor" -> R.drawable.ic_dashboard_24
        "pause" -> R.drawable.ic_pause_24
        "start" -> R.drawable.ic_play_arrow_24
        "stop" -> R.drawable.ic_stop_24
        "close" -> R.drawable.ic_close_24
        "record" -> R.drawable.ic_radio_button_checked_24
        "calibrate" -> R.drawable.ic_center_focus_strong_24
        "history" -> R.drawable.ic_history_24
        "fit" -> R.drawable.ic_fit_screen_24
        "fullscreen" -> R.drawable.ic_fullscreen_24
        "check" -> R.drawable.ic_check_24
        "eye" -> R.drawable.ic_visibility_24
        "eyeOff" -> R.drawable.ic_visibility_off_24
        "chevron" -> R.drawable.ic_expand_more_24
        "devices" -> R.drawable.ic_sensors_24
        "wifi" -> R.drawable.ic_wifi_24
        "settings" -> R.drawable.ic_settings_24
        "add" -> R.drawable.ic_add_24
        "scan" -> R.drawable.ic_search_24
        "export" -> R.drawable.ic_download_24
        "folder" -> R.drawable.ic_folder_open_24
        "map" -> R.drawable.ic_map_24
        "motion" -> R.drawable.ic_screen_rotation_alt_24
        "cir" -> R.drawable.ic_show_chart_24
        "battery" -> R.drawable.ic_battery_horiz_075_24
        "edit" -> R.drawable.ic_edit_24
        "delete" -> R.drawable.ic_delete_24
        "refresh" -> R.drawable.ic_refresh_24
        "info" -> R.drawable.ic_info_24
        "error" -> R.drawable.ic_error_24
        "tune" -> R.drawable.ic_tune_24
        "reset" -> R.drawable.ic_restart_alt_24
        else -> R.drawable.ic_info_24
    }
    Icon(painterResource(resource), contentDescription = null, tint = tint, modifier = modifier.size(24.dp))
}
