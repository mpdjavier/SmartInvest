package com.smartinvest.ui.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Global privacy switch for monetary amounts.
 *
 * It is backed by a Compose [mutableStateOf], so any composable that formats money
 * through [Fmt] automatically recomposes when the user toggles the option in
 * Ajustes. Percentages, dates and quantities stay visible on purpose: the goal is
 * to avoid showing balances in public, not to hide the whole screen.
 */
object MoneyVisibility {

    /** Asterisks shown instead of every amount. */
    const val MASK = "****"

    var hidden by mutableStateOf(false)
        private set

    fun update(hidden: Boolean) {
        this.hidden = hidden
    }
}
