package com.smartinvest.ui.planning

import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.LiquiditySource
import com.smartinvest.domain.model.LiquidityTier
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.model.WalletType
import kotlin.math.roundToLong

/**
 * Builds the ordered liquidity ladder used by the emergency redemption simulator.
 * FCI T+0 first, then wallets, then letters, then hard currency.
 */
object LiquiditySources {

    const val ID_PREFIX_WALLET = "wallet:"
    const val ID_PREFIX_HOLDING = "holding:"

    fun fromWallet(wallet: Wallet, mepRate: Double): LiquiditySource? {
        if (!wallet.isActive || wallet.balanceCents <= 0) return null
        val availableArs = if (wallet.currency == CurrencyCode.ARS) {
            wallet.balanceCents
        } else {
            (wallet.balanceCents * mepRate).roundToLong()
        }
        if (availableArs <= 0) return null
        val (tier, days, note) = when (wallet.type) {
            WalletType.MONEY_MARKET_FUND -> Triple(LiquidityTier.FCI_T0, 0, "Rescate T+0 en el día")
            WalletType.WALLET -> Triple(LiquidityTier.WALLET, 0, "Transferencia inmediata")
            WalletType.BANK -> Triple(LiquidityTier.WALLET, 1, "Transferencia / débito")
            WalletType.BROKER -> Triple(LiquidityTier.OTHER, 1, "Disponible en bróker")
            WalletType.OTHER -> Triple(LiquidityTier.OTHER, 1, "Cuenta alternativa")
        }
        return LiquiditySource(
            id = "$ID_PREFIX_WALLET${wallet.id}",
            name = wallet.name,
            tier = tier,
            availableCents = availableArs,
            settlementDays = days,
            note = note,
        )
    }

    fun fromHolding(holding: Holding, mepRate: Double): LiquiditySource? {
        if (holding.currentValueCents <= 0) return null
        val availableArs = if (holding.currency == CurrencyCode.ARS) {
            holding.currentValueCents
        } else {
            (holding.currentValueCents * mepRate).roundToLong()
        }
        if (availableArs <= 0) return null
        val (tier, days, note) = when (holding.assetType) {
            AssetType.FCI -> Triple(LiquidityTier.FCI_T0, 1, "Rescate del fondo")
            AssetType.LECAP, AssetType.BONCAP -> Triple(LiquidityTier.LECAP, 1, "Venta en bróker (T+1)")
            AssetType.BOND -> Triple(LiquidityTier.LECAP, 1, "Venta en bróker (T+1)")
            AssetType.DOLAR_MEP -> Triple(LiquidityTier.USD_HARD_CURRENCY, 1, "MEP a pesos (T+1)")
            AssetType.CEDEAR -> Triple(LiquidityTier.OTHER, 2, "Liquidación CEDEAR (T+2)")
            AssetType.CRYPTO -> Triple(LiquidityTier.OTHER, 0, "Stablecoin / exchange")
            AssetType.OTHER -> Triple(LiquidityTier.OTHER, 2, "Activo alternativo")
        }
        return LiquiditySource(
            id = "$ID_PREFIX_HOLDING${holding.id}",
            name = if (holding.name.isBlank()) holding.symbol else "${holding.symbol} · ${holding.name}",
            tier = tier,
            availableCents = availableArs,
            settlementDays = days,
            note = note,
        )
    }

    fun build(wallets: List<Wallet>, holdings: List<Holding>, mepRate: Double): List<LiquiditySource> =
        wallets.mapNotNull { fromWallet(it, mepRate) } +
            holdings.mapNotNull { fromHolding(it, mepRate) }

    fun liquidForEmergency(sources: List<LiquiditySource>): Long =
        sources.filter { it.tier == LiquidityTier.FCI_T0 || it.tier == LiquidityTier.WALLET }
            .sumOf { it.availableCents }
}
