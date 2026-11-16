package com.smartinvest.ui.investments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.backup.AppBackupManager
import com.smartinvest.domain.engine.HoldingReconciliationItem
import com.smartinvest.domain.engine.PortfolioReconciliationEngine
import com.smartinvest.domain.engine.PortfolioReconciliationReport
import com.smartinvest.domain.engine.PortfolioValuationEngine
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToLong

data class HoldingDraftEdit(
    val valueText: String,
    val currency: CurrencyCode,
    val manualLocked: Boolean,
)

data class PortfolioReconciliationUiState(
    val report: PortfolioReconciliationReport = PortfolioReconciliationReport(),
    val drafts: Map<Long, HoldingDraftEdit> = emptyMap(),
    val mepInputText: String = "",
    val bulkPasteText: String = PortfolioReconciliationEngine.DEFAULT_2026_11_14_LIST.trim(),
    val isWorking: Boolean = false,
    val message: String? = null,
) {
    val hasUnsavedChanges: Boolean
        get() = report.items.any { item ->
            val draft = drafts[item.holding.id] ?: return@any false
            val parsedCents = MoneyParser.parseToCents(draft.valueText)
            (parsedCents != null && parsedCents != item.manualNativeCents) ||
                draft.currency != item.holding.currency ||
                draft.manualLocked != item.isManualLocked
        }
}

@HiltViewModel
class PortfolioReconciliationViewModel @Inject constructor(
    private val investments: InvestmentRepository,
    private val settingsRepository: SettingsRepository,
    private val backupManager: AppBackupManager,
) : ViewModel() {

    private val drafts = MutableStateFlow<Map<Long, HoldingDraftEdit>>(emptyMap())
    private val mepInput = MutableStateFlow("")
    private val bulkPaste = MutableStateFlow(PortfolioReconciliationEngine.DEFAULT_2026_11_14_LIST.trim())
    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val dataFlow = combine(
        investments.observeHoldings(),
        investments.observeAllQuotes(),
        investments.observeFxRates(),
        settingsRepository.settings,
    ) { holdings, quotes, fxRates, settings ->
        val (mep, estimated) = PortfolioValuationEngine.mepRate(fxRates, settings.fallbackMepRate)
        PortfolioReconciliationEngine.buildReport(
            holdings = holdings,
            quotes = quotes,
            mepRate = mep,
            mepIsEstimated = estimated,
        )
    }

    val uiState = combine(
        dataFlow,
        drafts,
        mepInput,
        bulkPaste,
        combine(working, message) { w, m -> w to m },
    ) { report, currentDrafts, mepText, pasteText, (isWorking, msg) ->
        val mergedDrafts = buildMap {
            report.items.forEach { item ->
                val existing = currentDrafts[item.holding.id]
                put(
                    item.holding.id,
                    existing ?: HoldingDraftEdit(
                        valueText = formatCentsInput(item.manualNativeCents),
                        currency = item.holding.currency,
                        manualLocked = item.isManualLocked || item.manualNativeCents > 0L,
                    ),
                )
            }
        }
        PortfolioReconciliationUiState(
            report = report,
            drafts = mergedDrafts,
            mepInputText = mepText.ifBlank { formatDecimalInput(report.mepRate) },
            bulkPasteText = pasteText,
            isWorking = isWorking,
            message = msg,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PortfolioReconciliationUiState(),
    )

    fun updateDraftValue(holdingId: Long, valueText: String) {
        val item = uiState.value.report.items.firstOrNull { it.holding.id == holdingId } ?: return
        drafts.update { map ->
            val current = map[holdingId] ?: HoldingDraftEdit(
                valueText = formatCentsInput(item.manualNativeCents),
                currency = item.holding.currency,
                manualLocked = true,
            )
            map + (holdingId to current.copy(valueText = valueText, manualLocked = true))
        }
    }

    /**
     * Changes the holding's currency label (`ARS` <-> `USD`) while keeping the exact
     * digits entered (useful when a number was typed with the other currency code).
     */
    fun updateDraftCurrency(holdingId: Long, currency: CurrencyCode) {
        val item = uiState.value.report.items.firstOrNull { it.holding.id == holdingId } ?: return
        drafts.update { map ->
            val current = map[holdingId] ?: HoldingDraftEdit(
                valueText = formatCentsInput(item.manualNativeCents),
                currency = item.holding.currency,
                manualLocked = true,
            )
            map + (holdingId to current.copy(currency = currency, manualLocked = true))
        }
    }

    /**
     * Converts the draft amount to the other currency using the current MEP rate.
     */
    fun convertDraftCurrencyWithMep(holdingId: Long) {
        val state = uiState.value
        val item = state.report.items.firstOrNull { it.holding.id == holdingId } ?: return
        val current = state.drafts[holdingId] ?: HoldingDraftEdit(
            valueText = formatCentsInput(item.manualNativeCents),
            currency = item.holding.currency,
            manualLocked = true,
        )
        val cents = MoneyParser.parseToCents(current.valueText) ?: item.manualNativeCents
        val mep = state.report.mepRate.takeIf { it > 0.0 } ?: 1_000.0
        val newCurrency = if (current.currency == CurrencyCode.ARS) CurrencyCode.USD else CurrencyCode.ARS
        val converted = if (newCurrency == CurrencyCode.USD) {
            (cents / mep).roundToLong()
        } else {
            (cents * mep).roundToLong()
        }
        drafts.update { map ->
            map + (holdingId to current.copy(
                valueText = formatCentsInput(converted),
                currency = newCurrency,
                manualLocked = true,
            ))
        }
    }

    fun toggleDraftManualLock(holdingId: Long, locked: Boolean) {
        val item = uiState.value.report.items.firstOrNull { it.holding.id == holdingId } ?: return
        drafts.update { map ->
            val current = map[holdingId] ?: HoldingDraftEdit(
                valueText = formatCentsInput(item.manualNativeCents),
                currency = item.holding.currency,
                manualLocked = item.isManualLocked,
            )
            map + (holdingId to current.copy(manualLocked = locked))
        }
    }

    fun useCalculatedForHolding(item: HoldingReconciliationItem) {
        val calc = item.calculatedNativeCents ?: return
        drafts.update { map ->
            map + (item.holding.id to HoldingDraftEdit(
                valueText = formatCentsInput(calc),
                currency = item.holding.currency,
                manualLocked = false,
            ))
        }
    }

    fun setMepInput(text: String) {
        mepInput.value = text
    }

    fun applyManualMep() {
        val raw = uiState.value.mepInputText.trim().replace(".", "").replace(',', '.')
        val parsed = raw.toDoubleOrNull()
        if (parsed == null || parsed <= 0.0) {
            message.value = "Ingresá un valor de dólar MEP válido."
            return
        }
        viewModelScope.launch {
            runCatching {
                val current = settingsRepository.currentSettings()
                settingsRepository.update(current.copy(fallbackMepRate = parsed))
                investments.refreshSnapshot()
            }.onSuccess {
                message.value = "MEP de respaldo actualizado a $ ${formatDecimalInput(parsed)}."
            }.onFailure { err ->
                message.value = err.message ?: "No se pudo actualizar el MEP"
            }
        }
    }

    fun setBulkPasteText(text: String) {
        bulkPaste.value = text
    }

    fun saveAllDrafts() {
        if (working.value) return
        val state = uiState.value
        val today = DateUtils.today()
        viewModelScope.launch {
            working.value = true
            message.value = null
            runCatching {
                var savedCount = 0
                for (item in state.report.items) {
                    val draft = state.drafts[item.holding.id] ?: continue
                    val cents = MoneyParser.parseToCents(draft.valueText)
                    if (cents == null || cents < 0L) continue
                    val changed = cents != item.manualNativeCents ||
                        draft.currency != item.holding.currency ||
                        draft.manualLocked != item.isManualLocked
                    if (changed) {
                        val updatedNotes = PortfolioReconciliationEngine.withManualValuationLock(
                            notes = item.holding.notes,
                            locked = draft.manualLocked,
                            date = today,
                        )
                        investments.upsertHolding(
                            item.holding.copy(
                                currentValueCents = cents,
                                currency = draft.currency,
                                notes = updatedNotes,
                            ),
                        )
                        savedCount++
                    }
                }
                investments.refreshSnapshot()
                savedCount
            }.onSuccess { count ->
                drafts.value = emptyMap()
                message.value = if (count > 0) {
                    "Guardados $count ajustes manuales y actualizado el snapshot de hoy."
                } else {
                    "Todos los saldos ya coinciden con los valores mostrados."
                }
            }.onFailure { err ->
                message.value = err.message ?: "No se pudieron guardar los ajustes manuales."
            }
            working.value = false
        }
    }

    fun apply2026_11_14Preset() {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = null
            runCatching {
                backupManager.syncExportedBrokerHoldings()
            }.onSuccess { summary ->
                drafts.value = emptyMap()
                message.value = summary
            }.onFailure { err ->
                message.value = err.message ?: "No se pudo aplicar la lista del 14/11/2026."
            }
            working.value = false
        }
    }

    fun applyBulkPasteList() {
        if (working.value) return
        val raw = uiState.value.bulkPasteText
        val parsed = PortfolioReconciliationEngine.parseAdjustmentText(raw)
        if (parsed.isEmpty()) {
            message.value = "No se detectaron líneas con formato 'TICKER MONTO ARS/USD'."
            return
        }
        viewModelScope.launch {
            working.value = true
            message.value = null
            runCatching {
                backupManager.applyHoldingAdjustments(
                    adjustments = parsed,
                    lockManual = true,
                    date = DateUtils.today(),
                )
            }.onSuccess { summary ->
                drafts.value = emptyMap()
                message.value = summary
            }.onFailure { err ->
                message.value = err.message ?: "No se pudo aplicar la lista pegada."
            }
            working.value = false
        }
    }

    fun syncQuotesForComparison() {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = null
            val fx = investments.refreshDollarRates()
            val quotes = runCatching { investments.syncMarketQuotes() }.getOrNull()
            investments.refreshSnapshot()
            message.value = buildString {
                append(quotes?.message ?: "Cotizaciones consultadas")
                if (fx.isSuccess) append(" · MEP actualizado")
                append(" (los saldos manuales fijos se conservaron sin cambios).")
            }
            working.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun formatCentsInput(cents: Long): String {
        if (cents <= 0L) return "0"
        val whole = cents / 100L
        val rem = cents % 100L
        return if (rem == 0L) {
            whole.toString()
        } else {
            String.format(java.util.Locale.US, "%d,%02d", whole, rem)
        }
    }

    private fun formatDecimalInput(value: Double): String =
        if (value % 1.0 == 0.0) {
            String.format(java.util.Locale.US, "%.0f", value)
        } else {
            String.format(java.util.Locale.US, "%.2f", value).replace('.', ',')
        }
}
