package com.smartinvest.ui.investments

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.data.network.MarketPresets
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.QuoteUnit
import com.smartinvest.ui.components.AmountInput
import com.smartinvest.ui.components.ChipRow
import com.smartinvest.ui.components.DateField
import com.smartinvest.ui.components.DropdownField
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.util.Fmt
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingEditorScreen(
    onBack: () -> Unit,
    viewModel: HoldingEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDatePicker by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes()
                }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                viewModel.importBrokerFileBytes(bytes)
            }
        }
    }

    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onBack()
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (state.maturity ?: java.time.LocalDate.now())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            viewModel.setMaturity(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        showDatePicker = false
                    },
                ) { Text("Aceptar") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.setMaturity(null)
                        showDatePicker = false
                    },
                ) { Text("Sin vencimiento") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (state.isLoading) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InfoBanner(
            "Cargá el activo manualmente o usá un preset con seguimiento real desde Rava / CAFCI. " +
                "Tanto los CEDEARs (SPY) como los bonos soberanos (AO27 y AO27D) cuentan como " +
                "exposición dolarizada en el rebalanceo aunque coticen en pesos.",
            color = Sky,
        )

        SmartCard {
            SectionHeader(
                title = "Presets con datos reales",
                subtitle = "Rava (S30N6, T15E7, AO27, AO27D, SPY) y CAFCI (Adcap III, Mercado Fondo ARS/USD)",
            )
            Spacer(Modifier.height(8.dp))
            ChipRow(
                options = MarketPresets.all,
                selected = MarketPresets.all.firstOrNull { it.marketKey == state.marketKey }
                    ?: MarketPresets.all.first(),
                label = { it.label },
                onSelect = viewModel::applyPreset,
            )
        }

        state.error?.let { error ->
            Column {
                InfoBanner(error, color = Rose)
                TextButton(onClick = viewModel::clearError) { Text("Cerrar") }
            }
        }

        state.infoMessage?.let { info ->
            Column {
                InfoBanner(info, color = Sky)
                TextButton(onClick = viewModel::clearInfo) { Text("Cerrar") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.symbol,
                onValueChange = viewModel::setSymbol,
                label = { Text("Ticker") },
                placeholder = { Text("T30A7 / AO27D") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Nombre") },
                singleLine = true,
                modifier = Modifier.weight(1.4f),
            )
        }

        DropdownField(
            label = "Tipo de activo",
            selected = state.assetType,
            options = AssetType.entries,
            optionLabel = { it.label },
            onSelect = viewModel::setType,
        )

        if (state.assetType == AssetType.FCI) {
            SmartCard {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Usar como FCI billetera", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Queda como cuenta de gastos por defecto y los gastos descuentan su saldo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.isWallet,
                        onCheckedChange = viewModel::setWallet,
                    )
                }
                if (!state.isWallet) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Plazo de rescate",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = listOf(0, 1, 2, 3),
                        selected = state.settlementDays,
                        label = { if (it == 0) "T+0" else "T+$it" },
                        onSelect = viewModel::setSettlementDays,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Adcap Balanceado III rescata a 24 h: corresponde T+1.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Sky,
                    )
                }
            }
        }

        Column {
            Text(
                "Moneda",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            ChipRow(
                options = CurrencyCode.entries,
                selected = state.currency,
                label = { it.label },
                onSelect = viewModel::setCurrency,
            )
        }

        SmartCard {
            SectionHeader(
                title = "Sumar, retirar o importar movimientos",
                subtitle = "Actualiza valuación, costo neto e historial por fecha para calcular intereses día a día",
            )
            if (state.id > 0) {
                Spacer(Modifier.height(8.dp))
                AmountInput(
                    value = state.deltaAmountText,
                    onValueChange = viewModel::setDeltaAmount,
                    label = "Monto a sumar o retirar",
                    prefix = if (state.currency == CurrencyCode.USD) "US$" else "$",
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.applyFundsDelta(add = true) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("+ Sumar aporte")
                    }
                    OutlinedButton(
                        onClick = { viewModel.applyFundsDelta(add = false) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("− Retirar")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { filePickerLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Importar Excel (.xlsx / .xls / .csv) del bróker")
            }
            val presetMovs = com.smartinvest.domain.engine.BrokerMovementImporter.presetMovementsFor(
                state.symbol,
                state.marketKey,
            )
            if (!presetMovs.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = viewModel::applyBrokerPresetMovements,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cargar ${presetMovs.size} mov. exportados (${state.symbol.ifBlank { "Bróker" }})")
                }
            }
        }

        SmartCard {
            SectionHeader(
                title = "Valuación exacta hoy (en ${state.currency.symbol} y conversión)",
                subtitle = "Fijá el monto exacto que disponés hoy para comparar evolución y verificar cálculos",
            )
            Spacer(Modifier.height(8.dp))
            AmountInput(
                value = state.valueText,
                onValueChange = viewModel::setValue,
                label = "Valuación actual exacta (${state.currency.symbol})",
                prefix = if (state.currency == CurrencyCode.USD) "US$" else "$",
            )
            val currentCents = MoneyParser.parseToCents(state.valueText) ?: 0L
            val mep = state.mepRate.takeIf { it > 0.0 } ?: 1_000.0
            if (currentCents > 0L) {
                Spacer(Modifier.height(6.dp))
                val equivText = if (state.currency == CurrencyCode.ARS) {
                    val usdCents = (currentCents / mep).roundToLong()
                    "Equivalente al MEP (${Fmt.ars((mep * 100.0).roundToLong())}): ${Fmt.usd(usdCents)}"
                } else {
                    val arsCents = (currentCents * mep).roundToLong()
                    "Equivalente al MEP (${Fmt.ars((mep * 100.0).roundToLong())}): ${Fmt.ars(arsCents)}"
                }
                Text(
                    equivText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Sky,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fijar cifra exacta manual", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Impide que la sincronización de Rava/CAFCI pise tu saldo real cargado hoy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.manualValuationLocked,
                    onCheckedChange = viewModel::setManualValuationLocked,
                )
            }
            val qty = state.quantityText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
            val quotePrice = state.latestQuotePrice
            if (qty > 0.0 && quotePrice != null && quotePrice > 0.0) {
                val factor = if (state.quoteUnit == QuoteUnit.PER_100_NOMINAL) 0.01 else 1.0
                val calcCents = (quotePrice * factor * qty * 100.0).roundToLong()
                val diffCents = currentCents - calcCents
                Spacer(Modifier.height(8.dp))
                KeyValueRow(
                    "Calculado por cotización (${state.latestQuoteDate?.let(Fmt::shortDate) ?: "mercado"})",
                    if (state.currency == CurrencyCode.USD) Fmt.usd(calcCents) else Fmt.ars(calcCents),
                )
                if (currentCents > 0L) {
                    KeyValueRow(
                        "Diferencia (Real − Calculado)",
                        if (state.currency == CurrencyCode.USD) Fmt.signedUsd(diffCents) else Fmt.signedArs(diffCents),
                        valueColor = if (diffCents == 0L) Emerald else Amber,
                    )
                }
                TextButton(onClick = viewModel::applyCalculatedValuation) {
                    Text("Usar valor calculado por cotización")
                }
            }
            Spacer(Modifier.height(6.dp))
            val altCurrency = if (state.currency == CurrencyCode.ARS) CurrencyCode.USD else CurrencyCode.ARS
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                AmountInput(
                    value = state.altCurrencyValueText,
                    onValueChange = viewModel::setAltCurrencyValue,
                    label = "Cargar desde ${altCurrency.symbol}",
                    prefix = if (altCurrency == CurrencyCode.USD) "US$" else "$",
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = viewModel::convertAltCurrencyToValue) {
                    Text("Convertir")
                }
            }
        }

        SmartCard {
            SectionHeader(
                title = "Seguimiento automático (Rava / CAFCI)",
                subtitle = "Ej: rava:S30N6, rava:AO27D, rava:SPY o cafci:1402:4070 (también acepta URL)",
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.marketKey,
                onValueChange = viewModel::setMarketKey,
                label = { Text("Fuente / clave de mercado") },
                placeholder = { Text("rava:SPY o cafci:1402:4070") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.quantityText,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.all { it.isDigit() || it == ',' || it == '.' }) {
                            viewModel.setQuantity(value)
                        }
                    },
                    label = { Text("Cantidad / cuotapartes") },
                    placeholder = { Text("100") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                DropdownField(
                    label = "Unidad de cotización",
                    selected = state.quoteUnit,
                    options = QuoteUnit.entries,
                    optionLabel = { it.label },
                    onSelect = viewModel::setQuoteUnit,
                    modifier = Modifier.weight(1.3f),
                )
            }
        }

        AmountInput(
            value = state.costText,
            onValueChange = viewModel::setCost,
            label = "Costo de compra (opcional)",
            prefix = if (state.currency == CurrencyCode.USD) "US$" else "$",
        )

        OutlinedTextField(
            value = state.tnaText,
            onValueChange = { value ->
                if (value.isEmpty() || value.all { it.isDigit() || it == ',' || it == '.' }) {
                    viewModel.setTna(value)
                }
            },
            label = { Text("TNA % (opcional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        DateField(
            label = "Vencimiento",
            value = state.maturity?.let(Fmt::longDate) ?: "Sin vencimiento",
            onClick = { showDatePicker = true },
        )

        OutlinedTextField(
            value = state.notes,
            onValueChange = viewModel::setNotes,
            label = { Text("Notas") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        SmartCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
            SectionHeader(title = "Recordatorios automáticos", subtitle = "WorkManager")
            Spacer(Modifier.height(6.dp))
            Text(
                "· Vencimientos de LECAP/BONCAP dentro de la ventana configurada.\n" +
                    "· AO27D por debajo de USD 20.000.\n" +
                    "· Rebalanceo fuera de la banda 60/40 elástica.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = viewModel::save,
            enabled = !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
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
                Text(if (state.id > 0) "Guardar cambios" else "Agregar activo")
            }
        }

        if (state.id > 0) {
            OutlinedButton(
                onClick = viewModel::delete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Eliminar activo", color = Rose)
            }
        }
    }
}
