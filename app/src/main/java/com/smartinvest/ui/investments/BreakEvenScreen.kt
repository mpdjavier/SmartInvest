@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.smartinvest.ui.investments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.smartinvest.domain.model.BreakEvenKind
import com.smartinvest.domain.model.BreakEvenPoint
import com.smartinvest.domain.model.BreakEvenRateSource
import com.smartinvest.domain.model.DollarBreakEvenReport
import com.smartinvest.domain.model.InstrumentBreakEven
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.StatTile
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.util.Fmt
import kotlin.math.pow
import kotlin.math.roundToLong

@Composable
fun BreakEvenScreen(
    viewModel: BreakEvenViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val report = state.overview?.breakEven

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dólar break-even", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "A qué MEP conviene comprar dólares en lugar de seguir en cada instrumento",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::refreshDollar, enabled = !state.isWorking) {
                    if (state.isWorking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Emerald,
                        )
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Actualizar dólar", tint = Emerald)
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

        item { BreakEvenSummaryCard(report) }

        item {
            InfoBanner(
                "Break-even = MEP actual × (1 + TNA × días ÷ 365).\n" +
                    "· Si el dólar termina por encima, comprar dólares hoy rinde más.\n" +
                    "· Si termina por debajo, el instrumento gana.\n" +
                    "· Los activos en USD (incluidos CEDEARs) ya son cobertura en dólares: " +
                    "no tienen break-even cambiario.",
                color = Sky,
            )
        }

        val items = report?.items.orEmpty()
        val wallets = items.filter { it.kind == BreakEvenKind.WALLET }
        val holdings = items.filter { it.kind == BreakEvenKind.HOLDING }

        if (items.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Calculate,
                    title = "Sin instrumentos para calcular",
                    message = "Cargá saldo en billeteras o activos con valuación para ver su dólar break-even.",
                )
            }
        }

        if (wallets.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Billeteras y cuentas",
                    subtitle = "Break-even de cada saldo remunerado",
                )
            }
            items(wallets, key = { it.id }) { item ->
                BreakEvenCard(item)
            }
        }

        if (holdings.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Letras y activos",
                    subtitle = "Break-even por instrumento a 30, 60 y 120 días",
                )
            }
            items(holdings, key = { it.id }) { item ->
                BreakEvenCard(item)
            }
        }
    }
}

@Composable
private fun BreakEvenSummaryCard(report: DollarBreakEvenReport?) {
    val mep = report?.mepRate ?: 0.0
    val summary = report?.pesoSummary.orEmpty()
    SmartCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "MEP ACTUAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatMep(mep),
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TagPill(
                text = if (report?.mepIsEstimated != false) "MEP estimado" else "MEP en vivo",
                color = if (report?.mepIsEstimated != false) Amber else Sky,
            )
        }
        if (summary.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Sin posiciones en pesos para calcular un break-even de cartera.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SmartCard
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Break-even ponderado de la cartera en pesos",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ThinDivider()
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
        Spacer(Modifier.height(8.dp))
        val devaluation = report?.monthlyDevaluationPercent ?: 0.0
        Text(
            "Con la devaluación esperada de ${Fmt.percent(devaluation)} mensual, el MEP proyectado " +
                "sería ${formatMep(expectedMep(mep, devaluation, 30))} a 30 d, " +
                "${formatMep(expectedMep(mep, devaluation, 60))} a 60 d y " +
                "${formatMep(expectedMep(mep, devaluation, 120))} a 120 d.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BreakEvenCard(
    item: InstrumentBreakEven,
) {
    val sourceColor = sourceColor(item.rateSource)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TagPill(item.rateSource.label, sourceColor)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${Fmt.ars(item.valueArsCents)} · TNA ${Fmt.rate(item.annualRatePercent)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ThinDivider()

        if (item.isDollarPosition) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagPill("Ya en dólares", Sky)
                Spacer(Modifier.width(8.dp))
                Text(
                    item.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item.points.forEach { point ->
                    BreakEvenPointColumn(point)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RowScope.BreakEvenPointColumn(
    point: BreakEvenPoint,
) {
    val color = pointColor(point)
    Column(Modifier.weight(1f)) {
        Text(
            "${point.days} d",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            formatMep(point.breakEvenMepRate),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (point.requiredDevaluationPercent != null) {
                "MEP ${Fmt.signedPercent(point.requiredDevaluationPercent)}"
            } else {
                "USD ${Fmt.signedPercent(point.instrumentReturnPercent)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val expected = point.expectedMepRate
        if (point.breakEvenMepRate != null && expected > 0.0) {
            Text(
                "esp. ${formatMep(expected)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (point.dollarWinsAtExpectedMep == true) Rose else Emerald,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun pointColor(point: BreakEvenPoint): Color = when (point.dollarWinsAtExpectedMep) {
    true -> Rose
    false -> Emerald
    null -> Sky
}

private fun sourceColor(source: BreakEvenRateSource): Color = when (source) {
    BreakEvenRateSource.OWN_TNA -> Emerald
    BreakEvenRateSource.REFERENCE_TNA -> Amber
    BreakEvenRateSource.NO_RATE -> Rose
    BreakEvenRateSource.ALREADY_USD -> Sky
}

private fun formatMep(rate: Double?): String =
    if (rate == null || rate <= 0.0) "—" else Fmt.ars((rate * 100.0).roundToLong())

private fun expectedMep(mep: Double, monthlyDevaluationPercent: Double, days: Int): Double =
    mep * (1.0 + monthlyDevaluationPercent / 100.0).pow(days / 30.0)
