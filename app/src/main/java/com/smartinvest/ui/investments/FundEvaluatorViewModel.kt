package com.smartinvest.ui.investments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.engine.FundStabilityEngine
import com.smartinvest.domain.model.FundBenchmarkComparison
import com.smartinvest.domain.model.FundCatalogEntry
import com.smartinvest.domain.model.FundData
import com.smartinvest.domain.model.FundDateRange
import com.smartinvest.domain.model.FundEvaluation
import com.smartinvest.domain.model.FundFilterOption
import com.smartinvest.domain.model.FundOverlay
import com.smartinvest.domain.model.FundReturnHorizon
import com.smartinvest.domain.model.FundSimulation
import com.smartinvest.domain.model.FundSortOption
import com.smartinvest.domain.model.FundUniverse
import com.smartinvest.domain.repository.FundEvaluationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class FundEvaluatorUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val detailLoading: Boolean = false,
    val watchlist: List<String> = emptyList(),
    /** History-based evaluations of the followed funds (loaded once). */
    val evaluations: List<FundEvaluation> = emptyList(),
    val ranked: List<FundEvaluation> = emptyList(),
    val benchmark: FundEvaluation? = null,
    val universe: FundUniverse = FundUniverse.WATCHLIST,
    val windowDays: Int = FundStabilityEngine.DEFAULT_WINDOW,
    val horizon: FundReturnHorizon = FundReturnHorizon.YEAR,
    val sort: FundSortOption = FundSortOption.STABILITY,
    val filter: FundFilterOption = FundFilterOption.ALL,
    /** Optional stability floor in the 0..100 scale. 0 means "no minimum". */
    val minStability: Double = 0.0,
    val query: String = "",
    val catalog: List<FundCatalogEntry> = emptyList(),
    val catalogSize: Int = 0,
    val searchResults: List<FundCatalogEntry> = emptyList(),
    val selectedName: String? = null,
    val selected: FundEvaluation? = null,
    val selectedComparison: FundBenchmarkComparison? = null,
    val simulationAmountText: String = "1000000",
    val simulation: FundSimulation? = null,
    val dataAsOf: LocalDate? = null,
    val message: String? = null,
    /** Custom range bounds (already validated). Null means "rolling preset window". */
    val rangeStart: LocalDate? = null,
    val rangeEnd: LocalDate? = null,
    val rangeStartText: String = "",
    val rangeEndText: String = "",
    val rangeError: String? = null,
    val customRangeOpen: Boolean = false,
    /** Second fund superposed on the chart, chosen among the loaded histories. */
    val overlayName: String? = null,
    val overlay: FundOverlay? = null,
    val overlayCandidates: List<String> = emptyList(),
    /** The selected class has no observations inside the current date range. */
    val selectedOutOfRange: Boolean = false,
) {
    val attribution: String get() = "Datos: compararfondos.com.ar (CAFCI/CNV)"
    val hasMinimum: Boolean get() = minStability > 0.0
    val minimumLabel: String
        get() = if (hasMinimum) "≥ ${minStability.roundToInt()}" else "Sin mínimo"
    val hasCustomRange: Boolean get() = rangeStart != null || rangeEnd != null
}

@HiltViewModel
class FundEvaluatorViewModel @Inject constructor(
    private val repository: FundEvaluationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FundEvaluatorUiState())
    val uiState: StateFlow<FundEvaluatorUiState> = _uiState.asStateFlow()

    private var fundData: List<FundData> = emptyList()
    private var isWorking = false
    private var pendingLoad: Pair<Boolean, String?>? = null
    private var catalogEvaluations: List<FundEvaluation> = emptyList()
    private var catalogEvaluationsHorizon: FundReturnHorizon? = null
    private var catalogEvaluationsRef: List<FundCatalogEntry>? = null

    init {
        load(force = false)
    }

    fun refresh() = load(force = true)

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun selectUniverse(universe: FundUniverse) {
        if (universe == _uiState.value.universe) return
        // Keep the configuration, but do not carry a selection between universes.
        _uiState.update {
            it.copy(
                universe = universe,
                selectedName = null,
                selected = null,
                selectedComparison = null,
                simulation = null,
            )
        }
        when {
            universe == FundUniverse.CATALOG && _uiState.value.catalog.isEmpty() ->
                _uiState.update {
                    it.copy(message = "El catálogo todavía no está disponible. Actualizá e intentá de nuevo.")
                }
            else -> _uiState.update { it.copy(message = null) }
        }
        rebuild()
    }

    fun selectWindow(windowDays: Int) {
        if (windowDays == FundStabilityEngine.CUSTOM_WINDOW) {
            openCustomRange()
            return
        }
        if (windowDays == _uiState.value.windowDays && !_uiState.value.hasCustomRange) return
        _uiState.update {
            it.copy(
                windowDays = windowDays,
                rangeStart = null,
                rangeEnd = null,
                rangeStartText = "",
                rangeEndText = "",
                rangeError = null,
                customRangeOpen = false,
            )
        }
        rebuild()
    }

    fun openCustomRange() {
        _uiState.update { state ->
            val prefill = state.rangeStartText.isBlank() &&
                state.rangeEndText.isBlank() &&
                !state.hasCustomRange
            if (prefill) {
                val today = DateUtils.today()
                val start = today.minusDays(state.windowDays.toLong())
                state.copy(
                    customRangeOpen = true,
                    rangeError = null,
                    rangeStartText = formatDate(start),
                    rangeEndText = formatDate(today),
                )
            } else {
                state.copy(customRangeOpen = true, rangeError = null)
            }
        }
    }

    fun closeCustomRange() {
        _uiState.update { it.copy(customRangeOpen = false, rangeError = null) }
    }

    fun onRangeStartChange(text: String) {
        _uiState.update { it.copy(rangeStartText = text, rangeError = null) }
    }

    fun onRangeEndChange(text: String) {
        _uiState.update { it.copy(rangeEndText = text, rangeError = null) }
    }

    fun applyRange() {
        val state = _uiState.value
        val start = parseDate(state.rangeStartText)
        val end = parseDate(state.rangeEndText)
        val error = when {
            state.rangeStartText.isNotBlank() && start == null ->
                "La fecha «desde» no es válida. Usá dd/MM/aaaa."
            state.rangeEndText.isNotBlank() && end == null ->
                "La fecha «hasta» no es válida. Usá dd/MM/aaaa."
            start == null && end == null ->
                "Ingresá al menos una fecha para acotar el análisis."
            start != null && end != null && start.isAfter(end) ->
                "La fecha «desde» no puede ser posterior a la fecha «hasta»."
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(rangeError = error) }
            return
        }
        _uiState.update {
            it.copy(
                rangeStart = start,
                rangeEnd = end,
                rangeError = null,
                customRangeOpen = true,
            )
        }
        rebuild()
    }

    /** Quick ranges from the UI: null means "year to date". */
    fun applyQuickRange(days: Int?) {
        val today = DateUtils.today()
        val start = if (days == null) LocalDate.of(today.year, 1, 1) else today.minusDays(days.toLong())
        _uiState.update {
            it.copy(
                rangeStart = start,
                rangeEnd = today,
                rangeStartText = formatDate(start),
                rangeEndText = formatDate(today),
                rangeError = null,
                customRangeOpen = true,
            )
        }
        rebuild()
    }

    fun clearRange() {
        _uiState.update {
            it.copy(
                rangeStart = null,
                rangeEnd = null,
                rangeStartText = "",
                rangeEndText = "",
                rangeError = null,
            )
        }
        rebuild()
    }

    fun selectOverlay(name: String?) {
        if (name == _uiState.value.overlayName) return
        _uiState.update { it.copy(overlayName = name) }
        rebuild()
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedName = null) }
        rebuild()
    }

    fun selectHorizon(horizon: FundReturnHorizon) {
        if (horizon == _uiState.value.horizon) return
        _uiState.update { it.copy(horizon = horizon) }
        rebuild()
    }

    fun selectSort(sort: FundSortOption) {
        if (sort == _uiState.value.sort) return
        _uiState.update { it.copy(sort = sort) }
        rebuild()
    }

    fun selectFilter(filter: FundFilterOption) {
        if (filter == _uiState.value.filter) return
        _uiState.update { it.copy(filter = filter) }
        rebuild()
    }

    fun setMinStability(value: Double) {
        val rounded = (value.coerceIn(0.0, 100.0) / 5.0).roundToInt() * 5.0
        if (rounded == _uiState.value.minStability) return
        _uiState.update { it.copy(minStability = rounded) }
        rebuild()
    }

    fun selectFund(name: String) {
        _uiState.update {
            it.copy(
                selectedName = name,
                overlayName = it.overlayName?.takeIf { overlay -> !overlay.equals(name, ignoreCase = true) },
            )
        }
        rebuild()
        val selected = _uiState.value.selected
        if (selected != null && selected.fromSummary) {
            loadHistory(name)
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        rebuild()
    }

    fun onSimulationAmountChange(text: String) {
        _uiState.update { it.copy(simulationAmountText = text) }
        rebuildSimulation()
    }

    fun simulate() {
        rebuildSimulation()
        if (_uiState.value.simulation == null) {
            val selected = _uiState.value.selected
            _uiState.update {
                it.copy(
                    message = if (selected == null || selected.fromSummary) {
                        "Cargá el historial del fondo para simular con la serie real."
                    } else {
                        "Ingresá un monto mayor a cero para simular."
                    },
                )
            }
        }
    }

    fun addFund(entry: FundCatalogEntry) {
        viewModelScope.launch {
            repository.addToWatchlist(entry.name)
            _uiState.update { it.copy(query = "") }
            load(force = false, notice = "Agregado al seguimiento: ${entry.name}")
        }
    }

    fun followSelected() {
        val selected = _uiState.value.selected ?: return
        viewModelScope.launch {
            repository.addToWatchlist(selected.name)
            _uiState.update { it.copy(selectedName = selected.name) }
            load(force = false, notice = "Agregado al seguimiento: ${selected.name}")
        }
    }

    fun removeFund(name: String) {
        viewModelScope.launch {
            repository.removeFromWatchlist(name)
            _uiState.update { state ->
                state.copy(
                    selectedName = state.selectedName?.takeIf { it != name },
                )
            }
            load(force = false, notice = "Quitado del seguimiento: $name")
        }
    }

    fun resetWatchlist() {
        viewModelScope.launch {
            repository.resetWatchlist()
            _uiState.update { it.copy(selectedName = null) }
            load(force = false, notice = "Lista restaurada con los fondos sugeridos.")
        }
    }

    private fun loadHistory(name: String) {
        if (_uiState.value.detailLoading) return
        _uiState.update { it.copy(detailLoading = true) }
        viewModelScope.launch {
            val data = runCatching { repository.loadFund(name) }.getOrNull()
            if (data != null && data.history.size >= 2) {
                fundData = fundData.filterNot { it.name.equals(name, ignoreCase = true) } + data
            }
            _uiState.update { it.copy(detailLoading = false) }
            rebuild()
        }
    }

    private fun load(force: Boolean, notice: String? = null) {
        if (isWorking) {
            // Keep the latest request instead of dropping a user action that
            // arrived while a refresh was still running.
            pendingLoad = force to notice
            return
        }
        isWorking = true
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isLoading = it.evaluations.isEmpty(),
                        isRefreshing = force,
                        message = notice ?: it.message,
                    )
                }

                val watchlist = runCatching { repository.ensureSeededWatchlist() }
                    .getOrElse { FundEvaluationRepository.DEFAULT_WATCHLIST }
                val names = (watchlist + FundStabilityEngine.BENCHMARK_NAME).distinct()
                val requested = names.size
                val data = runCatching { repository.loadFunds(names, force) }
                    .getOrElse { emptyList() }
                val catalog = runCatching { repository.catalog(force = false) }
                    .getOrElse { emptyList() }
                fundData = data

                val loaded = data.count { it.history.size >= 2 }
                val failure = when {
                    data.isEmpty() -> "No se pudo consultar compararfondos.com.ar. Revisá la conexión e intentá de nuevo."
                    loaded == 0 -> "No hay historial disponible para estos fondos. Intentá actualizar en unos minutos."
                    catalog.isEmpty() -> "No se pudo cargar el catálogo completo: podés evaluar el seguimiento, pero el buscador no está disponible."
                    loaded < requested -> "Se evaluaron $loaded de $requested fondos. El resto no tiene historial disponible."
                    else -> null
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        watchlist = watchlist,
                        catalog = catalog,
                        catalogSize = catalog.size,
                        message = notice ?: failure,
                    )
                }
                rebuild()
            } finally {
                isWorking = false
                if (_uiState.value.isLoading || _uiState.value.isRefreshing) {
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                }
                val queued = pendingLoad
                pendingLoad = null
                if (queued != null) load(queued.first, queued.second)
            }
        }
    }

    private fun rebuild() {
        val state = _uiState.value
        val followed = state.watchlist.map { it.lowercase() }.toSet()
        val benchmarkKey = FundStabilityEngine.BENCHMARK_NAME.lowercase()
        val range = if (state.hasCustomRange) FundDateRange(state.rangeStart, state.rangeEnd) else null

        val historyEvaluations = fundData.mapNotNull { data ->
            if (range != null) {
                FundStabilityEngine.evaluate(data, range)
            } else {
                FundStabilityEngine.evaluate(data, state.windowDays)
            }
        }
        val followedEvaluations = historyEvaluations.filter {
            it.name.lowercase() in followed || it.name.lowercase() == benchmarkKey
        }
        val benchmark = followedEvaluations.firstOrNull {
            it.name.equals(FundStabilityEngine.BENCHMARK_NAME, ignoreCase = true)
        }

        val catalogEvaluations = if (state.universe == FundUniverse.CATALOG) {
            cachedCatalogEvaluations(state.catalog, state.horizon)
        } else {
            emptyList()
        }

        val pool = when (state.universe) {
            FundUniverse.WATCHLIST -> followedEvaluations
            FundUniverse.CATALOG -> catalogEvaluations
        }.filterNot {
            it.name.equals(FundStabilityEngine.BENCHMARK_NAME, ignoreCase = true)
        }.filter {
            FundStabilityEngine.matchesThreshold(it, state.minStability) &&
                FundStabilityEngine.matchesFilter(it, state.filter)
        }
        val ranked = FundStabilityEngine.rank(pool, state.sort)

        val selectedName = state.selectedName
        val selected = when {
            selectedName != null -> historyEvaluations.firstOrNull {
                it.name.equals(selectedName, ignoreCase = true)
            } ?: catalogEvaluations.firstOrNull {
                it.name.equals(selectedName, ignoreCase = true)
            }
            state.universe == FundUniverse.WATCHLIST -> ranked.firstOrNull() ?: benchmark
            else -> null
        }
        val comparison = if (selected != null && !selected.fromSummary && benchmark != null &&
            !selected.name.equals(benchmark.name, ignoreCase = true)
        ) {
            FundStabilityEngine.compare(selected, benchmark, selected.windowDays)
        } else {
            null
        }

        val overlayEvaluation = state.overlayName?.let { name ->
            historyEvaluations.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
        val overlay = if (selected != null && !selected.fromSummary && overlayEvaluation != null) {
            FundStabilityEngine.overlay(selected, overlayEvaluation)
        } else {
            null
        }
        val overlayCandidates = historyEvaluations
            .filter { evaluation ->
                selected == null || !evaluation.name.equals(selected.name, ignoreCase = true)
            }
            .map { it.name }

        val searchResults = searchCatalog(state.catalog, state.query, state.watchlist)
        val dataAsOf = historyEvaluations.mapNotNull { it.lastDate }.maxOrNull()
            ?: state.catalog.mapNotNull { it.vcpDate }.maxOrNull()

        _uiState.update {
            it.copy(
                evaluations = followedEvaluations,
                ranked = ranked,
                benchmark = benchmark,
                searchResults = searchResults,
                selectedName = selected?.name,
                selected = selected,
                selectedComparison = comparison,
                dataAsOf = dataAsOf,
                overlay = overlay,
                overlayCandidates = overlayCandidates,
                selectedOutOfRange = state.selectedName != null && selected == null,
            )
        }
        rebuildSimulation()
    }

    private fun cachedCatalogEvaluations(
        catalog: List<FundCatalogEntry>,
        horizon: FundReturnHorizon,
    ): List<FundEvaluation> {
        if (catalogEvaluationsRef === catalog && catalogEvaluationsHorizon == horizon) {
            return catalogEvaluations
        }
        val built = catalog
            .asSequence()
            .filter { it.name.isNotBlank() }
            .map { FundStabilityEngine.evaluateFromSummary(it, horizon) }
            .toList()
        catalogEvaluations = built
        catalogEvaluationsHorizon = horizon
        catalogEvaluationsRef = catalog
        return built
    }

    private fun rebuildSimulation() {
        val state = _uiState.value
        val selected = state.selected
        val amount = parseAmountCents(state.simulationAmountText)
        val simulation = if (selected != null && !selected.fromSummary && amount != null) {
            if (state.hasCustomRange) {
                FundStabilityEngine.simulate(
                    selected.history,
                    amount,
                    FundDateRange(state.rangeStart, state.rangeEnd),
                )
            } else {
                FundStabilityEngine.simulate(selected.history, amount, state.windowDays)
            }
        } else {
            null
        }
        _uiState.update { it.copy(simulation = simulation) }
    }

    private fun searchCatalog(
        catalog: List<FundCatalogEntry>,
        query: String,
        watchlist: List<String>,
    ): List<FundCatalogEntry> {
        val needle = query.normalize()
        if (needle.length < 2) return emptyList()
        val followed = watchlist.map { it.normalize() }.toSet()
        return catalog
            .asSequence()
            .filter { entry ->
                val name = entry.name.normalize()
                name.contains(needle) ||
                    entry.manager.normalize().contains(needle) ||
                    entry.category.normalize().contains(needle)
            }
            .sortedWith(
                compareBy<FundCatalogEntry> { entry ->
                    if (entry.name.normalize().startsWith(needle)) 0 else 1
                }.thenBy { it.name.lowercase(Locale.forLanguageTag("es-AR")) },
            )
            .filterNot { it.name.normalize() in followed }
            .take(SEARCH_LIMIT)
            .toList()
    }

    private fun parseAmountCents(text: String): Long? {
        val clean = text.trim().replace("\u00A0", "").replace(" ", "")
        if (clean.isEmpty()) return null
        val normalized = when {
            clean.contains(',') -> clean.replace(".", "").replace(',', '.')
            clean.count { it == '.' } == 1 && clean.substringAfterLast('.').length <= 2 -> clean
            else -> clean.replace(".", "")
        }
        val value = normalized.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        return (value * 100.0).roundToLong()
    }

    private fun parseDate(raw: String): LocalDate? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        for (pattern in DATE_PATTERNS) {
            val parsed = runCatching {
                LocalDate.parse(clean, DateTimeFormatter.ofPattern(pattern, ES_AR))
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun formatDate(date: LocalDate): String = date.format(DATE_DISPLAY)

    private fun String.normalize(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(ACCENT_MARKS, "")
            .lowercase(Locale.ROOT)
            .trim()

    companion object {
        private const val SEARCH_LIMIT = 40
        private val ACCENT_MARKS = Regex("\\p{M}+")
        private val ES_AR = Locale.forLanguageTag("es-AR")
        private val DATE_PATTERNS = listOf(
            "dd/MM/yyyy",
            "dd-MM-yyyy",
            "d/M/yyyy",
            "d-M-yyyy",
            "yyyy-MM-dd",
        )
        private val DATE_DISPLAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", ES_AR)
    }
}
