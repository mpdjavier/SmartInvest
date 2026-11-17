@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.smartinvest.ui.investments

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.domain.engine.HoldingReconciliationItem
import com.smartinvest.domain.engine.PortfolioReconciliationReport
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.QuoteUnit
import com.smartinvest.ui.components.AmountInput
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.LinearBar
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.StatTile
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.PlexMono
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.util.Fmt
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun PortfolioReconciliationScreen(
    onEditHolding: (Long) -> Unit,
    onOpenDailyInterest: () -> Unit,
    viewModel: PortfolioReconciliationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val report = state.report
    var showBulkPaste by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ajuste manual y control", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Fijá la cifra exacta de hoy en ARS y USD, compará evolución y verificá cálculos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = viewModel::syncQuotesForComparison,
                    enabled = !state.isWorking,
                ) {
                    if (state.isWorking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Emerald,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Actualizar cotizaciones y comparar",
                            tint = Emerald,
                        )
                    }
                }
            }
        }

        state.message?.let { msg ->
            item {
                Column {
                    InfoBanner(msg, color = Sky)
                    TextButton(onClick = viewModel::clearMessage) { Text("Cerrar") }
                }
            }
        }

        item {
            ExactTotalsCard(
                report = report,
                mepInputText = state.mepInputText,
                onMepInputChange = viewModel::setMepInput,
                onApplyMep = viewModel::applyManualMep,
            )
        }

        item {
            ExposureAndCalculationCheckCard(
                report = report,
                onOpenDailyInterest = onOpenDailyInterest,
                onSyncQuotes = viewModel::syncQuotesForComparison,
                isWorking = state.isWorking,
            )
        }

        item {
            SmartCard {
                SectionHeader(
                    title = "Carga rápida de saldos",
                    subtitle = "Aplicá la lista del 16/11/2026 (saldos + cantidades) o pegá una lista actualizada",
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = viewModel::apply2026_11_16Preset,
                        enabled = !state.isWorking,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                    ) {
                        Text("Cargar lista 16/11/26", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = { showBulkPaste = !showBulkPaste },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                    ) {
                        Text(if (showBulkPaste) "Ocultar texto" else "Pegar lista", maxLines = 1, softWrap = false)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "El botón carga los 9 saldos del 16/11 y las cantidades que faltaban " +
                        "(AO27D, ADCAP-BAL3, MP-ARS y MP-USD) para que CAFCI y Rava actualicen solos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showBulkPaste) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = state.bulkPasteText,
                        onValueChange = viewModel::setBulkPasteText,
                        label = { Text("Formato: TICKER (CUENTA) MONTO ARS/USD") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::applyBulkPasteList,
                        enabled = !state.isWorking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Aplicar saldos pegados")
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "Ajuste manual por activo (${report.items.size})",
                subtitle = "Editá en ARS o USD y compará contra el valor calculado",
                actionLabel = if (state.hasUnsavedChanges) "Guardar todo" else null,
                onAction = if (state.hasUnsavedChanges) viewModel::saveAllDrafts else null,
            )
        }

        if (state.hasUnsavedChanges) {
            item {
                Button(
                    onClick = viewModel::saveAllDrafts,
                    enabled = !state.isWorking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Guardar ajustes manuales")
                }
            }
        }

        items(report.items, key = { it.holding.id }) { item ->
            val draft = state.drafts[item.holding.id] ?: HoldingDraftEdit(
                valueText = "",
                currency = item.holding.currency,
                manualLocked = item.isManualLocked,
            )
            HoldingReconciliationCard(
                item = item,
                draft = draft,
                mepRate = report.mepRate,
                onValueChange = { viewModel.updateDraftValue(item.holding.id, it) },
                onCurrencyChange = { viewModel.updateDraftCurrency(item.holding.id, it) },
                onConvertWithMep = { viewModel.convertDraftCurrencyWithMep(item.holding.id) },
                onToggleLock = { viewModel.toggleDraftManualLock(item.holding.id, it) },
                onUseCalculated = { viewModel.useCalculatedForHolding(item) },
                onOpenFullEditor = { onEditHolding(item.holding.id) },
            )
        }

        if (report.items.isNotEmpty()) {
            item {
                Button(
                    onClick = viewModel::saveAllDrafts,
                    enabled = !state.isWorking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Guardar ajustes manuales y actualizar snapshot")
                }
            }
        }
    }
}

@Composable
private fun ExactTotalsCard(
    report: PortfolioReconciliationReport,
    mepInputText: String,
    onMepInputChange: (String) -> Unit,
    onApplyMep: () -> Unit,
) {
    SmartCard {
        SectionHeader(
            title = "Cifras exactas de hoy (ARS y USD)",
            subtitle = "Totales por moneda nativa y patrimonio unificado al MEP",
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Activos en ARS",
                value = Fmt.ars(report.nativeArsTotalCents),
                caption = "Suma directa en pesos",
                accent = Emerald,
                modifier = Modifier.weight(1f),
                help = "Suma exacta de todos los activos cuya moneda de valuación es ARS.",
            )
            StatTile(
                label = "Activos en USD",
                value = Fmt.usd(report.nativeUsdTotalCents),
                caption = "Suma directa en dólares",
                accent = Sky,
                modifier = Modifier.weight(1f),
                help = "Suma exacta de todos los activos cuya moneda de valuación es USD (ej. AO27D).",
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Total en ARS",
                value = Fmt.ars(report.totalArsCents),
                caption = "Patrimonio unificado",
                accent = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Total en USD",
                value = Fmt.usd(report.totalUsdCents),
                caption = "MEP ${Fmt.ars((report.mepRate * 100.0).roundToLong())}",
                accent = Amber,
                modifier = Modifier.weight(1f),
            )
        }
        ThinDivider()
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = mepInputText,
                onValueChange = onMepInputChange,
                label = { Text("MEP de referencia ($/USD)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onApplyMep) {
                Text("Fijar MEP")
            }
        }
    }
}

@Composable
private fun ExposureAndCalculationCheckCard(
    report: PortfolioReconciliationReport,
    onOpenDailyInterest: () -> Unit,
    onSyncQuotes: () -> Unit,
    isWorking: Boolean,
) {
    val pesoItems = report.items.filterNot { it.isDollarExposure }
    val dollarItems = report.items.filter { it.isDollarExposure }

    SmartCard {
        SectionHeader(
            title = "Exposición 60/40 y control de cálculos",
            subtitle = "SPY + bonos (AO27 y AO27D) + FCI USD computan como parte dolarizada",
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Tramo Pesos",
                value = Fmt.percent(report.pesoExposurePercent),
                caption = Fmt.ars(report.pesoExposureArsCents),
                accent = Emerald,
                modifier = Modifier.weight(1f),
                help = "Incluye FCI en pesos y LECAP/BONCAP (${pesoItems.joinToString { it.displayLabel }}).",
            )
            StatTile(
                label = "Tramo Dolarizado",
                value = Fmt.percent(report.dollarExposurePercent),
                caption = "${Fmt.ars(report.dollarExposureArsCents)} (${Fmt.usd(report.dollarExposureUsdCents)})",
                accent = Sky,
                modifier = Modifier.weight(1f),
                help = "Incluye activos en USD, CEDEARs (SPY) y bonos soberanos tanto en ARS (AO27) como en USD (AO27D).",
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearBar(
            fraction = (report.pesoExposurePercent / 100.0).toFloat(),
            color = Emerald,
            height = 7,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Dolarizados (${dollarItems.size}): ${dollarItems.joinToString(", ") { it.displayLabel }}",
            style = MaterialTheme.typography.bodySmall,
            color = Sky,
        )
        Text(
            "Pesos (${pesoItems.size}): ${pesoItems.joinToString(", ") { it.displayLabel }}",
            style = MaterialTheme.typography.bodySmall,
            color = Emerald,
        )
        ThinDivider()
        KeyValueRow(
            "Total real fijado hoy",
            Fmt.ars(report.totalArsCents),
            strong = true,
        )
        KeyValueRow(
            "Total calculado por cotizaciones",
            Fmt.ars(report.totalCalculatedArsCents),
        )
        KeyValueRow(
            "Diferencia (Real − Calculado)",
            Fmt.signedArs(report.totalDiffArsCents),
            valueColor = when {
                abs(report.totalDiffArsCents) <= 100L -> Emerald
                report.totalDiffArsCents > 0L -> Amber
                else -> Rose
            },
            strong = true,
        )
        if (report.totalTrackedCostArsCents > 0L) {
            ThinDivider()
            KeyValueRow(
                "Costo neto invertido (movimientos)",
                Fmt.ars(report.totalTrackedCostArsCents),
            )
            KeyValueRow(
                "Valor actual de esas posiciones",
                Fmt.ars(report.totalTrackedValueArsCents),
            )
            val gainStr = buildString {
                append(Fmt.signedArs(report.totalUnrealizedGainArsCents))
                report.totalUnrealizedGainPercent?.let { pct ->
                    append(" (")
                    append(Fmt.signedPercent(pct, 2))
                    append(")")
                }
            }
            KeyValueRow(
                "Evolución real acumulada",
                gainStr,
                valueColor = if (report.totalUnrealizedGainArsCents >= 0L) Emerald else Rose,
                strong = true,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onSyncQuotes,
                enabled = !isWorking,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Comparar mercado", maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onOpenDailyInterest,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Icon(Icons.Rounded.Insights, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Interés día a día", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun HoldingReconciliationCard(
    item: HoldingReconciliationItem,
    draft: HoldingDraftEdit,
    mepRate: Double,
    onValueChange: (String) -> Unit,
    onCurrencyChange: (CurrencyCode) -> Unit,
    onConvertWithMep: () -> Unit,
    onToggleLock: (Boolean) -> Unit,
    onUseCalculated: () -> Unit,
    onOpenFullEditor: () -> Unit,
) {
    val parsedDraftCents = MoneyParser.parseToCents(draft.valueText) ?: item.manualNativeCents
    val safeMep = mepRate.takeIf { it > 0.0 } ?: 1_000.0
    val equivText = if (draft.currency == CurrencyCode.ARS) {
        val usdCents = (parsedDraftCents / safeMep).roundToLong()
        "≈ ${Fmt.usd(usdCents)} al MEP"
    } else {
        val arsCents = (parsedDraftCents * safeMep).roundToLong()
        "≈ ${Fmt.ars(arsCents)} al MEP"
    }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            item.displayLabel,
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = PlexMono),
                        )
                        TagPill(item.holding.assetType.label, Amber)
                        TagPill(
                            text = if (item.isDollarExposure) "DOLARIZADO (USD)" else "PESOS (ARS)",
                            color = if (item.isDollarExposure) Sky else Emerald,
                        )
                        TagPill(
                            text = if (draft.manualLocked) "MANUAL FIJO" else "AUTO",
                            color = if (draft.manualLocked) Emerald else Sky,
                        )
                    }
                    Text(
                        item.holding.name.ifBlank { item.holding.assetType.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onOpenFullEditor) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = "Abrir editor completo",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AmountInput(
                    value = draft.valueText,
                    onValueChange = onValueChange,
                    label = "Cifra exacta hoy (${draft.currency.symbol})",
                    prefix = if (draft.currency == CurrencyCode.USD) "US$" else "$",
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = draft.currency == CurrencyCode.ARS,
                    onClick = { onCurrencyChange(CurrencyCode.ARS) },
                    label = { Text("ARS") },
                )
                FilterChip(
                    selected = draft.currency == CurrencyCode.USD,
                    onClick = { onCurrencyChange(CurrencyCode.USD) },
                    label = { Text("USD") },
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    equivText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono),
                    color = Sky,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onConvertWithMep,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Convertir ARS↔USD")
                }
                IconButton(
                    onClick = { onToggleLock(!draft.manualLocked) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (draft.manualLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = "Fijar valor manual",
                        tint = if (draft.manualLocked) Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Calculation & Evolution Diagnostics
            val hasQuantityDiag = item.holding.quantity > 0.0
            val hasCostDiag = item.costNativeCents > 0L && item.costNativeCents != item.manualNativeCents
            if (hasQuantityDiag || hasCostDiag) {
                ThinDivider()
                if (hasQuantityDiag) {
                    val unitLabel = if (item.holding.quoteUnit == QuoteUnit.PER_100_NOMINAL) "VN (c/100)" else "u."
                    KeyValueRow(
                        "Cantidad registrada",
                        "${formatQuantity(item.holding.quantity)} $unitLabel",
                    )
                    item.impliedPrice?.let { implied ->
                        KeyValueRow(
                            "Precio implícito según tu saldo",
                            formatPrice(implied, draft.currency),
                        )
                    }
                    if (item.latestMarketPrice != null && item.calculatedNativeCents != null) {
                        val dateStr = item.latestMarketDate?.let(Fmt::shortDate) ?: "mercado"
                        KeyValueRow(
                            "Último cierre ($dateStr: ${formatPrice(item.latestMarketPrice, item.holding.currency)})",
                            formatNative(item.calculatedNativeCents, item.holding.currency),
                        )
                        val diff = item.diffNativeCents ?: 0L
                        val diffText = buildString {
                            append(formatSignedNative(diff, item.holding.currency))
                            item.diffPercent?.let { pct ->
                                append(" (")
                                append(Fmt.signedPercent(pct, 2))
                                append(")")
                            }
                        }
                        KeyValueRow(
                            "Diferencia (Real − Calculado)",
                            diffText,
                            valueColor = when {
                                abs(diff) <= 100L -> Emerald
                                diff > 0L -> Amber
                                else -> Rose
                            },
                            strong = abs(diff) > 100L,
                        )
                        if (abs(diff) > 0L) {
                            TextButton(
                                onClick = onUseCalculated,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            ) {
                                Text("Usar valor calculado por cotización")
                            }
                        }
                    }
                }
                if (hasCostDiag && item.gainNativeCents != null) {
                    KeyValueRow(
                        "Costo neto invertido",
                        formatNative(item.costNativeCents, item.holding.currency),
                    )
                    val gainStr = buildString {
                        append(formatSignedNative(item.gainNativeCents, item.holding.currency))
                        item.gainPercent?.let { pct ->
                            append(" (")
                            append(Fmt.signedPercent(pct, 2))
                            append(")")
                        }
                    }
                    KeyValueRow(
                        "Evolución / Ganancia real",
                        gainStr,
                        valueColor = if (item.gainNativeCents >= 0L) Emerald else Rose,
                        strong = true,
                    )
                }
            }
        }
    }
}

private fun formatNative(cents: Long, currency: CurrencyCode): String =
    if (currency == CurrencyCode.USD) Fmt.usd(cents) else Fmt.ars(cents)

private fun formatSignedNative(cents: Long, currency: CurrencyCode): String =
    if (currency == CurrencyCode.USD) Fmt.signedUsd(cents) else Fmt.signedArs(cents)

private fun formatQuantity(qty: Double): String =
    if (qty % 1.0 == 0.0) {
        String.format(Locale.forLanguageTag("es-AR"), "%,.0f", qty)
    } else {
        String.format(Locale.forLanguageTag("es-AR"), "%,.2f", qty)
    }

private fun formatPrice(price: Double, currency: CurrencyCode): String {
    val prefix = if (currency == CurrencyCode.USD) "US$" else "$"
    val decimals = if (price < 20.0) 4 else 2
    return "$prefix " + String.format(Locale.forLanguageTag("es-AR"), "%,.${decimals}f", price)
}
