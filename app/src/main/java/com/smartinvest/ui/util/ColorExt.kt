package com.smartinvest.ui.util

import androidx.compose.ui.graphics.Color
import com.smartinvest.domain.model.AlertSeverity
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky

fun colorFromHex(hex: String, fallback: Color = Sky): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

fun severityColor(severity: AlertSeverity): Color = when (severity) {
    AlertSeverity.CRITICAL -> Rose
    AlertSeverity.WARNING -> Amber
    AlertSeverity.INFO -> Sky
}
