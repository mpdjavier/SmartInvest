package com.smartinvest.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.domain.repository.AlertsRepository
import com.smartinvest.domain.repository.BootstrapRepository
import com.smartinvest.domain.repository.InvestmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val bootstrap: BootstrapRepository,
    private val alerts: AlertsRepository,
    private val investments: InvestmentRepository,
) : ViewModel() {

    val unreadAlerts = alerts.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch {
            runCatching { bootstrap.seedIfNeeded() }
            runCatching { investments.refreshDollarRates() }
            runCatching { investments.refreshSnapshot() }
            runCatching { alerts.scanAlerts() }
        }
    }
}
