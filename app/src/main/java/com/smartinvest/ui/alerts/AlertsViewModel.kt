package com.smartinvest.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.domain.model.AlertType
import com.smartinvest.domain.model.AppAlert
import com.smartinvest.domain.repository.AlertsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlertsUiState(
    val alerts: List<AppAlert> = emptyList(),
    val unreadCount: Int = 0,
    val filter: AlertType? = null,
    val isScanning: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val repository: AlertsRepository,
) : ViewModel() {

    private val filter = MutableStateFlow<AlertType?>(null)
    private val scanning = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState = combine(
        repository.observeAlerts(),
        repository.observeUnreadCount(),
        filter,
        scanning,
        message,
    ) { alerts, unread, typeFilter, isScanning, msg ->
        AlertsUiState(
            alerts = if (typeFilter == null) alerts else alerts.filter { it.type == typeFilter },
            unreadCount = unread,
            filter = typeFilter,
            isScanning = isScanning,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

    fun setFilter(type: AlertType?) {
        filter.value = type
    }

    fun markRead(id: Long) {
        viewModelScope.launch { runCatching { repository.markRead(id) } }
    }

    fun markAllRead() {
        viewModelScope.launch {
            runCatching { repository.markAllRead() }
                .onSuccess { message.value = "Todas las alertas marcadas como leídas." }
                .onFailure { message.value = it.message ?: "No se pudo actualizar" }
        }
    }

    fun scan() {
        if (scanning.value) return
        viewModelScope.launch {
            scanning.value = true
            message.value = null
            runCatching { repository.scanAlerts() }
                .onSuccess { created ->
                    message.value = if (created.isEmpty()) {
                        "Sin novedades: tasas, vencimientos y rebalanceo en orden."
                    } else {
                        "${created.size} alerta(s) nueva(s)."
                    }
                }
                .onFailure { message.value = it.message ?: "No se pudo escanear" }
            scanning.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }
}
