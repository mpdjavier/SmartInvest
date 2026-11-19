package com.smartinvest.domain.model

import java.time.LocalDate

/** Manual cash flow registered against a single holding. */
enum class HoldingMovementType(val label: String) {
    INGRESO("Ingreso"),
    EGRESO("Egreso"),
}

/**
 * A manual contribution ([HoldingMovementType.INGRESO]) or withdrawal
 * ([HoldingMovementType.EGRESO]) made on a holding. Amounts are expressed in the
 * holding currency, so a USD asset keeps its movements in USD.
 */
data class HoldingMovement(
    val id: Long,
    val holdingId: Long,
    val type: HoldingMovementType,
    val amountCents: Long,
    val currency: CurrencyCode,
    val date: LocalDate,
    val note: String,
    val createdAt: Long,
) {
    /** Positive for income, negative for withdrawals. */
    val signedCents: Long
        get() = if (type == HoldingMovementType.INGRESO) amountCents else -amountCents

    val isIncome: Boolean get() = type == HoldingMovementType.INGRESO
}
