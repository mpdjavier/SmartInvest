package com.smartinvest.ui.rebalancing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.engine.RemComparisonEngine
import com.smartinvest.domain.engine.RemConfigCodec
import com.smartinvest.domain.engine.RemXlsxParser
import com.smartinvest.domain.model.RemComparison
import com.smartinvest.domain.model.RemConfig
import com.smartinvest.domain.model.RemDefaults
import com.smartinvest.domain.model.RemInstrument
import com.smartinvest.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import javax.inject.Inject

data class RemUiState(
    val config: RemConfig = RemConfig(),
    val comparison: RemComparison? = null,
    val isWorking: Boolean = false,
    val message: String? = null,
) {
    val today: java.time.LocalDate get() = comparison?.today ?: DateUtils.today()
}

@HiltViewModel
class RemViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val working = MutableStateFlow(false)
    private val today = DateUtils.today()

    private val initialConfig = RemConfig()

    val uiState: StateFlow<RemUiState> = combine(
        settingsRepository.settings,
        message,
        working,
    ) { settings, currentMessage, isWorking ->
        val config = RemConfigCodec.decode(settings.remConfigJson)
        RemUiState(
            config = config,
            comparison = RemComparisonEngine.compare(
                snapshot = config.snapshot,
                instruments = config.instruments,
                horizonMonth = config.horizon,
                crawlingPegMonthlyPercent = config.crawlingPegMonthlyPercent,
                today = today,
            ),
            isWorking = isWorking,
            message = currentMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RemUiState(
            config = initialConfig,
            comparison = RemComparisonEngine.compare(
                snapshot = initialConfig.snapshot,
                instruments = initialConfig.instruments,
                horizonMonth = initialConfig.horizon,
                crawlingPegMonthlyPercent = initialConfig.crawlingPegMonthlyPercent,
                today = today,
            ),
        ),
    )

    fun setCrawlingPeg(value: Double) {
        val peg = value.coerceIn(0.0, 20.0)
        updateConfig { it.copy(crawlingPegMonthlyPercent = peg) }
    }

    fun setHorizon(month: YearMonth) {
        updateConfig { it.copy(horizonMonth = month.toString()) }
    }

    fun saveInstrument(instrument: RemInstrument) {
        val clean = instrument.copy(
            id = instrument.id.ifBlank { "custom-${System.currentTimeMillis()}" },
            symbol = instrument.symbol.trim().uppercase(),
            name = instrument.name.trim().ifBlank { instrument.symbol.trim().uppercase() },
        )
        if (clean.symbol.isBlank()) {
            message.value = "El símbolo del instrumento no puede quedar vacío."
            return
        }
        updateConfig { config ->
            val exists = config.instruments.any { it.id == clean.id }
            config.copy(
                instruments = if (exists) {
                    config.instruments.map { if (it.id == clean.id) clean else it }
                } else {
                    config.instruments + clean
                },
            )
        }
        message.value = "${clean.symbol} guardado."
    }

    fun deleteInstrument(id: String) {
        updateConfig { config ->
            config.copy(instruments = config.instruments.filterNot { it.id == id })
        }
        message.value = "Instrumento eliminado."
    }

    fun importRemXlsx(bytes: ByteArray) {
        viewModelScope.launch {
            working.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching { RemXlsxParser.parse(bytes) }
            }
            result.onSuccess { snapshot ->
                updateConfig { config ->
                    val nextHorizon = snapshot.lastMonth
                        ?.let { minOf(it, RemComparisonEngine.ELECTION_HORIZON) }
                        ?: config.horizon
                    config.copy(snapshot = snapshot, horizonMonth = nextHorizon.toString())
                }
                message.value = "REM importado: ${snapshot.reference} · ${snapshot.points.size} meses."
            }.onFailure { error ->
                message.value = "No se pudo importar el REM: ${error.message ?: "formato desconocido"}."
            }
            working.value = false
        }
    }

    fun resetSnapshot() {
        updateConfig {
            it.copy(
                snapshot = RemDefaults.snapshot,
                horizonMonth = RemDefaults.DEFAULT_HORIZON,
            )
        }
        message.value = "REM de octubre 2026 restaurado."
    }

    fun resetInstruments() {
        updateConfig { it.copy(instruments = RemDefaults.instruments) }
        message.value = "Instrumentos de referencia restaurados."
    }

    fun clearMessage() {
        message.value = null
    }

    private fun updateConfig(transform: (RemConfig) -> RemConfig) {
        viewModelScope.launch {
            val settings = settingsRepository.currentSettings()
            val current = RemConfigCodec.decode(settings.remConfigJson)
            val updated = transform(current)
            settingsRepository.update(settings.copy(remConfigJson = RemConfigCodec.encode(updated)))
        }
    }
}
