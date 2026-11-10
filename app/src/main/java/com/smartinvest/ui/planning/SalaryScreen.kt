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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.domain.model.AllocationBucket
import com.smartinvest.ui.components.AmountInput
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.LinearBar
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.StatTile
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.theme.Violet
import com.smartinvest.ui.util.Fmt

@Composable
fun SalaryScreen(
    onOpenSettings: () -> Unit,
    viewModel: SalaryViewModel = hiltViewModel(),
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
                Text("Repartir sueldo", style = MaterialTheme.typography.titleLarge)
                Text(
                    Fmt.month(plan?.month ?: java.time.YearMonth.now()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            AmountInput(
                value = state.salaryText,
                onValueChange = viewModel::setSalary,
                label = "Sueldo neto del mes",
                supportingText = "Se descuenta el gasto real del hogar para calcular el colchón.",
            )
        }

        item {
            SmartCard {
                SectionHeader(
                    title = "Colchón del hogar",
                    subtitle = "Vinculado al gasto real de ${Fmt.month(plan?.month ?: java.time.YearMonth.now())}",
                )
                ThinDivider()
                KeyValueRow("Gasto compartido del mes", Fmt.ars(state.summary?.totalSpendCents ?: 0))
                KeyValueRow("Cuotas del mes", Fmt.ars(state.summary?.installmentSpendCents ?: 0), valueColor = Amber)
                KeyValueRow(
                    "Fondo común disponible",
                    Fmt.ars(state.summary?.fundBalanceCents ?: 0),
                    valueColor = Sky,
                )
                KeyValueRow(
                    "Aporte neto pendiente",
                    Fmt.ars(state.summary?.totalNetContributionsCents ?: 0),
                    strong = true,
                )
                if ((state.summary?.fundBalanceCents ?: 0) > 0) {
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(
                        "El fondo común ya cubre ${Fmt.ars(state.summary?.fundBalanceCents ?: 0)}. " +
                            "Podés reducir el colchón si el fondo alcanza para las compras del mes.",
                        color = Sky,
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "Sueldo",
                    value = Fmt.compact(plan?.netSalaryCents ?: 0),
                    accent = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Colchón",
                    value = Fmt.compact(plan?.householdCushionCents ?: 0),
                    accent = Amber,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "A invertir",
                    value = Fmt.compact(plan?.investableCents ?: 0),
                    accent = Emerald,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if ((plan?.deficitCents ?: 0) > 0) {
            item {
                InfoBanner(
                    "El gasto del hogar supera el sueldo por ${Fmt.ars(plan?.deficitCents ?: 0)}. " +
                        "Revisá el fondo común o las cuotas antes de invertir.",
                    color = Rose,
                )
            }
        }

        item {
            SmartCard {
                SectionHeader(
                    title = "Distribución del remanente",
                    subtitle = "FCI T+0 · letras a tasa fija · cobertura MEP",
                    actionLabel = "Ajustar %",
                    onAction = onOpenSettings,
                )
                Spacer(Modifier.height(10.dp))
                plan?.allocations?.forEach { line ->
                    val color = when (line.bucket) {
                        AllocationBucket.FCI_T0 -> Emerald
                        AllocationBucket.LECAP -> Violet
                        AllocationBucket.MEP -> Sky
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${line.bucket.label} · ${line.percent}%", style = MaterialTheme.typography.titleSmall)
                            Text(
                                line.bucket.destination,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Fmt.ars(line.amountCents), style = MaterialTheme.typography.titleSmall, color = color)
                    }
                    LinearBar(
                        fraction = if ((plan.investableCents) > 0) {
                            line.amountCents.toFloat() / plan.investableCents.toFloat()
                        } else {
                            0f
                        },
                        color = color,
                        height = 6,
                    )
                }
                if (plan == null || plan.investableCents == 0L) {
                    InfoBanner("Ingresá el sueldo para ver el reparto del remanente.", color = Sky)
                }
            }
        }

        item {
            SmartCard {
                SectionHeader(
                    title = "Cobertura de emergencia",
                    subtitle = "FCI T+0 + billeteras disponibles",
                )
                ThinDivider()
                KeyValueRow("Líquido inmediato", Fmt.ars(plan?.emergencyLiquidCents ?: 0), valueColor = Emerald, strong = true)
                KeyValueRow(
                    "Meses de cobertura",
                    Fmt.coverage(plan?.emergencyCoverageMonths ?: 0.0),
                    valueColor = if ((plan?.emergencyCoverageMonths ?: 0.0) >= 3.0) Emerald else Amber,
                )
                KeyValueRow("Fuentes líquidas", "${state.sources.count { it.settlementDays <= 1 }} cuentas")
                Spacer(Modifier.height(6.dp))
                Text(
                    "El simulador de rescates prioriza FCI eco, luego billeteras y por último letras y dólares.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::savePlan,
                    enabled = !state.isSaving && (plan?.netSalaryCents ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Guardando…")
                    } else {
                        Text("Guardar plan")
                    }
                }
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Ajustar porcentajes")
                }
            }
        }

        item {
            Text(
                "Sugerencia: mantené al menos 3 meses de gastos del hogar en FCI T+0 antes de inmovilizar en letras.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
