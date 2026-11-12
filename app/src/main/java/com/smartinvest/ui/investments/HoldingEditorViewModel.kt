package com.smartinvest.ui.investments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.data.network.MarketKeyParser
import com.smartinvest.data.network.MarketPreset
import com.smartinvest.domain.engine.BrokerImportSummary
import com.smartinvest.domain.engine.BrokerMovementImporter
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.QuoteUnit
import com.smartinvest.domain.repository.InvestmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class HoldingForm(
    val id: Long = 0,
    val symbol: String = "",
    val name: String = "",
    val assetType: AssetType = AssetType.LECAP,
    val currency: CurrencyCode = CurrencyCode.ARS,
    val valueText: String = "",
    val costText: String = "",
    val deltaAmountText: String = "",
    val tnaText: String = "",
    val maturity: LocalDate? = null,
    val notes: String = "",
    val marketKey: String = "",
    val quantityText: String = "",
    val quoteUnit: QuoteUnit = QuoteUnit.PER_UNIT,
    val isWallet: Boolean = false,
    val settlementDays: Int = 1,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val infoMessage: String? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false,
)

@HiltViewModel
class HoldingEditorViewModel @Inject constructor(
    private val investments: InvestmentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val holdingId: Long = savedStateHandle.get<String>("holdingId")?.toLongOrNull() ?: 0L
    private val form = MutableStateFlow(HoldingForm(id = holdingId))

    val uiState = form.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HoldingForm(id = holdingId),
    )

    init {
        if (holdingId > 0) {
            viewModelScope.launch {
                val holding = investments.observeHoldings().first().firstOrNull { it.id == holdingId }
                if (holding == null) {
                    form.update { it.copy(isLoading = false, error = "El activo ya no existe") }
                } else {
                    form.update { fromHolding(holding) }
                }
            }
        } else {
            form.update { it.copy(isLoading = false) }
        }
    }

    fun setSymbol(value: String) = form.update { it.copy(symbol = value.uppercase(), error = null) }
    fun setName(value: String) = form.update { it.copy(name = value, error = null) }
    fun setType(value: AssetType) = form.update {
        it.copy(
            assetType = value,
            isWallet = if (value == AssetType.FCI) it.isWallet else false,
            settlementDays = if (value == AssetType.FCI) it.settlementDays else 1,
            error = null,
        )
    }
    fun setCurrency(value: CurrencyCode) = form.update { it.copy(currency = value, error = null) }
    fun setValue(value: String) = form.update { it.copy(valueText = value, error = null) }
    fun setCost(value: String) = form.update { it.copy(costText = value, error = null) }
    fun setDeltaAmount(value: String) = form.update { it.copy(deltaAmountText = value, error = null) }
    fun setTna(value: String) = form.update { it.copy(tnaText = value, error = null) }

    fun applyFundsDelta(add: Boolean) {
        val current = form.value
        val deltaCents = MoneyParser.parseToCents(current.deltaAmountText)
        if (deltaCents == null || deltaCents <= 0L) {
            form.update { it.copy(error = "Ingresá un monto válido para sumar o retirar") }
            return
        }
        val oldValueCents = MoneyParser.parseToCents(current.valueText) ?: 0L
        val newValueCents = if (add) {
            oldValueCents + deltaCents
        } else {
            if (deltaCents > oldValueCents) {
                form.update { it.copy(error = "El retiro supera la valuación actual del activo") }
                return
            }
            oldValueCents - deltaCents
        }

        val oldCostCents = MoneyParser.parseToCents(current.costText) ?: 0L
        val newCostCents = when {
            oldCostCents <= 0L -> 0L
            add -> oldCostCents + deltaCents
            oldValueCents > 0L -> (oldCostCents.toDouble() * newValueCents.toDouble() / oldValueCents.toDouble()).toLong()
            else -> 0L
        }

        val oldQty = current.quantityText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        val newQtyText = if (oldQty > 0.0 && oldValueCents > 0L) {
            val scaledQty = oldQty * (newValueCents.toDouble() / oldValueCents.toDouble())
            String.format(java.util.Locale.US, "%.4f", scaledQty)
                .trimEnd('0')
                .trimEnd('.')
                .replace('.', ',')
        } else {
            current.quantityText
        }

        val sign = if (add) "+" else "-"
        val dateLabel = com.smartinvest.core.time.DateUtils.today()
            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        val formattedDelta = if (current.currency == CurrencyCode.USD) {
            com.smartinvest.core.money.MoneyFormatter.usd(deltaCents)
        } else {
            com.smartinvest.core.money.MoneyFormatter.ars(deltaCents)
        }
        val logEntry = "$dateLabel: $sign$formattedDelta"
        val updatedNotes = if (current.notes.isBlank()) logEntry else "${current.notes.trim()}\n$logEntry"

        form.update {
            it.copy(
                valueText = centsInput(newValueCents),
                costText = if (newCostCents > 0L) centsInput(newCostCents) else it.costText,
                quantityText = newQtyText,
                deltaAmountText = "",
                notes = updatedNotes,
                error = null,
            )
        }
    }
    fun setMaturity(value: LocalDate?) = form.update { it.copy(maturity = value, error = null) }
    fun setNotes(value: String) = form.update { it.copy(notes = value) }
    fun setMarketKey(value: String) = form.update { it.copy(marketKey = value, error = null) }
    fun setQuantity(value: String) = form.update { it.copy(quantityText = value, error = null) }
    fun setQuoteUnit(value: QuoteUnit) = form.update { it.copy(quoteUnit = value, error = null) }
    fun setWallet(value: Boolean) = form.update {
        it.copy(
            isWallet = value,
            settlementDays = if (value) 0 else it.settlementDays,
            error = null,
        )
    }
    fun setSettlementDays(value: Int) = form.update {
        it.copy(settlementDays = value.coerceIn(0, 10), error = null)
    }
    fun clearError() = form.update { it.copy(error = null) }
    fun clearInfo() = form.update { it.copy(infoMessage = null) }

    fun importBrokerFileBytes(bytes: ByteArray) {
        val current = form.value
        val filter = current.marketKey.ifBlank { current.symbol }
        val summary = BrokerMovementImporter.parseBrokerFileBytes(bytes, filter)
            .let { parsed ->
                if (parsed.movements.isEmpty() && filter.isNotBlank()) {
                    BrokerMovementImporter.parseBrokerFileBytes(bytes, "")
                } else {
                    parsed
                }
            }
        if (summary.movements.isEmpty()) {
            form.update {
                it.copy(error = "No se encontraron suscripciones, rescates o compras para este activo en el archivo")
            }
            return
        }
        applyImportedSummary(summary)
    }

    fun applyBrokerPresetMovements() {
        val current = form.value
        val movs = BrokerMovementImporter.presetMovementsFor(current.symbol, current.marketKey)
        if (movs.isNullOrEmpty()) {
            form.update { it.copy(error = "No hay movimientos predefinidos para este activo") }
            return
        }
        applyImportedSummary(BrokerMovementImporter.summarize(movs))
    }

    private fun applyImportedSummary(summary: BrokerImportSummary) {
        val current = form.value
        val fmt = { cents: Long ->
            if (current.currency == CurrencyCode.USD) MoneyFormatter.usd(cents) else MoneyFormatter.ars(cents)
        }
        val notesBlock = summary.toNotesBlock(current.currency)
        val nonMovementNotes = current.notes.lineSequence()
            .filter { line -> BrokerMovementImporter.parseMovementNotes(line).isEmpty() }
            .joinToString("\n")
            .trim()
        val combinedNotes = if (nonMovementNotes.isBlank()) notesBlock else "$nonMovementNotes\n$notesBlock"

        val currentValCents = MoneyParser.parseToCents(current.valueText) ?: 0L
        val newValueText = if (currentValCents > 0L) {
            current.valueText
        } else {
            centsInput(summary.netInvestedCents.coerceAtLeast(0L))
        }

        val newQtyText = if (summary.netQuantity > 0.0) {
            if (summary.netQuantity % 1.0 == 0.0) {
                String.format(java.util.Locale.US, "%.0f", summary.netQuantity)
            } else {
                String.format(java.util.Locale.US, "%.4f", summary.netQuantity)
                    .trimEnd('0')
                    .trimEnd('.')
                    .replace('.', ',')
            }
        } else {
            current.quantityText
        }

        val qtyNote = if (summary.netQuantity > 0.0) " · Cantidad: $newQtyText" else ""
        val info = "Importados ${summary.movements.size} movimientos$qtyNote · " +
            "Comprado/Suscripto: ${fmt(summary.totalSubscribedCents)} · " +
            "Rescatado/Vendido: ${fmt(summary.totalRedeemedCents)} · " +
            "Costo neto: ${fmt(summary.netInvestedCents)}"

        form.update {
            it.copy(
                valueText = newValueText,
                costText = if (summary.netInvestedCents > 0L) centsInput(summary.netInvestedCents) else it.costText,
                quantityText = newQtyText,
                notes = combinedNotes,
                infoMessage = info,
                error = null,
            )
        }
    }

    fun applyPreset(preset: MarketPreset) {
        form.update {
            it.copy(
                symbol = preset.symbol,
                name = preset.name,
                assetType = preset.assetType,
                currency = preset.currency,
                quoteUnit = preset.quoteUnit,
                marketKey = preset.marketKey,
                settlementDays = preset.settlementDays,
                isWallet = false,
                error = null,
            )
        }
    }

    fun save() {
        val current = form.value
        if (current.isSaving) return
        if (current.symbol.isBlank()) {
            form.update { it.copy(error = "Ingresá el ticker o símbolo") }
            return
        }
        val quantity = current.quantityText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        if (quantity < 0.0) {
            form.update { it.copy(error = "Cantidad inválida") }
            return
        }
        val normalizedKey = MarketKeyParser.normalize(current.marketKey)
        val value = if (current.valueText.isBlank() && quantity > 0.0 && normalizedKey.isNotBlank()) {
            0L
        } else {
            MoneyParser.parseToCents(current.valueText)
        }
        if (value == null) {
            form.update { it.copy(error = "Ingresá una valuación o configurá cantidad + fuente automática") }
            return
        }
        val cost = if (current.costText.isBlank()) 0L else MoneyParser.parseToCents(current.costText)
        if (cost == null) {
            form.update { it.copy(error = "Costo inválido") }
            return
        }
        val tna = current.tnaText.trim().replace(',', '.').toDoubleOrNull()

        val holding = Holding(
            id = current.id,
            symbol = current.symbol.trim().uppercase(),
            name = current.name.trim(),
            assetType = current.assetType,
            currency = current.currency,
            currentValueCents = value,
            costCents = cost,
            tnaPercent = tna,
            maturityDate = current.maturity,
            notes = current.notes.trim(),
            marketKey = normalizedKey,
            source = MarketKeyParser.sourceFor(normalizedKey),
            quantity = quantity,
            quoteUnit = current.quoteUnit,
            isWallet = current.isWallet && current.assetType == AssetType.FCI,
            settlementDays = if (current.assetType == AssetType.FCI) current.settlementDays else 1,
        )

        form.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                investments.upsertHolding(holding)
                if (normalizedKey.isNotBlank()) {
                    runCatching { investments.syncMarketQuotes() }
                }
            }
                .onSuccess { form.update { it.copy(isSaving = false, saved = true) } }
                .onFailure { error ->
                    form.update {
                        it.copy(isSaving = false, error = error.message ?: "No se pudo guardar el activo")
                    }
                }
        }
    }

    fun delete() {
        val id = form.value.id
        if (id <= 0) return
        viewModelScope.launch {
            runCatching { investments.deleteHolding(id) }
                .onSuccess { form.update { it.copy(deleted = true) } }
                .onFailure { error ->
                    form.update { it.copy(error = error.message ?: "No se pudo eliminar el activo") }
                }
        }
    }

    private fun fromHolding(holding: Holding): HoldingForm = HoldingForm(
        id = holding.id,
        symbol = holding.symbol,
        name = holding.name,
        assetType = holding.assetType,
        currency = holding.currency,
        valueText = centsInput(holding.currentValueCents),
        costText = if (holding.costCents > 0) centsInput(holding.costCents) else "",
        tnaText = holding.tnaPercent?.takeIf { it > 0.0 }?.toString()?.replace('.', ',') ?: "",
        maturity = holding.maturityDate,
        notes = holding.notes,
        marketKey = holding.marketKey,
        quantityText = if (holding.quantity > 0.0) holding.quantity.toString().replace('.', ',') else "",
        quoteUnit = holding.quoteUnit,
        isWallet = holding.isWallet,
        settlementDays = holding.settlementDays,
        isLoading = false,
    )

    private fun centsInput(cents: Long): String =
        if (cents <= 0) "" else (cents / 100.0).toString().replace('.', ',')
}
