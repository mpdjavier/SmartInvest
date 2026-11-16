package com.smartinvest.ui.household

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.domain.engine.ExpenseReportEngine
import com.smartinvest.domain.model.ExpenseBucket
import com.smartinvest.domain.model.ExpenseMonthBucket
import com.smartinvest.domain.model.ExpensePeriod
import com.smartinvest.domain.model.ExpenseRecord
import com.smartinvest.domain.model.ExpenseReport
import com.smartinvest.ui.components.ChipRow
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.LinearBar
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.StatTile
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.PlexMono
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.util.Fmt
import java.time.YearMonth

/**
 * Expense report for a period: totals, per-category / per-method / per-member splits,
 * monthly evolution and the biggest purchases. Exports a CSV and a shareable summary.
 */
@Composable
fun ExpenseReportScreen(
    onEditExpense: (YearMonth, Long) -> Unit,
    viewModel: ExpenseReportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val csv = viewModel.csv()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(csv.toByteArray(Charsets.UTF_8))
            } ?: error("No se pudo abrir el archivo elegido")
        }.onFailure { error ->
            viewModel.reportExportError(error.message)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ChipRow(
                options = ExpensePeriod.entries.toList(),
                selected = state.period,
                label = { it.label },
                onSelect = viewModel::setPeriod,
            )
        }

        state.message?.let { message ->
            item {
                Column {
                    InfoBanner(message, color = Rose)
                    TextButton(onClick = viewModel::clearMessage) { Text("Cerrar") }
                }
            }
        }

        when {
            state.isLoading -> item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 30.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            state.report.isEmpty -> item {
                EmptyState(
                    icon = Icons.Rounded.Description,
                    title = "Sin gastos para reportar",
                    message = "No hay movimientos en ${state.period.label.lowercase()}. " +
                        "Probá con otro período o cargá gastos en Hogar.",
                )
            }

            else -> {
                item { SummaryCard(state) }
                if (state.report.monthly.size > 1) {
                    item { MonthlyCard(state.report.monthly) }
                }
                item {
                    BucketCard(
                        title = "Por categoría",
                        subtitle = "Dónde se concentra el gasto",
                        buckets = state.report.byCategory,
                        totalCents = state.report.totalCents,
                        color = Emerald,
                    )
                }
                item {
                    BucketCard(
                        title = "Por medio de pago",
                        subtitle = "Efectivo, transferencia, tarjeta, fondo o FCI",
                        buckets = state.report.byPaymentMethod,
                        totalCents = state.report.totalCents,
                        color = Sky,
                    )
                }
                if (state.report.byMember.size > 1) {
                    item {
                        BucketCard(
                            title = "Por integrante",
                            subtitle = "Quién pagó cada gasto",
                            buckets = state.report.byMember,
                            totalCents = state.report.totalCents,
                            color = Amber,
                        )
                    }
                }
                item {
                    TopExpensesCard(
                        records = state.report.topExpenses,
                        onEditExpense = onEditExpense,
                    )
                }
                item {
                    ExportCard(
                        onExportCsv = { csvLauncher.launch(state.fileName) },
                        onShareSummary = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Reporte de gastos · SmartInvest")
                                putExtra(Intent.EXTRA_TEXT, viewModel.summary())
                            }
                            context.startActivity(
                                Intent.createChooser(intent, "Compartir reporte"),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: ExpenseReportUiState) {
    val report = state.report
    SmartCard {
        SectionHeader(
            title = "Resumen del período",
            subtitle = "${state.periodLabel} · ${report.count} movimientos",
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Gasto total",
                value = Fmt.compact(report.totalCents),
                caption = if (report.monthsConsidered > 1) {
                    "${report.monthsConsidered} meses"
                } else {
                    "del período"
                },
                accent = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Promedio / mes",
                value = Fmt.compact(report.averagePerMonthCents),
                caption = "por mes con datos",
                accent = Emerald,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Ticket promedio",
                value = Fmt.compact(report.averageTicketCents),
                caption = "${report.count} movimientos",
                accent = Sky,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Mayor gasto",
                value = report.largest?.let { Fmt.compact(it.impactCents) } ?: "—",
                caption = report.largest?.description,
                accent = Amber,
                modifier = Modifier.weight(1f),
            )
        }
        ThinDivider()
        KeyValueRow("Gastos directos", Fmt.ars(report.directCents))
        KeyValueRow("Cuotas del período", "${Fmt.ars(report.installmentCents)} · ${report.installmentCount}")
    }
}

@Composable
private fun MonthlyCard(months: List<ExpenseMonthBucket>) {
    val ordered = months.sortedBy { it.month }
    val max = ordered.maxOfOrNull { it.totalCents }?.coerceAtLeast(1L) ?: 1L
    SmartCard {
        SectionHeader(
            title = "Evolución mensual",
            subtitle = "Gasto imputado mes a mes",
        )
        Spacer(Modifier.height(10.dp))
        ordered.forEach { bucket ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Fmt.month(bucket.month),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Fmt.ars(bucket.totalCents),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = PlexMono),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearBar(
                fraction = bucket.totalCents.toFloat() / max.toFloat(),
                color = Emerald,
                height = 6,
            )
            Text(
                "${bucket.count} movimientos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(9.dp))
        }
    }
}

@Composable
private fun BucketCard(
    title: String,
    subtitle: String,
    buckets: List<ExpenseBucket>,
    totalCents: Long,
    color: androidx.compose.ui.graphics.Color,
) {
    SmartCard {
        SectionHeader(title = title, subtitle = subtitle)
        Spacer(Modifier.height(10.dp))
        if (buckets.isEmpty()) {
            Text(
                "Sin datos en el período.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            buckets.forEachIndexed { index, bucket ->
                BucketRow(bucket = bucket, totalCents = totalCents, color = color)
                if (index < buckets.lastIndex) Spacer(Modifier.height(9.dp))
            }
        }
    }
}

@Composable
private fun BucketRow(bucket: ExpenseBucket, totalCents: Long, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            bucket.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            Fmt.ars(bucket.totalCents),
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = PlexMono),
            maxLines = 1,
        )
    }
    Spacer(Modifier.height(4.dp))
    LinearBar(
        fraction = (ExpenseReportEngine.sharePercent(bucket.totalCents, totalCents) / 100.0).toFloat(),
        color = color,
        height = 6,
    )
    Text(
        "${bucket.count} mov · ${Fmt.percent(bucket.shareOf(totalCents))} del total",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TopExpensesCard(
    records: List<ExpenseRecord>,
    onEditExpense: (YearMonth, Long) -> Unit,
) {
    SmartCard {
        SectionHeader(
            title = "Mayores gastos",
            subtitle = "Tocá uno para editarlo",
        )
        Spacer(Modifier.height(8.dp))
        if (records.isEmpty()) {
            Text(
                "Sin movimientos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            records.forEach { record ->
                Card(
                    onClick = { onEditExpense(record.month, record.expenseId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                record.description,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append(Fmt.shortDate(record.date))
                                    append(" · ")
                                    append(record.paymentMethod.shortLabel)
                                    record.installmentLabel?.let { append(" · ").append(it) }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            Fmt.ars(record.impactCents),
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = PlexMono),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportCard(
    onExportCsv: () -> Unit,
    onShareSummary: () -> Unit,
) {
    SmartCard {
        SectionHeader(
            title = "Emitir reporte",
            subtitle = "CSV para Excel o resumen para compartir",
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onExportCsv, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Exportar CSV")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onShareSummary, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Compartir resumen")
        }
        Spacer(Modifier.height(10.dp))
        InfoBanner(
            "El CSV incluye fecha, categoría, integrante, medio de pago, tarjeta, cuota, " +
                "monto del mes y notas. Usa punto y coma como separador y coma decimal: " +
                "abre directo en Excel o Google Sheets.",
            color = Sky,
        )
    }
}
