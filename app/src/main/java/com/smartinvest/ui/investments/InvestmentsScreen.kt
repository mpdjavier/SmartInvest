@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.smartinvest.ui.investments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CarryRiskBand
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HoldingValuation
import com.smartinvest.domain.model.InstrumentBreakEven
import com.smartinvest.domain.usecase.PortfolioOverview
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.LinearBar
import com.smartinvest.ui.components.MetricPill
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.StatTile
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.theme.Violet
import com.smartinvest.ui.util.Fmt
import kotlin.math.roundToLong

@Composable
fun InvestmentsScreen(
    onAddHolding: () -> Unit,
    onEditHolding: (Long) -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenSalary: () -> Unit,
    onOpenEmergency: () -> Unit,
    onOpenDailyInterest: () -> Unit = {},
    onOpenInstrumentDetail: (Long) -> Unit = {},
    onOpenRebalancing: () -> Unit = {},
    onOpenBreakEven: () -> Unit = {},
    viewModel: InvestmentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val overview = state.overview

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cartera", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Activos, FCI, letras y cobertura en dólares",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::refreshMarket, enabled = !state.isWorking) {
                    if (state.isWorking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Emerald,
                        )
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Actualizar", tint = Emerald)
                    }
                }
            }
        }

        state.message?.let { message ->
            item {
                Column {
                    InfoBanner(message, color = Sky)
                    TextButton(onClick = viewModel::clearMessage) { Text("Cerrar") }
                }
            }
        }

        item { ValuationCard(overview) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "En pesos",
                    value = Fmt.compact(overview?.valuation?.arsAssetsCents ?: 0),
                    caption = Fmt.percent(overview?.valuation?.pesoSharePercent),
                    accent = Emerald,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "En dólares",
                    value = Fmt.compact(overview?.valuation?.usdAssetsArsCents ?: 0),
                    caption = Fmt.percent(overview?.valuation?.dollarSharePercent),
                    accent = Sky,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Interés mes",
                    value = Fmt.compact(overview?.valuation?.monthlyInterestCents ?: 0),
                    caption = "TNA ponderada",
                    accent = Amber,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Button(
                onClick = viewModel::syncRates,
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Rounded.TrendingUp, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sincronizar TNA")
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onOpenDailyInterest,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Insights, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Interés diario")
                }
                OutlinedButton(
                    onClick = { onOpenInstrumentDetail(0L) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ShowChart, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Evolución / FCI")
                }
            }
        }

        item { RebalanceCard(overview, onOpenRebalancing) }

        item { DollarBreakEvenCard(overview, onOpenBreakEven) }

        item {
            SectionHeader(
                title = "Letras y activos",
                subtitle = "LECAP, BONCAP, bonos, FCI y MEP",
                actionLabel = "Agregar",
                onAction = onAddHolding,
            )
        }

        if (state.holdings.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.AutoMirrored.Rounded.ShowChart,
                    title = "Sin activos cargados",
                    message = "Cargá LECAP/BONCAP, bonos, CEDEARs o dólares MEP con su valuación y vencimiento.",
                )
            }
        } else {
            val valuations = overview?.valuation?.holdings.orEmpty()
            items(state.holdings.size) { index ->
                val holding = state.holdings[index]
                val valuation = valuations.firstOrNull { it.holding.id == holding.id }
                HoldingRow(
                    holding = holding,
                    valueArsCents = valuation?.valueArsCents ?: holding.currentValueCents,
                    dailyInterestCents = valuation?.dailyInterestCents ?: 0L,
                    breakEven = overview?.breakEven?.holding(holding.id),
                    onOpenEvolution = { onOpenInstrumentDetail(holding.id) },
                    onEdit = { onEditHolding(holding.id) },
                )
            }
        }

        item {
            ThinDivider()
        }

        item {
            SmartCard {
                Text("Planificación", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onOpenAnalytics,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Insights, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Analítica", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onOpenSalary,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.TrendingUp, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sueldo", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onOpenEmergency,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("Rescate", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun ValuationCard(overview: PortfolioOverview?) {
    val valuation = overview?.valuation
    SmartCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PATRIMONIO TOTAL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            MetricPill(
                text = if (valuation?.mepIsEstimated != false) "MEP estimado" else "MEP en vivo",
                color = if (valuation?.mepIsEstimated != false) Amber else Sky,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            Fmt.ars(valuation?.totalArsCents ?: 0),
            style = MaterialTheme.typography.headlineLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            Fmt.usd(valuation?.totalUsdCents ?: 0),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MetricPill(
                text = "7d ${Fmt.signedPercent(overview?.analytics?.change7d?.percent)}",
                color = windowColor(overview?.analytics?.change7d?.percent),
            )
            MetricPill(
                text = "30d ${Fmt.signedPercent(overview?.analytics?.change30d?.percent)}",
                color = windowColor(overview?.analytics?.change30d?.percent),
            )
            MetricPill(
                text = "YTD ${Fmt.signedPercent(overview?.analytics?.ytd?.percent)}",
                color = windowColor(overview?.analytics?.ytd?.percent),
            )
        }
        ThinDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Interés / día",
                value = Fmt.ars(valuation?.dailyInterestCents ?: 0),
                accent = Emerald,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Ganancia no realizada",
                value = Fmt.signedArs(valuation?.unrealizedGainCents ?: 0),
                accent = when {
                    (valuation?.unrealizedGainCents ?: 0) >= 0 -> Emerald
                    else -> Rose
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RebalanceCard(data: PortfolioOverview?, onOpenRebalancing: () -> Unit) {
    val overview = data ?: return
    val advice = overview.rebalance
    val macro = overview.macro
    val riskColor = riskColor(macro.band)
    SmartCard {
        SectionHeader(
            title = "Rebalanceo elástico",
            subtitle = "Cartera invertible · objetivo ${Fmt.percent(advice.targetPesoPercent, 0)} en pesos · banda ±${Fmt.percent(advice.tolerancePp, 0)}",
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "ÍNDICE DE CARRY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        macro.score.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        color = riskColor,
                    )
                    Text(
                        " / 100",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            TagPill("${macro.band.emoji} ${macro.band.label}", riskColor)
        }
        Spacer(Modifier.height(8.dp))
        LinearBar(fraction = macro.score / 100f, color = riskColor, height = 7)
        Spacer(Modifier.height(8.dp))
        Text(
            macro.suggestion,
            style = MaterialTheme.typography.bodySmall,
            color = riskColor,
        )
        val guardrail = macro.liquidityGuardrail
        if (macro.countryRiskCeilingActive || macro.fxGapSpeedCeilingActive || !guardrail.isSatisfied) {
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    if (macro.countryRiskCeilingActive) {
                        append("Techo AMARILLO por riesgo país (${macro.countryRiskPoints} pts). ")
                    }
                    if (macro.fxGapSpeedCeilingActive) {
                        append("Brecha acelerando ${Fmt.pp(macro.fxGapSpeedPp)} en 30 días. ")
                    }
                    if (!guardrail.isSatisfied) {
                        append("Guardarraíl de liquidez: ")
                        append(guardrail.detail)
                    }
                }.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Pesos",
                value = Fmt.percent(advice.pesoPercent),
                caption = Fmt.compact(overview.investableValuation.arsAssetsCents),
                accent = Emerald,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Dólares",
                value = Fmt.percent(advice.dollarPercent),
                caption = Fmt.compact(overview.investableValuation.usdAssetsArsCents),
                accent = Sky,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearBar(
            fraction = (advice.pesoPercent / 100.0).toFloat(),
            color = if (advice.isOutOfBand) Amber else Emerald,
        )
        Spacer(Modifier.height(10.dp))
        InfoBanner(
            advice.suggestedAction,
            color = if (advice.isOutOfBand) Amber else Emerald,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenRebalancing, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Insights, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Abrir asistente macro y próximo sueldo")
        }
    }
}

private fun riskColor(band: CarryRiskBand): Color = when (band) {
    CarryRiskBand.LOW -> Emerald
    CarryRiskBand.MODERATE -> Amber
    CarryRiskBand.HIGH -> Rose
}

@Composable
private fun DollarBreakEvenCard(data: PortfolioOverview?, onOpen: () -> Unit) {
    val report = data?.breakEven
    val summary = report?.pesoSummary.orEmpty()
    SmartCard {
        SectionHeader(
            title = "Dólar break-even",
            subtitle = "MEP que iguala comprar dólares con invertir en pesos",
        )
        Spacer(Modifier.height(10.dp))
        if (summary.isEmpty()) {
            Text(
                "Cargá posiciones en pesos para calcular el break-even de la cartera.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Promedio ponderado de las posiciones en pesos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                summary.forEach { point ->
                    StatTile(
                        label = "${point.days} días",
                        value = formatMep(point.breakEvenMepRate),
                        caption = "MEP ${Fmt.signedPercent(point.requiredDevaluationPercent)}",
                        accent = Amber,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Calculate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Ver break-even por instrumento")
        }
    }
}

@Composable
private fun HoldingRow(
    holding: Holding,
    valueArsCents: Long,
    dailyInterestCents: Long,
    breakEven: InstrumentBreakEven? = null,
    onOpenEvolution: () -> Unit,
    onEdit: () -> Unit,
) {
    val color = assetColor(holding.assetType)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(holding.symbol, style = MaterialTheme.typography.titleSmall)
                TagPill(holding.assetType.label, color)
                if (holding.isTracked) {
                    TagPill(holding.source.label, Sky)
                }
            }
            Text(
                holding.name.ifBlank { holding.assetType.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (holding.assetType == AssetType.CEDEAR && holding.currency == CurrencyCode.ARS) {
                Text(
                    "Computa como USD en rebalanceo 60/40",
                    style = MaterialTheme.typography.bodySmall,
                    color = Sky,
                )
            }
            val maturity = holding.maturityDate
            if (maturity != null) {
                Text(
                    "Vence ${Fmt.longDate(maturity)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
            }
            BreakEvenSummaryLine(breakEven)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (holding.currency == CurrencyCode.USD) {
                    Fmt.usd(holding.currentValueCents)
                } else {
                    Fmt.ars(valueArsCents)
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Text(
                if (holding.currency == CurrencyCode.USD) {
                    "≈ ${Fmt.ars(valueArsCents)}"
                } else {
                    "ARS"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (dailyInterestCents != 0L) {
                Text(
                    "${Fmt.signedArs(dailyInterestCents)} / día",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dailyInterestCents >= 0) Emerald else Rose,
                    maxLines = 1,
                )
            }
        }
        if (holding.isTracked) {
            IconButton(onClick = onOpenEvolution) {
                Icon(
                    Icons.AutoMirrored.Rounded.ShowChart,
                    contentDescription = "Evolución",
                    tint = Emerald,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Rounded.Edit, contentDescription = "Editar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BreakEvenSummaryLine(breakEven: InstrumentBreakEven?) {
    if (breakEven == null) return
    if (breakEven.isDollarPosition) {
        Text(
            "Break-even: ya en dólares",
            style = MaterialTheme.typography.bodySmall,
            color = Sky,
        )
    } else {
        Text(
            "BE " + breakEven.points.joinToString(" · ") {
                "${it.days}d ${formatMep(it.breakEvenMepRate)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Amber,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatMep(rate: Double?): String =
    if (rate == null || rate <= 0.0) "—" else Fmt.ars((rate * 100.0).roundToLong())

private fun windowColor(percent: Double?): Color = when {
    percent == null -> Sky
    percent >= 0 -> Emerald
    else -> Rose
}

private fun assetColor(type: AssetType): Color = when (type) {
    AssetType.LECAP, AssetType.BONCAP -> Emerald
    AssetType.BOND, AssetType.DOLAR_MEP -> Sky
    AssetType.FCI -> Violet
    AssetType.CEDEAR, AssetType.CRYPTO -> Amber
    AssetType.OTHER -> Sky
}
