@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.smartinvest.ui.investments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.domain.model.ComparaTasasCategory
import com.smartinvest.domain.model.ComparaTasasConfig
import com.smartinvest.domain.model.ComparaTasasItem
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.SettingsRepository
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.MetricPill
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.PlexMono
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.theme.Violet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class ComparaTasasUiState(
    val config: ComparaTasasConfig = ComparaTasasConfig(),
    val searchQuery: String = "",
    val isRefreshing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ComparaTasasViewModel @Inject constructor(
    private val investments: InvestmentRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private var autoLoaded = false

    val uiState = combine(
        settingsRepository.settings,
        searchQuery,
        refreshing,
        message,
    ) { settings, query, isRefreshing, msg ->
        ComparaTasasUiState(
            config = settings.comparaTasasConfig,
            searchQuery = query,
            isRefreshing = isRefreshing,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ComparaTasasUiState())

    fun ensureLoaded() {
        if (autoLoaded || refreshing.value) return
        autoLoaded = true
        if (uiState.value.config.catalog.items.isEmpty()) {
            refreshCatalog()
        }
    }

    fun refreshCatalog() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            message.value = null
            val res = investments.refreshComparaTasasCatalog()
            message.value = res.fold(
                onSuccess = { cat ->
                    "ComparaTasas actualizado: ${cat.items.size} productos desde ${cat.source}."
                },
                onFailure = { err ->
                    err.message ?: "No se pudo consultar ComparaTasas. Se muestra el último resumen guardado."
                },
            )
            refreshing.value = false
        }
    }

    fun setSearchQuery(value: String) {
        searchQuery.value = value
    }

    fun toggleCategory(category: ComparaTasasCategory) {
        viewModelScope.launch {
            val currentSettings = settingsRepository.currentSettings()
            val cfg = currentSettings.comparaTasasConfig
            val updatedSet = if (category.name in cfg.selectedCategories) {
                cfg.selectedCategories - category.name
            } else {
                cfg.selectedCategories + category.name
            }
            val updated = cfg.copy(selectedCategories = updatedSet)
            settingsRepository.update(
                currentSettings.copy(comparaTasasConfigJson = ComparaTasasConfig.encode(updated)),
            )
        }
    }

    fun selectAllCategories() {
        viewModelScope.launch {
            val currentSettings = settingsRepository.currentSettings()
            val cfg = currentSettings.comparaTasasConfig
            val updated = cfg.copy(
                selectedCategories = ComparaTasasCategory.entries.map { it.name }.toSet(),
            )
            settingsRepository.update(
                currentSettings.copy(comparaTasasConfigJson = ComparaTasasConfig.encode(updated)),
            )
        }
    }

    fun selectDefaultRateCategories() {
        viewModelScope.launch {
            val currentSettings = settingsRepository.currentSettings()
            val cfg = currentSettings.comparaTasasConfig
            val updated = cfg.copy(selectedCategories = ComparaTasasCategory.DEFAULT_SELECTED)
            settingsRepository.update(
                currentSettings.copy(comparaTasasConfigJson = ComparaTasasConfig.encode(updated)),
            )
        }
    }

    fun clearCategories() {
        viewModelScope.launch {
            val currentSettings = settingsRepository.currentSettings()
            val cfg = currentSettings.comparaTasasConfig
            val updated = cfg.copy(selectedCategories = emptySet())
            settingsRepository.update(
                currentSettings.copy(comparaTasasConfigJson = ComparaTasasConfig.encode(updated)),
            )
        }
    }

    fun setShowOnDashboard(show: Boolean) {
        viewModelScope.launch {
            val currentSettings = settingsRepository.currentSettings()
            val cfg = currentSettings.comparaTasasConfig
            val updated = cfg.copy(showOnDashboard = show)
            settingsRepository.update(
                currentSettings.copy(comparaTasasConfigJson = ComparaTasasConfig.encode(updated)),
            )
        }
    }

    fun clearMessage() {
        message.value = null
    }
}

@Composable
fun ComparaTasasScreen(
    viewModel: ComparaTasasViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val config = state.config
    val catalog = config.catalog
    var showForm by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(catalog.items.size) {
        if (catalog.items.isEmpty()) {
            viewModel.ensureLoaded()
        }
    }

    val countsByCategory = catalog.items.groupingBy { it.categoryName }.eachCount()
    val query = state.searchQuery.trim().lowercase(Locale.forLanguageTag("es-AR"))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Resumen ComparaTasas", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (catalog.items.isNotEmpty()) {
                            "${catalog.items.size} productos relevados · Fuente: ${catalog.source}"
                        } else {
                            "Tasas de billeteras, FCI, plazos fijos, USD, cripto y comisiones"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { showForm = !showForm },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Rounded.Checklist, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (showForm) "Ocultar filtro" else "Qué ver")
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = viewModel::refreshCatalog, enabled = !state.isRefreshing) {
                    if (state.isRefreshing) {
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

        state.message?.let { msg ->
            item {
                Column {
                    InfoBanner(msg, color = Sky)
                    TextButton(onClick = viewModel::clearMessage) { Text("Cerrar") }
                }
            }
        }

        if (showForm) {
            item {
                SmartCard {
                    SectionHeader(
                        title = "Formulario: elegí qué información ver",
                        subtitle = "Tildá las secciones que querés incluir acá y en el acceso rápido de Inicio",
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Mostrar acceso y resumen en Inicio",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "Agrega una tarjeta en la pantalla principal con el top de cada sección tildada.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = config.showOnDashboard,
                            onCheckedChange = viewModel::setShowOnDashboard,
                        )
                    }
                    ThinDivider()
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = viewModel::selectAllCategories,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("Tildar todo")
                        }
                        OutlinedButton(
                            onClick = viewModel::selectDefaultRateCategories,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("Solo tasas (ARS/USD)")
                        }
                        TextButton(onClick = viewModel::clearCategories) {
                            Text("Destildar todo")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    ComparaTasasCategory.entries.forEach { category ->
                        val checked = category.name in config.selectedCategories
                        val count = countsByCategory[category.name] ?: 0
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { viewModel.toggleCategory(category) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { viewModel.toggleCategory(category) },
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        category.label,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    if (count > 0) {
                                        Spacer(Modifier.width(6.dp))
                                        TagPill("$count", categoryAccent(category))
                                    }
                                }
                                Text(
                                    category.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                label = { Text("Filtrar por entidad, fondo o producto") },
                placeholder = { Text("Mercado Pago, Ualá, Naranja X, Galicia, Adcap…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val activeCategories = config.activeCategories
        if (activeCategories.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Checklist,
                    title = "Ninguna categoría tildada",
                    message = "Tildá al menos una categoría en el formulario de arriba para ver el resumen de ComparaTasas.",
                )
            }
        } else if (catalog.items.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.AutoMirrored.Rounded.TrendingUp,
                    title = if (state.isRefreshing) "Consultando ComparaTasas…" else "Sin datos descargados",
                    message = "Tocá el botón de actualizar arriba a la derecha para traer el catálogo en vivo.",
                )
            }
        } else {
            activeCategories.forEach { category ->
                val rawItems = catalog.items.filter { it.categoryName == category.name }
                val filtered = if (query.isBlank()) {
                    rawItems
                } else {
                    rawItems.filter {
                        it.entityName.lowercase(Locale.ROOT).contains(query) ||
                            it.productName.lowercase(Locale.ROOT).contains(query) ||
                            it.conditions.lowercase(Locale.ROOT).contains(query) ||
                            it.metricLabel.lowercase(Locale.ROOT).contains(query)
                    }
                }
                val sorted = sortItemsForCategory(category, filtered)
                if (sorted.isNotEmpty()) {
                    item(key = "header_${category.name}") {
                        SectionHeader(
                            title = category.label,
                            subtitle = "${sorted.size} opciones · ${category.subtitle}",
                        )
                    }
                    items(sorted.size, key = { idx -> "${category.name}_${idx}_${sorted[idx].entityName}_${sorted[idx].productName}" }) { idx ->
                        ComparaTasasItemRow(
                            item = sorted[idx],
                            accent = categoryAccent(category),
                        )
                    }
                }
            }
        }
    }
}

internal fun sortItemsForCategory(
    category: ComparaTasasCategory,
    items: List<ComparaTasasItem>,
): List<ComparaTasasItem> {
    val lowerIsBetter = category in setOf(
        ComparaTasasCategory.COMISIONES_BROKERS,
        ComparaTasasCategory.CREDITOS_PRESTAMOS,
        ComparaTasasCategory.COBRO_REMESAS,
    )
    return if (lowerIsBetter) {
        items.sortedWith(
            compareBy<ComparaTasasItem> { it.ratePercent ?: Double.MAX_VALUE }
                .thenBy { it.entityName },
        )
    } else {
        items.sortedWith(
            compareByDescending<ComparaTasasItem> { it.ratePercent ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.entityName },
        )
    }
}

internal fun categoryAccent(category: ComparaTasasCategory): Color = when (category) {
    ComparaTasasCategory.CUENTAS_REMUNERADAS -> Emerald
    ComparaTasasCategory.FONDOS_PESOS -> Violet
    ComparaTasasCategory.PLAZOS_FIJOS -> Amber
    ComparaTasasCategory.INVERSIONES_USD -> Sky
    ComparaTasasCategory.CRIPTO -> Amber
    ComparaTasasCategory.COMISIONES_BROKERS -> Sky
    ComparaTasasCategory.CREDITOS_PRESTAMOS -> Rose
    ComparaTasasCategory.COBRO_REMESAS -> Sky
}

@Composable
private fun ComparaTasasItemRow(
    item: ComparaTasasItem,
    accent: Color,
) {
    val locale = Locale.forLanguageTag("es-AR")
    val primaryBadge = item.metricLabel.ifBlank {
        item.ratePercent?.let { "TNA " + String.format(locale, "%.2f%%", it) } ?: "—"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.entityName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.entityType.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    TagPill(item.entityType, Sky)
                }
            }
            if (!item.productName.equals(item.entityName, ignoreCase = true)) {
                Text(
                    item.productName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val metaParts = buildList {
                item.teaPercent?.let { add("TEA ${String.format(locale, "%.2f%%", it)}") }
                if (item.categoryName == ComparaTasasCategory.CUENTAS_REMUNERADAS.name) {
                    if (item.capPesos != null && item.capPesos > 0L) {
                        add("Tope ${MoneyFormatter.arsWhole(item.capPesos * 100L)}")
                    } else if (item.teaPercent != null) {
                        add("Sin tope")
                    }
                }
                if (item.conditions.isNotBlank()) add(item.conditions)
            }
            if (metaParts.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    metaParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        MetricPill(
            text = primaryBadge,
            color = accent,
        )
    }
}
