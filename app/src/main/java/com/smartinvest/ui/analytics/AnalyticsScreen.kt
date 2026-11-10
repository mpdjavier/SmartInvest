package com.smartinvest.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.domain.model.PerformanceWindow
import com.smartinvest.domain.model.PortfolioSnapshot
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.PortfolioChart
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.StatTile
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.util.Fmt

@Composable
fun AnalyticsScreen(
    onOpenDailyInterest: () -> Unit = {},
    onOpenInstrumentDetail: () -> Unit = {},
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val overview = state.overview

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Analítica patrimonial", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "7 días, 30 días, YTD y ganancia diaria",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::refresh, enabled = !state.isWorking) {
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

        item {
            SmartCard {
                Text("Patrimonio actual", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    Fmt.ars(overview?.valuation?.totalArsCents ?: 0),
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    Fmt.usd(overview?.valuation?.totalUsdCents ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WindowTile(overview?.analytics?.change7d, Modifier.weight(1f))
                WindowTile(overview?.analytics?.change30d, Modifier.weight(1f))
                WindowTile(overview?.analytics?.ytd, Modifier.weight(1f))
            }
        }

        item {
            SmartCard {
                SectionHeader(
                    title = "Últimos 90 días",
                    subtitle = if (state.series.size >= 2) {
                        "${Fmt.shortDate(state.series.first().date)} → ${Fmt.shortDate(state.series.last().date)}"
                    } else {
                        "Necesitás al menos 2 snapshots"
                    },
                )
                Spacer(Modifier.height(12.dp))
                if (state.series.size >= 2) {
                    PortfolioChart(
                        values = state.series.map { it.totalArsCents },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        lineColor = Emerald,
                    )
                } else {
                    InfoBanner(
                        "El historial se construye con un snapshot por día. Abrí la app o usá Actualizar " +
                            "para registrar el de hoy.",
                        color = Sky,
                    )
                }
            }
        }

        item {
            SmartCard {
                SectionHeader(
                    title = "Ganancia y rendimiento",
                    subtitle = "Estimaciones sobre TNA y cotizaciones reales",
                    actionLabel = "Ver día a día",
                    onAction = onOpenDailyInterest,
                )
                ThinDivider()
                KeyValueRow("Interés estimado por día", Fmt.ars(overview?.analytics?.dailyInterestCents ?: 0), valueColor = Emerald, strong = true)
                KeyValueRow("Proyección mensual", Fmt.ars(overview?.analytics?.monthlyInterestCents ?: 0), valueColor = Emerald)
                KeyValueRow(
                    "Ganancia últimos 30 días",
                    Fmt.signedArs(overview?.analytics?.trailing30dGainCents ?: 0),
                    valueColor = gainColor(overview?.analytics?.trailing30dGainCents ?: 0),
                )
                KeyValueRow(
                    "Ganancia promedio por día",
                    Fmt.signedArs(overview?.analytics?.trailing30dGainPerDayCents ?: 0),
                    valueColor = gainColor(overview?.analytics?.trailing30dGainPerDayCents ?: 0),
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onOpenInstrumentDetail) {
                    Text("Ver evolución de instrumentos (Rava) y FCI (CAFCI)")
                }
            }
        }

        item {
            SmartCard {
                SectionHeader(title = "Composición", subtitle = "Pesos vs. dólares y costo")
                ThinDivider()
                KeyValueRow("Activos en pesos", Fmt.ars(overview?.valuation?.arsAssetsCents ?: 0))
                KeyValueRow("Activos en dólares (ARS)", Fmt.ars(overview?.valuation?.usdAssetsArsCents ?: 0))
                KeyValueRow("MEP usado", Fmt.ars(((overview?.valuation?.mepRate ?: 0.0) * 100).toLong()))
                KeyValueRow("Costo total", Fmt.ars(overview?.valuation?.totalCostCents ?: 0))
                KeyValueRow(
                    "Resultado no realizado",
                    Fmt.signedArs(overview?.valuation?.unrealizedGainCents ?: 0),
                    valueColor = gainColor(overview?.valuation?.unrealizedGainCents ?: 0),
                    strong = true,
                )
            }
        }

        if (state.series.isNotEmpty()) {
            item {
                SectionHeader(title = "Historial de snapshots", subtitle = "${state.series.size} registros")
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.series.takeLast(14).reversed().forEach { snapshot ->
                        SnapshotRow(snapshot)
                    }
                }
            }
        }

        item {
            Text(
                "Los rendimientos se estiman con la TNA cargada y el tope remunerado. " +
                    "No incluyen impuestos, comisiones ni variaciones de cuotapartes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WindowTile(window: PerformanceWindow?, modifier: Modifier = Modifier) {
    val percent = window?.percent
    val color = when {
        percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
        percent >= 0 -> Emerald
        else -> Rose
    }
    SmartCard(modifier = modifier) {
        Text(
            window?.label ?: "—",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(Fmt.signedPercent(window?.percent), style = MaterialTheme.typography.titleLarge, color = color)
        Text(
            window?.gainCents?.let(Fmt::signedArs) ?: "Sin base",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SnapshotRow(snapshot: PortfolioSnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Fmt.longDate(snapshot.date),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(Fmt.ars(snapshot.totalArsCents), style = MaterialTheme.typography.titleSmall)
    }
}

private fun gainColor(value: Long): Color = if (value >= 0) Emerald else Rose

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    StatTile(label = label, value = value, modifier = modifier)
}
