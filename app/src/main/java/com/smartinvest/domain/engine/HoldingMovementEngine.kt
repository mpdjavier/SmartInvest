package com.smartinvest.domain.engine

import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HoldingMovementType
import kotlin.math.roundToLong

/** Result of applying (or reverting) a manual movement on a holding. */
data class HoldingMovementEffect(
    val valueCents: Long,
    val costCents: Long,
    val quantity: Double,
)

/**
 * Applies manual income / withdrawals to a holding while keeping valuation,
 * net cost and quantity consistent.
 *
 * Income adds the amount to the valuation and the cost basis. Withdrawals reduce
 * the valuation and scale cost and quantity proportionally, so the average cost
 * per unit is preserved. [reverse] performs the inverse operation, which is used
 * when a movement is deleted.
 */
object HoldingMovementEngine {

    fun apply(
        holding: Holding,
        type: HoldingMovementType,
        amountCents: Long,
        reverse: Boolean = false,
    ): HoldingMovementEffect {
        require(amountCents > 0L) { "El monto del movimiento debe ser mayor a cero" }

        val isIncome = type == HoldingMovementType.INGRESO
        val addsValue = isIncome != reverse
        val newValue = if (addsValue) {
            holding.currentValueCents + amountCents
        } else {
            holding.currentValueCents - amountCents
        }
        require(newValue >= 0L) {
            if (isIncome) "El monto supera la valuación actual del activo" else "El egreso supera la valuación actual del activo"
        }

        val oldValue = holding.currentValueCents
        val ratio = if (oldValue > 0L) newValue.toDouble() / oldValue.toDouble() else 1.0

        val newCost = when {
            // Transactional wallet FCIs do not track an investment cost basis: their cost
            // follows the available balance so payments never create a fake unrealized loss.
            holding.isWallet -> newValue
            holding.costCents <= 0L -> 0L
            // Income adds to the cost basis; reverting that income subtracts it.
            isIncome && !reverse -> holding.costCents + amountCents
            isIncome && reverse -> (holding.costCents - amountCents).coerceAtLeast(0L)
            // Withdrawals keep the average cost per unit: scale proportionally.
            oldValue > 0L -> (holding.costCents.toDouble() * ratio).roundToLong()
            else -> holding.costCents
        }

        val newQuantity = if (holding.quantity > 0.0 && oldValue > 0L) {
            holding.quantity * ratio
        } else {
            holding.quantity
        }

        return HoldingMovementEffect(
            valueCents = newValue,
            costCents = newCost,
            quantity = newQuantity,
        )
    }
}
