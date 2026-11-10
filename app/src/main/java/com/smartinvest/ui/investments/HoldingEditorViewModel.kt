package com.smartinvest.ui.investments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.data.network.MarketKeyParser
import com.smartinvest.data.network.MarketPreset
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
    val tnaText: String = "",
    val maturity: LocalDate? = null,
    val notes: String = "",
    val marketKey: String = "",
    val quantityText: String = "",
    val quoteUnit: QuoteUnit = QuoteUnit.PER_UNIT,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
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
    fun setType(value: AssetType) = form.update { it.copy(assetType = value, error = null) }
    fun setCurrency(value: CurrencyCode) = form.update { it.copy(currency = value, error = null) }
    fun setValue(value: String) = form.update { it.copy(valueText = value, error = null) }
    fun setCost(value: String) = form.update { it.copy(costText = value, error = null) }
    fun setTna(value: String) = form.update { it.copy(tnaText = value, error = null) }
    fun setMaturity(value: LocalDate?) = form.update { it.copy(maturity = value, error = null) }
    fun setNotes(value: String) = form.update { it.copy(notes = value) }
    fun setMarketKey(value: String) = form.update { it.copy(marketKey = value, error = null) }
    fun setQuantity(value: String) = form.update { it.copy(quantityText = value, error = null) }
    fun setQuoteUnit(value: QuoteUnit) = form.update { it.copy(quoteUnit = value, error = null) }
    fun clearError() = form.update { it.copy(error = null) }

    fun applyPreset(preset: MarketPreset) {
        form.update {
            it.copy(
                symbol = preset.symbol,
                name = preset.name,
                assetType = preset.assetType,
                currency = preset.currency,
                quoteUnit = preset.quoteUnit,
                marketKey = preset.marketKey,
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
        isLoading = false,
    )

    private fun centsInput(cents: Long): String =
        if (cents <= 0) "" else (cents / 100.0).toString().replace('.', ',')
}
