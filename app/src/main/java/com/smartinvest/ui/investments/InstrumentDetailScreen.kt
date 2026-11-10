package com.smartinvest.ui.investments

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartinvest.data.network.MarketKeyParser
import com.smartinvest.data.network.MarketPresets
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.InstrumentQuote
import com.smartinvest.domain.model.MarketSource
import com.smartinvest.domain.model.QuoteUnit
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.ui.components.ChipRow
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.MetricPill
import com.smartinvest.ui.components.PortfolioChart
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToLong

data class TrackedInstrumentOption(
    val marketKey: String,
    val label: String,
    val symbol: String,
    val name: String,
    val assetType: AssetType,
    val currency: CurrencyCode,
    val quoteUnit: QuoteUnit,
    val source: MarketSource,
    val holdingId: Long? = null,
    val quantity: Double = 0.0,
    val currentValueCents: Long = 0L,
)

data class InstrumentDetailUiState(
    val options: List<TrackedInstrumentOption> = emptyList(),
    val selected: TrackedInstrumentOption? = null,
    val quotes: List<InstrumentQuote> = emptyList(),
    val isWorking: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class InstrumentDetailViewModel @Inject constructor(
    private val investments: InvestmentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialHoldingId: Long =
        savedStateHandle.get<String>("holdingId")?.toLongOrNull() ?: 0L

    private val selectedKey = MutableStateFlow<String?>(null)
    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val autoSyncedKeys = mutableSetOf<String>()

    val uiState = combine(
        investments.observeHoldings(),
        investments.observeAllQuotes(),
        selectedKey,
        working,
        message,
    ) { holdings, allQuotes, currentKey, isWorking, msg ->
        val options = buildOptions(holdings)
        val initialOption = if (currentKey != null) {
            options.firstOrNull { it.marketKey == currentKey }
        } else if (initialHoldingId > 0L) {
            options.firstOrNull { it.holdingId == initialHoldingId }
        } else {
            null
        } ?: options.firstOrNull()

        val activeQuotes = if (initialOption != null) {
            allQuotes.filter { it.marketKey == initialOption.marketKey }.sortedBy { it.date }
        } else {
            emptyList()
        }

        InstrumentDetailUiState(
            options = options,
            selected = initialOption,
            quotes = activeQuotes,
            isWorking = isWorking,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InstrumentDetailUiState())

    fun selectInstrument(option: TrackedInstrumentOption) {
        selectedKey.value = option.marketKey
    }

    fun ensureQuotesLoaded(option: TrackedInstrumentOption, currentQuotesCount: Int) {
        if (currentQuotesCount > 0 || working.value) return
        if (!autoSyncedKeys.add(option.marketKey)) return
        syncOption(option)
    }

    fun syncSelected() {
        val current = uiState.value.selected ?: return
        syncOption(current)
    }

    private fun syncOption(option: TrackedInstrumentOption) {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = null
            val res = investments.syncInstrument(
                marketKey = option.marketKey,
                symbol = option.symbol,
                currency = option.currency,
                quoteUnit = option.quoteUnit,
            )
            message.value = res.fold(
                onSuccess = { count -> "Actualizado ${option.symbol}: $count cotizaciones desde ${option.source.label}." },
                onFailure = { err -> err.message ?: "No se pudo consultar ${option.symbol} en ${option.source.label}." },
            )
            working.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun buildOptions(holdings: List<Holding>): List<TrackedInstrumentOption> {
        val result = linkedMapOf<String, TrackedInstrumentOption>()

        holdings.filter { it.marketKey.isNotBlank() }.forEach { h ->
            val key = MarketKeyParser.normalize(h.marketKey)
            result[key] = TrackedInstrumentOption(
                marketKey = key,
                label = "${h.symbol} (En cartera)",
                symbol = h.symbol,
                name = h.name.ifBlank { h.symbol },
                assetType = h.assetType,
                currency = h.currency,
                quoteUnit = h.quoteUnit,
                source = MarketKeyParser.sourceFor(key),
                holdingId = h.id,
                quantity = h.quantity,
                currentValueCents = h.currentValueCents,
            )
        }

        MarketPresets.all.forEach { preset ->
            val existing = result[preset.marketKey]
            if (existing == null) {
                result[preset.marketKey] = TrackedInstrumentOption(
                    marketKey = preset.marketKey,
                    label = preset.label,
                    symbol = preset.symbol,
                    name = preset.name,
                    assetType = preset.assetType,
                    currency = preset.currency,
                    quoteUnit = preset.quoteUnit,
                    source = MarketKeyParser.sourceFor(preset.marketKey),
                )
            }
        }

        return result.values.toList()
    }
}

@Composable
fun InstrumentDetailScreen(
    onEditHolding: (Long) -> Unit,
    onAddHolding: () -> Unit,
    viewModel: InstrumentDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selected = state.selected
    val quotes = state.quotes

    LaunchedEffect(selected?.marketKey, quotes.size) {
        if (selected != null && quotes.isEmpty()) {
            viewModel.ensureQuotesLoaded(selected, quotes.size)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Evolución de instrumentos", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Cotizaciones históricas de Rava (LECAP, bonos, CEDEAR) y CAFCI (FCI)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::syncSelected, enabled = !state.isWorking && selected != null) {
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

        if (state.options.isNotEmpty() && selected != null) {
            item {
                SmartCard {
                    SectionHeader(
                        title = "Instrumento o FCI",
                        subtitle = "Tocá para ver la curva de precios o cuotapartes",
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipRow(
                        options = state.options,
                        selected = selected,
                        label = { it.label },
                        onSelect = viewModel::selectInstrument,
                    )
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

        if (selected != null) {
            item {
                InstrumentSummaryCard(
                    option = selected,
                    quotes = quotes,
                    onEditHolding = onEditHolding,
                    onAddHolding = onAddHolding,
                )
            }

            item {
                SmartCard {
                    SectionHeader(
                        title = "Curva de cotización",
                        subtitle = if (quotes.size >= 2) {
                            "${Fmt.shortDate(quotes.first().date)} → ${Fmt.shortDate(quotes.last().date)} (${quotes.size} ruedas)"
                        } else {
                            "Tocá el ícono de actualizar para descargar cierres"
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    if (quotes.size >= 2) {
                        val firstPrice = quotes.first().price
                        val lastPrice = quotes.last().price
                        val lineColor = if (lastPrice >= firstPrice) Emerald else Rose
                        PortfolioChart(
                            values = quotes.map { (it.price * 10_000.0).roundToLong() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            lineColor = lineColor,
                        )
                    } else {
                        EmptyState(
                            icon = Icons.AutoMirrored.Rounded.ShowChart,
                            title = if (state.isWorking) "Consultando ${selected.source.label}…" else "Sin historial descargado",
                            message = "Usá el botón de actualizar arriba a la derecha para traer los valores desde ${selected.source.label}.",
                        )
                    }
                }
            }

            if (quotes.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Cierres registrados",
                        subtitle = "${quotes.size} puntos · ${selected.quoteUnit.label}",
                    )
                }
                val reversed = quotes.reversed()
                itemsIndexed(reversed, key = { _, q -> q.date.toEpochDay() }) { index, quote ->
                    val prev = reversed.getOrNull(index + 1)
                    QuoteRow(
                        quote = quote,
                        previous = prev,
                        option = selected,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstrumentSummaryCard(
    option: TrackedInstrumentOption,
    quotes: List<InstrumentQuote>,
    onEditHolding: (Long) -> Unit,
    onAddHolding: () -> Unit,
) {
    val latest = quotes.lastOrNull()
    val prev = if (quotes.size >= 2) quotes[quotes.size - 2] else null
    val first = quotes.firstOrNull()

    val dayPct = if (latest != null && prev != null && prev.price > 0.0) {
        (latest.price - prev.price) * 100.0 / prev.price
    } else {
        null
    }
    val windowPct = if (latest != null && first != null && first.price > 0.0 && quotes.size >= 2) {
        (latest.price - first.price) * 100.0 / first.price
    } else {
        null
    }

    SmartCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(option.symbol, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    TagPill(option.assetType.label, assetBadgeColor(option.assetType))
                    Spacer(Modifier.width(6.dp))
                    TagPill(option.source.label, Sky)
                }
                Text(
                    option.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (option.holdingId != null) {
                IconButton(onClick = { onEditHolding(option.holdingId) }) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Editar tenencia", tint = Emerald)
                }
            } else {
                OutlinedButton(onClick = onAddHolding) {
                    Text("Cargar en cartera")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            latest?.let { formatQuotePrice(it.price, option.currency) } ?: "—",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            latest?.let { "Último cierre: ${Fmt.longDate(it.date)} · ${option.quoteUnit.label}" }
                ?: "Clave: ${option.marketKey}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricPill(
                text = "Día ${Fmt.signedPercent(dayPct, 2)}",
                color = pctColor(dayPct),
            )
            MetricPill(
                text = "Período ${Fmt.signedPercent(windowPct, 2)}",
                color = pctColor(windowPct),
            )
        }
        if (option.holdingId != null && option.quantity > 0.0) {
            ThinDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "Tenencia",
                    value = String.format(Locale.forLanguageTag("es-AR"), "%.2f", option.quantity),
                    caption = option.quoteUnit.label,
                    accent = Sky,
                    modifier = Modifier.weight(1f),
                )
                val valuedCents = latest?.let {
                    val factor = if (option.quoteUnit == QuoteUnit.PER_100_NOMINAL) 0.01 else 1.0
                    (it.price * factor * option.quantity * 100.0).roundToLong()
                } ?: option.currentValueCents
                StatTile(
                    label = "Valuación actual",
                    value = if (option.currency == CurrencyCode.USD) Fmt.usd(valuedCents) else Fmt.ars(valuedCents),
                    caption = option.currency.label,
                    accent = Emerald,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QuoteRow(
    quote: InstrumentQuote,
    previous: InstrumentQuote?,
    option: TrackedInstrumentOption,
) {
    val pct = if (previous != null && previous.price > 0.0) {
        (quote.price - previous.price) * 100.0 / previous.price
    } else {
        null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(Fmt.longDate(quote.date), style = MaterialTheme.typography.titleSmall)
            Text(
                "${quote.source.label} · ${option.currency.symbol}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatQuotePrice(quote.price, option.currency),
                style = MaterialTheme.typography.titleSmall,
            )
            if (pct != null) {
                Text(
                    Fmt.signedPercent(pct, 2),
                    style = MaterialTheme.typography.bodySmall,
                    color = pctColor(pct),
                )
            }
        }
    }
}

private fun formatQuotePrice(price: Double, currency: CurrencyCode): String {
    val prefix = if (currency == CurrencyCode.USD) "US$" else "$"
    val decimals = if (price < 20.0) 4 else 2
    return "$prefix " + String.format(Locale.forLanguageTag("es-AR"), "%.${decimals}f", price)
}

private fun pctColor(pct: Double?): Color = when {
    pct == null -> Sky
    pct >= 0.0 -> Emerald
    else -> Rose
}

private fun assetBadgeColor(type: AssetType): Color = when (type) {
    AssetType.LECAP, AssetType.BONCAP -> Emerald
    AssetType.BOND, AssetType.DOLAR_MEP -> Sky
    AssetType.FCI -> Violet
    AssetType.CEDEAR, AssetType.CRYPTO -> Amber
    AssetType.OTHER -> Sky
}
