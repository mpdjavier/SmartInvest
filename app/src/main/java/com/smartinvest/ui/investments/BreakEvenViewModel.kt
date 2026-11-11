package com.smartinvest.ui.investments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.usecase.ObservePortfolioOverviewUseCase
import com.smartinvest.domain.usecase.PortfolioOverview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BreakEvenUiState(
    val overview: PortfolioOverview? = null,
    val isWorking: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class BreakEvenViewModel @Inject constructor(
    observePortfolio: ObservePortfolioOverviewUseCase,
    private val investments: InvestmentRepository,
) : ViewModel() {

    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState = combine(
        observePortfolio(),
        working,
        message,
    ) { overview, isWorking, msg ->
        BreakEvenUiState(overview = overview, isWorking = isWorking, message = msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BreakEvenUiState())

    fun refreshDollar() {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = null
            val result = runCatching { investments.refreshDollarRates() }
            investments.refreshSnapshot()
            message.value = if (result.isSuccess) {
                "Dólar actualizado"
            } else {
                "DolarAPI sin respuesta: se mantiene el último MEP disponible."
            }
            working.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }
}
