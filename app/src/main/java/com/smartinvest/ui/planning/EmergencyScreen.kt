package com.smartinvest.ui.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.domain.model.LiquiditySource
import com.smartinvest.domain.model.LiquidityTier
import com.smartinvest.domain.model.RedemptionLeg
import com.smartinvest.ui.components.AmountInput
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
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

@Composable
fun EmergencyScreen(
    viewModel: EmergencyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val plan = state.plan

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("Simulador de rescate", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Prioriza FCI eco y liquidez inmediata",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            AmountInput(
                value = state.requestedText,
                onValueChange = viewModel::setRequested,
                label = "Monto que necesitás",
                supportingText = "Simulación sin ejecutar ventas reales.",
            )
        }

        if (state.quickAmounts.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.quickAmounts.distinct().forEach { amount ->
                        AssistChip(
                            onClick = { viewModel.useQuickAmount(amount) },
                            label = { Text(Fmt.compact(amount)) },
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "Líquido inmediato",
                    value = Fmt.compact(state.liquidAvailableCents),
                    caption = "FCI T+0 + billeteras",
                    accent = Emerald,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Total disponible",
                    value = Fmt.compact(state.totalAvailableCents),
                    caption = "Todos los activos",
                    accent = Sky,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            SmartCard {
                SectionHeader(
                    title = "Plan de rescate",
                    subtitle = if (plan?.isFullyCovered == true) {
                        "Cubierto en ${plan?.maxSettlementDays ?: 0} día(s)"
                    } else {
                        "Faltan ${Fmt.ars(plan?.shortfallCents ?: 0)}"
                    },
                )
                ThinDivider()
                KeyValueRow("Solicitado", Fmt.ars(plan?.requestedCents ?: 0))
                KeyValueRow("Cubierto", Fmt.ars(plan?.coveredCents ?: 0), valueColor = Emerald, strong = true)
                if ((plan?.shortfallCents ?: 0) > 0) {
                    KeyValueRow("Faltante", Fmt.ars(plan?.shortfallCents ?: 0), valueColor = Rose, strong = true)
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(
                        "El monto supera los activos cargados. Revisá saldos en Billeteras o cargá activos faltantes.",
                        color = Rose,
                    )
                }
                if (plan != null && plan.requestedCents > 0 && plan.legs.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    InfoBanner("No hay fuentes con saldo para rescatar.", color = Amber)
                }
            }
        }

        if (plan != null && plan.legs.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Orden de extracción",
                    subtitle = "Se ejecuta de menor a mayor fricción",
                )
            }
            items(plan.legs, key = { it.sourceName + it.amountCents }) { leg ->
                RedemptionLegRow(leg)
            }
        }

        item {
            ThinDivider()
        }

        item {
            SectionHeader(
                title = "Fuentes de liquidez",
                subtitle = "${state.sources.size} cuentas y activos",
            )
        }

        if (state.sources.isEmpty()) {
            item {
                InfoBanner(
                    "Cargá saldos en billeteras o activos para simular rescates de emergencia.",
                    color = Sky,
                )
            }
        } else {
            items(state.sources, key = { it.id }) { source ->
                SourceRow(source)
            }
        }
    }
}

@Composable
private fun RedemptionLegRow(leg: RedemptionLeg) {
    val color = tierColor(leg.tier)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(leg.sourceName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(6.dp))
                TagPill(leg.tier.label, color)
            }
            Text(
                "${leg.note} · ${if (leg.settlementDays == 0) "hoy" else "${leg.settlementDays} día(s)"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(Fmt.ars(leg.amountCents), style = MaterialTheme.typography.titleSmall, color = color)
    }
}

@Composable
private fun SourceRow(source: LiquiditySource) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.titleSmall)
            Text(
                source.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.ars(source.availableCents), style = MaterialTheme.typography.titleSmall)
            TagPill(source.tier.label, tierColor(source.tier))
        }
    }
}

private fun tierColor(tier: LiquidityTier) = when (tier) {
    LiquidityTier.FCI_T0 -> Emerald
    LiquidityTier.WALLET -> Sky
    LiquidityTier.LECAP -> Violet
    LiquidityTier.USD_HARD_CURRENCY -> Amber
    LiquidityTier.OTHER -> Rose
}
