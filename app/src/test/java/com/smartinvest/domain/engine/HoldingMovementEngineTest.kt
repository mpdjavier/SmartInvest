package com.smartinvest.domain.engine

import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HoldingMovementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HoldingMovementEngineTest {

    private fun holding(
        valueCents: Long = 100_000L,
        costCents: Long = 100_000L,
        quantity: Double = 100.0,
    ) = Holding(
        id = 1,
        symbol = "S30N6",
        name = "LECAP",
        assetType = AssetType.LECAP,
        currency = CurrencyCode.ARS,
        currentValueCents = valueCents,
        costCents = costCents,
        tnaPercent = null,
        maturityDate = null,
        notes = "",
        quantity = quantity,
    )

    @Test
    fun `income increases value cost and quantity proportionally`() {
        val effect = HoldingMovementEngine.apply(
            holding = holding(),
            type = HoldingMovementType.INGRESO,
            amountCents = 20_000L,
        )

        assertEquals(120_000L, effect.valueCents)
        assertEquals(120_000L, effect.costCents)
        assertEquals(120.0, effect.quantity, 0.0001)
    }

    @Test
    fun `withdrawal reduces value and keeps average cost per unit`() {
        val effect = HoldingMovementEngine.apply(
            holding = holding(valueCents = 100_000L, costCents = 80_000L, quantity = 100.0),
            type = HoldingMovementType.EGRESO,
            amountCents = 20_000L,
        )

        assertEquals(80_000L, effect.valueCents)
        assertEquals(64_000L, effect.costCents)
        assertEquals(80.0, effect.quantity, 0.0001)
    }

    @Test
    fun `withdrawal larger than valuation is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            HoldingMovementEngine.apply(
                holding = holding(valueCents = 10_000L),
                type = HoldingMovementType.EGRESO,
                amountCents = 20_000L,
            )
        }
    }

    @Test
    fun `reverting an income restores the previous state`() {
        val original = holding(valueCents = 100_000L, costCents = 80_000L, quantity = 100.0)
        val afterIncome = HoldingMovementEngine.apply(original, HoldingMovementType.INGRESO, 20_000L)
        val holdingAfterIncome = original.copy(
            currentValueCents = afterIncome.valueCents,
            costCents = afterIncome.costCents,
            quantity = afterIncome.quantity,
        )

        val reverted = HoldingMovementEngine.apply(
            holding = holdingAfterIncome,
            type = HoldingMovementType.INGRESO,
            amountCents = 20_000L,
            reverse = true,
        )

        assertEquals(original.currentValueCents, reverted.valueCents)
        assertEquals(original.costCents, reverted.costCents)
        assertEquals(original.quantity, reverted.quantity, 0.0001)
    }

    @Test
    fun `reverting a withdrawal restores the previous state`() {
        val original = holding(valueCents = 100_000L, costCents = 80_000L, quantity = 100.0)
        val afterWithdrawal = HoldingMovementEngine.apply(original, HoldingMovementType.EGRESO, 20_000L)
        val holdingAfterWithdrawal = original.copy(
            currentValueCents = afterWithdrawal.valueCents,
            costCents = afterWithdrawal.costCents,
            quantity = afterWithdrawal.quantity,
        )

        val reverted = HoldingMovementEngine.apply(
            holding = holdingAfterWithdrawal,
            type = HoldingMovementType.EGRESO,
            amountCents = 20_000L,
            reverse = true,
        )

        assertEquals(original.currentValueCents, reverted.valueCents)
        assertEquals(original.costCents, reverted.costCents)
        assertEquals(original.quantity, reverted.quantity, 0.0001)
    }

    @Test
    fun `unknown cost stays at zero`() {
        val effect = HoldingMovementEngine.apply(
            holding = holding(valueCents = 100_000L, costCents = 0L, quantity = 0.0),
            type = HoldingMovementType.INGRESO,
            amountCents = 5_000L,
        )

        assertEquals(105_000L, effect.valueCents)
        assertEquals(0L, effect.costCents)
        assertEquals(0.0, effect.quantity, 0.0001)
    }
}
