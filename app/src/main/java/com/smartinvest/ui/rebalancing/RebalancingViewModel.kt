package com.smartinvest.ui.rebalancing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.domain.engine.OrganicRebalancePlanner
import com.smartinvest.domain.model.OrganicRebalancePlan
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.usecase.MacroRebalanceContext
import com.smartinvest.domain.usecase.ObserveMacroRebalanceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class RebalancingUiState(
    val context: MacroRebalanceContext? = null,
    val plan: OrganicRebalancePlan? = null,
    val salaryText: String = "",
    val isWorking: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class RebalancingViewModel @Inject constructor(
    observeMacro: ObserveMacroRebalanceUseCase,
    private val investments: InvestmentRepository,
) : ViewModel() {

    private val salaryText = MutableStateFlow("")
    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val userEditedSalary = MutableStateFlow(false)

    private val contextFlow = observeMacro()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            contextFlow.collect { context ->
                if (
                    context != null &&
                    !userEditedSalary.value &&
                    salaryText.value.isBlank() &&
                    context.latestSalaryCents > 0L
                ) {
                    salaryText.value = centsToInput(context.latestSalaryCents)
                }
            }
        }
    }

    val uiState = combine(
        contextFlow,
        salaryText,
        working,
        message,
        userEditedSalary,
    ) { context, text, isWorking, msg, edited ->
        val parsed = MoneyParser.parseToCents(text) ?: 0L
        val effectiveIncome = when {
            parsed > 0L -> parsed
            edited -> 0L
            else -> context?.latestSalaryCents ?: 0L
        }
        val plan = context?.let { current ->
            OrganicRebalancePlanner.plan(
                valuation = current.valuation,
                assessment = current.assessment,
                nextIncomeCents = effectiveIncome,
                settings = current.settings,
            )
        }
        RebalancingUiState(
            context = context,
            plan = plan,
            salaryText = text,
            isWorking = isWorking,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RebalancingUiState())

    fun setSalary(value: String) {
        userEditedSalary.value = true
        salaryText.value = value
    }

    fun useLastSalary() {
        val latest = contextFlow.value?.latestSalaryCents ?: 0L
        if (latest <= 0L) {
            message.value = "Todavía no hay un plan de sueldo guardado."
            return
        }
        userEditedSalary.value = false
        salaryText.value = centsToInput(latest)
    }

    fun refresh() {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = null
            val fx = investments.refreshDollarRates()
            val rates = runCatching { investments.syncRates() }.getOrNull()
            val quotes = runCatching { investments.syncMarketQuotes() }.getOrNull()
            investments.refreshSnapshot()
            message.value = buildString {
                append(
                    if (fx.isSuccess) {
                        "Dólar oficial y MEP actualizados"
                    } else {
                        "DolarAPI sin respuesta (se usa el último MEP / respaldo)"
                    },
                )
                if (rates != null) {
                    append(" · ")
                    append(rates.message)
                }
                if (quotes != null) {
                    append(" · ")
                    append(quotes.message)
                }
            }
            working.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun centsToInput(cents: Long): String =
        String.format(Locale.forLanguageTag("es-AR"), "%.2f", cents / 100.0)
}
