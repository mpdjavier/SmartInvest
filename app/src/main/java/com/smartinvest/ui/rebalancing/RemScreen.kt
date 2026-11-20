package com.smartinvest.ui.rebalancing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.domain.engine.RemComparisonEngine
import com.smartinvest.domain.model.RemComparison
import com.smartinvest.domain.model.RemConfig
import com.smartinvest.domain.model.RemInstrument
import com.smartinvest.domain.model.RemInstrumentComparison
import com.smartinvest.domain.model.RemInstrumentKind
import com.smartinvest.domain.model.RemMonthPoint
import com.smartinvest.domain.model.RemVerdict
import com.smartinvest.ui.components.ChipRow
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
import com.smartinvest.ui.util.Fmt
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs

@Composable
fun RemScreen(viewModel: RemViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<RemInstrument?>(null) }
    var adding by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) viewModel.importRemXlsx(bytes)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("Comparador REM", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Instrumentos de corto plazo contra un crawling peg ficticio. " +
                        "Horizonte máximo: abril de 2027 por el calendario electoral.",
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
            RemSnapshotCard(
                comparison = state.comparison,
                config = state.config,
                isWorking = state.isWorking,
                onImport = { importLauncher.launch("*/*") },
                onReset = viewModel::resetSnapshot,
                onHorizonSelected = viewModel::setHorizon,
            )
        }

        item {
            RemBenchmarkCard(
                comparison = state.comparison,
                onPegChange = viewModel::setCrawlingPeg,
            )
        }

        item {
            RemRankingCard(
                comparison = state.comparison,
                onEdit = { editing = it },
            )
        }

        item {
            OutlinedButton(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Agregar instrumento")
            }
        }

        item {
            TextButton(onClick = viewModel::resetInstruments) {
                Text("Restaurar instrumentos de referencia")
            }
        }

        item {
            Text(
                "El comparador usa las medianas del REM para inflación y tipo de cambio, y " +
                    "supuestos de TIR editables para cada instrumento. No ejecuta órdenes ni " +
                    "constituye asesoramiento financiero.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val current = editing
    if (current != null) {
        RemInstrumentDialog(
            instrument = current,
            onDismiss = { editing = null },
            onSave = {
                viewModel.saveInstrument(it)
                editing = null
            },
            onDelete = {
                viewModel.deleteInstrument(it.id)
                editing = null
            },
        )
    }

    if (adding) {
        RemInstrumentDialog(
            instrument = null,
            onDismiss = { adding = false },
            onSave = {
                viewModel.saveInstrument(it)
                adding = false
            },
        )
    }
}

@Composable
private fun RemSnapshotCard(
    comparison: RemComparison?,
    config: RemConfig,
    isWorking: Boolean,
    onImport: () -> Unit,
    onReset: () -> Unit,
    onHorizonSelected: (YearMonth) -> Unit,
) {
    val snapshot = comparison?.snapshot ?: config.snapshot
    val visiblePoints = snapshot.points
        .filter { point ->
            point.yearMonth?.let { !it.isAfter(RemComparisonEngine.ELECTION_HORIZON) } == true
        }
        .sortedBy { it.month }
    val availableMonths = visiblePoints.mapNotNull { it.yearMonth }.distinct()

    SmartCard {
        SectionHeader(
            title = "Expectativas REM",
            subtitle = snapshot.reference.ifBlank { "REM · BCRA" },
        )
        Spacer(Modifier.height(10.dp))

        if (visiblePoints.isEmpty()) {
            InfoBanner(
                "No hay REM cargado. Importá el XLSX oficial del BCRA para proyectar inflación y tipo de cambio.",
                color = Amber,
            )
        } else {
            Text(
                buildString {
                    append("Publicado ")
                    append(snapshot.publishedDate?.let(Fmt::longDate) ?: "sin fecha")
                    append(" · horizonte acotado a abr-2027")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            if (availableMonths.isNotEmpty()) {
                Text(
                    "HORIZONTE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = availableMonths,
                    selected = comparison?.horizon ?: config.horizon,
                    label = { shortMonth(it) },
                    onSelect = onHorizonSelected,
                )
                Spacer(Modifier.height(12.dp))
            }

            RemPathHeader()
            visiblePoints.forEach { point -> RemPathRow(point) }
            Spacer(Modifier.height(6.dp))
            Text(
                "IPC = mediana mensual. FX = tipo de cambio nominal esperado ($/USD, promedio " +
                    "mensual). TAMAR = tasa nominal anual esperada.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport, enabled = !isWorking) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Emerald,
                    )
                } else {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null)
                }
                Spacer(Modifier.width(6.dp))
                Text("Importar XLSX del BCRA")
            }
            TextButton(onClick = onReset, enabled = !isWorking) {
                Text("Restaurar")
            }
        }
        Text(
            "Descargá las tablas del REM desde bcra.gob.ar y elegí el archivo .xlsx. " +
                "La app toma las medianas mensuales de IPC general, tipo de cambio y TAMAR.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemPathHeader() {
    Row(Modifier.fillMaxWidth()) {
        Text(
            "MES",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "IPC",
            modifier = Modifier.width(58.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "FX",
            modifier = Modifier.width(86.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "TAMAR",
            modifier = Modifier.width(54.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemPathRow(point: RemMonthPoint) {
    val month = point.yearMonth ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            shortMonth(month),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            Fmt.percent(point.inflationMonthlyPercent, 2),
            modifier = Modifier.width(58.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (point.officialFx > 0.0) formatFx(point.officialFx) else "—",
            modifier = Modifier.width(86.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (point.tamarTnaPercent > 0.0) Fmt.percent(point.tamarTnaPercent, 2) else "—",
            modifier = Modifier.width(54.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RemBenchmarkCard(
    comparison: RemComparison?,
    onPegChange: (Double) -> Unit,
) {
    val currentPeg = comparison?.crawlingPegMonthlyPercent ?: 1.0
    var pegText by remember(currentPeg) { mutableStateOf(formatNumber(currentPeg)) }

    SmartCard {
        SectionHeader(
            title = "Crawling peg ficticio",
            subtitle = "Benchmark: el oficial sube un X% fijo por mes",
        )
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = pegText,
                onValueChange = { pegText = it },
                label = { Text("% mensual") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { parseDecimal(pegText)?.let(onPegChange) },
            ) {
                Text("Aplicar")
            }
        }
        Spacer(Modifier.height(8.dp))
        ChipRow(
            options = listOf(0.5, 1.0, 1.5, 2.0),
            selected = currentPeg,
            label = { Fmt.percent(it, 1) },
            onSelect = onPegChange,
        )

        Spacer(Modifier.height(12.dp))
        comparison?.let { data ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "Peg al horizonte",
                    value = Fmt.percent(data.pegReturnPercent, 2),
                    caption = "${formatNumber(data.months)} meses",
                    accent = Emerald,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Devaluación REM",
                    value = Fmt.percent(data.remDepreciationPercent, 2),
                    caption = "${Fmt.percent(data.remMonthlyDepreciationPercent, 2)} mensual",
                    accent = Sky,
                    modifier = Modifier.weight(1f),
                )
            }
            ThinDivider()
            KeyValueRow(
                "Inflación REM acumulada",
                Fmt.percent(data.remInflationPercent, 2),
            )
            KeyValueRow(
                "Crawling peg al horizonte",
                Fmt.percent(data.pegReturnPercent, 2),
            )
            if (data.hasFx) {
                KeyValueRow(
                    "FX REM hoy → horizonte",
                    "${formatFx(data.remFxToday)} → ${formatFx(data.remFxAtHorizon)}",
                )
            }
            Spacer(Modifier.height(8.dp))
            InfoBanner(data.recommendation, color = Emerald)
            data.warnings.forEach { warning ->
                Spacer(Modifier.height(6.dp))
                InfoBanner(warning, color = Amber)
            }
        }
    }
}

@Composable
private fun RemRankingCard(
    comparison: RemComparison?,
    onEdit: (RemInstrument) -> Unit,
) {
    SmartCard {
        SectionHeader(
            title = "Ranking de instrumentos",
            subtitle = "Ordenados por exceso de retorno sobre el crawling peg",
        )
        Spacer(Modifier.height(6.dp))

        val rows = comparison?.rows.orEmpty()
        if (rows.isEmpty()) {
            Text(
                "Sin instrumentos activos. Agregá una letra, un bono o un dollar linked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            return@SmartCard
        }

        rows.forEachIndexed { index, row ->
            if (index > 0) ThinDivider()
            RemInstrumentRow(
                position = index + 1,
                row = row,
                peg = comparison?.crawlingPegMonthlyPercent ?: 1.0,
                onClick = { onEdit(row.instrument) },
            )
        }
    }
}

@Composable
private fun RemInstrumentRow(
    position: Int,
    row: RemInstrumentComparison,
    peg: Double,
    onClick: () -> Unit,
) {
    val verdictColor = verdictColor(row.verdict)
    val vsPegColor = if (row.vsCrawlingPegPp >= 0.0) Emerald else Rose
    val usdColor = if (row.expectedUsdReturnPercent >= 0.0) Emerald else Rose

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#$position",
                modifier = Modifier.width(26.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.instrument.symbol,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.width(6.dp))
                    TagPill(row.kind.label, Sky)
                }
                Text(
                    row.instrument.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Fmt.percent(row.expectedArsReturnPercent, 2),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (row.expectedArsReturnPercent >= 0.0) Emerald else Rose,
                )
                Text(
                    "${Fmt.percent(row.expectedMonthlyReturnPercent, 2)} mensual",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TagPill("${row.verdict.emoji} ${row.verdict.label}", verdictColor)
            TagPill("${signedPp(row.vsCrawlingPegPp)} vs ${Fmt.percent(peg, 1)}", vsPegColor)
            TagPill("${Fmt.signedPercent(row.expectedUsdReturnPercent, 2)} USD", usdColor)
        }

        Spacer(Modifier.height(5.dp))
        Text(
            row.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.fxToday > 0.0 && row.breakEvenFx > 0.0) {
            Spacer(Modifier.height(8.dp))
            SmartCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
                val endDateLabel = Fmt.shortDate(row.effectiveDate)
                val beatsRem = row.breakEvenVsRemFxDiff >= 0.0
                val beatsPeg = row.breakEvenVsPegFxDiff >= 0.0
                val remDiffPp = row.breakEvenDevaluationPercent - row.remDevaluationPercent
                val pegDiffPp = row.breakEvenDevaluationPercent - row.pegDevaluationPercent

                Text(
                    "DÓLAR AL VENCIMIENTO ($endDateLabel) · BASE HOY ${formatFx(row.fxToday)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                KeyValueRow(
                    label = "Dólar techo / equilibrio (${row.instrument.symbol})",
                    value = "${formatFx(row.breakEvenFx)} (+${Fmt.percent(row.breakEvenDevaluationPercent, 2)} · ${Fmt.percent(row.breakEvenMonthlyDevaluationPercent, 2)}/mes)",
                    valueColor = Emerald,
                    strong = true,
                )
                KeyValueRow(
                    label = "Dólar REM al $endDateLabel",
                    value = "${formatFx(row.remFxAtEffectiveDate)} (+${Fmt.percent(row.remDevaluationPercent, 2)} · ${Fmt.percent(row.remMonthlyDevaluationPercent, 2)}/mes)",
                    valueColor = Sky,
                )
                KeyValueRow(
                    label = "Margen vs REM",
                    value = "${formatSignedFx(row.breakEvenVsRemFxDiff)} (${Fmt.signedPercent(row.expectedUsdReturnPercent, 2)} USD · ${signedPp(remDiffPp)})",
                    valueColor = if (beatsRem) Emerald else Rose,
                )
                KeyValueRow(
                    label = "Dólar Peg ${Fmt.percent(peg, 1)}/mes al $endDateLabel",
                    value = "${formatFx(row.pegFxAtEffectiveDate)} (+${Fmt.percent(row.pegDevaluationPercent, 2)} · ${Fmt.percent(peg, 2)}/mes)",
                    valueColor = Sky,
                )
                KeyValueRow(
                    label = "Margen vs Peg ${Fmt.percent(peg, 1)}",
                    value = "${formatSignedFx(row.breakEvenVsPegFxDiff)} (${Fmt.signedPercent(row.vsCrawlingPegPp, 2)} · ${signedPp(pegDiffPp)})",
                    valueColor = if (beatsPeg) Emerald else Rose,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("Conviene ${row.instrument.symbol} en pesos si al $endDateLabel el dólar vale menos de ")
                        append("${formatFx(row.breakEvenFx)} (suba < ${Fmt.percent(row.breakEvenDevaluationPercent, 2)}). ")
                        append(
                            if (beatsRem) {
                                "Supera al REM por ${formatFx(abs(row.breakEvenVsRemFxDiff))} (${Fmt.signedPercent(row.expectedUsdReturnPercent, 2)})."
                            } else {
                                "Queda ${formatFx(abs(row.breakEvenVsRemFxDiff))} debajo del REM (${Fmt.signedPercent(row.expectedUsdReturnPercent, 2)})."
                            },
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Vence ${Fmt.shortDate(row.effectiveDate)} · break-even " +
                "${Fmt.percent(row.breakEvenMonthlyDevaluationPercent, 2)} mensual · " +
                "retorno real ${Fmt.signedPercent(row.realReturnPercent, 2)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemInstrumentDialog(
    instrument: RemInstrument?,
    onDismiss: () -> Unit,
    onSave: (RemInstrument) -> Unit,
    onDelete: ((RemInstrument) -> Unit)? = null,
) {
    val isNew = instrument == null
    var symbol by remember(instrument?.id) { mutableStateOf(instrument?.symbol.orEmpty()) }
    var name by remember(instrument?.id) { mutableStateOf(instrument?.name.orEmpty()) }
    var kindName by remember(instrument?.id) {
        mutableStateOf(instrument?.kind ?: RemInstrumentKind.PESO_FIXED.name)
    }
    var yieldText by remember(instrument?.id) {
        mutableStateOf(instrument?.annualYieldPercent?.let(::formatNumber).orEmpty())
    }
    var maturity by remember(instrument?.id) { mutableStateOf(instrument?.maturity.orEmpty()) }
    var yieldIsEffective by remember(instrument?.id) {
        mutableStateOf(instrument?.yieldIsEffective ?: true)
    }
    var error by remember(instrument?.id) { mutableStateOf<String?>(null) }

    val selectedKind = runCatching { RemInstrumentKind.valueOf(kindName) }
        .getOrDefault(RemInstrumentKind.PESO_FIXED)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Nuevo instrumento" else "Editar ${instrument?.symbol}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isNew) {
                    OutlinedTextField(
                        value = symbol,
                        onValueChange = { symbol = it.uppercase() },
                        label = { Text("Símbolo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nombre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(
                    "TIPO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = RemInstrumentKind.values().toList(),
                    selected = selectedKind,
                    label = { it.label },
                    onSelect = { kindName = it.name },
                )

                OutlinedTextField(
                    value = yieldText,
                    onValueChange = { yieldText = it },
                    label = { Text(yieldLabel(selectedKind, yieldIsEffective)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (selectedKind == RemInstrumentKind.PESO_FIXED) {
                    ChipRow(
                        options = listOf(true, false),
                        selected = yieldIsEffective,
                        label = { if (it) "TIR efectiva" else "TNA nominal" },
                        onSelect = { yieldIsEffective = it },
                    )
                }

                OutlinedTextField(
                    value = maturity,
                    onValueChange = { maturity = it },
                    label = { Text("Vencimiento (yyyy-MM-dd, opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                instrument?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Rose,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val yieldValue = parseDecimal(yieldText)
                    if (yieldValue == null) {
                        error = "Ingresá un rendimiento válido."
                        return@TextButton
                    }
                    if (maturity.isNotBlank() && runCatching { LocalDate.parse(maturity) }.isFailure) {
                        error = "El vencimiento debe tener el formato yyyy-MM-dd."
                        return@TextButton
                    }
                    if (isNew && symbol.isBlank()) {
                        error = "El símbolo no puede quedar vacío."
                        return@TextButton
                    }
                    onSave(
                        RemInstrument(
                            id = instrument?.id.orEmpty(),
                            symbol = if (isNew) symbol else instrument.symbol,
                            name = if (isNew) name else instrument.name,
                            kind = selectedKind.name,
                            maturity = maturity.trim(),
                            annualYieldPercent = yieldValue,
                            yieldIsEffective = when (selectedKind) {
                                RemInstrumentKind.PESO_TNA -> false
                                RemInstrumentKind.PESO_FIXED -> yieldIsEffective
                                else -> true
                            },
                            notes = instrument?.notes.orEmpty(),
                            isActive = true,
                        ),
                    )
                },
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null && instrument != null) {
                    TextButton(onClick = { onDelete(instrument) }) {
                        Text("Eliminar", color = Rose)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

private fun yieldLabel(kind: RemInstrumentKind, effective: Boolean): String = when (kind) {
    RemInstrumentKind.PESO_FIXED -> if (effective) "TIR anual (%)" else "TNA anual (%)"
    RemInstrumentKind.PESO_TNA -> "TNA anual (%)"
    RemInstrumentKind.CER -> "TIR real anual (%)"
    RemInstrumentKind.DOLLAR_LINKED -> "Spread / TIR anual (%)"
    RemInstrumentKind.USD_BOND -> "TIR en USD anual (%)"
}

private fun verdictColor(verdict: RemVerdict) = when (verdict) {
    RemVerdict.CONVIENE -> Emerald
    RemVerdict.EMPATA -> Amber
    RemVerdict.NO_CONVIENE -> Rose
}

private val SHORT_MONTHS = listOf(
    "Ene", "Feb", "Mar", "Abr", "May", "Jun",
    "Jul", "Ago", "Sep", "Oct", "Nov", "Dic",
)

private fun shortMonth(month: YearMonth): String =
    "${SHORT_MONTHS[month.monthValue - 1]} ${month.year % 100}"

private fun formatFx(value: Double): String =
    String.format(Locale.forLanguageTag("es-AR"), "\$ %,.2f", value)

private fun formatSignedFx(value: Double): String {
    val sign = if (value >= 0.0) "+" else "−"
    return "$sign${formatFx(abs(value))}"
}

private fun formatNumber(value: Double): String =
    String.format(Locale.forLanguageTag("es-AR"), "%.2f", value)

private fun parseDecimal(raw: String): Double? {
    val clean = raw.trim().replace("%", "").replace(" ", "")
    if (clean.isEmpty()) return null
    val normalized = when {
        clean.contains(',') && clean.contains('.') -> clean.replace(".", "").replace(',', '.')
        clean.contains(',') -> clean.replace(',', '.')
        else -> clean
    }
    return normalized.toDoubleOrNull()
}

private fun signedPp(value: Double): String {
    val sign = if (value >= 0.0) "+" else "−"
    return sign + String.format(Locale.forLanguageTag("es-AR"), "%.2f pp", abs(value))
}
