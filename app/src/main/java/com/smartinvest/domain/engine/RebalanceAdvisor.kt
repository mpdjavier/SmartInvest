package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.PortfolioValuation
import com.smartinvest.domain.model.RebalanceAdvice
import kotlin.math.abs
import kotlin.math.roundToLong

object RebalanceAdvisor {

    fun analyze(valuation: PortfolioValuation, settings: AppSettings): RebalanceAdvice {
        val target = settings.rebalanceTargetPesoPercent.toDouble().coerceIn(0.0, 100.0)
        val tolerance = settings.rebalanceTolerancePp.coerceAtLeast(0.0)
        val bandLow = (target - tolerance).coerceAtLeast(0.0)
        val bandHigh = (target + tolerance).coerceAtMost(100.0)
        val actual = valuation.pesoSharePercent
        val outOfBand = actual < bandLow || actual > bandHigh

        val targetPesos = (valuation.totalArsCents * target / 100.0).roundToLong()
        val delta = targetPesos - valuation.arsAssetsCents
        val action = when {
            !outOfBand -> "Dentro de la banda elástica ${bandLow.fmt()}% – ${bandHigh.fmt()}%."
            actual > bandHigh ->
                "Cartera cargada a pesos (${actual.fmt()}%). Pasá ${MoneyFormatter.ars(abs(delta))} a Dólar MEP."
            else ->
                "Cartera cargada a dólares (${actual.fmt()}%). Pasá ${MoneyFormatter.ars(delta)} a instrumentos en pesos."
        }

        return RebalanceAdvice(
            pesoPercent = actual,
            dollarPercent = 100.0 - actual,
            targetPesoPercent = target,
            tolerancePp = tolerance,
            bandLow = bandLow,
            bandHigh = bandHigh,
            isOutOfBand = outOfBand,
            suggestedAction = action,
            adjustmentArsCents = delta,
        )
    }

    private fun Double.fmt(): String = String.format(java.util.Locale.forLanguageTag("es-AR"), "%.1f", this)
}
