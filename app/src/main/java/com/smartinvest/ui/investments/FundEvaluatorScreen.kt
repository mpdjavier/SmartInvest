package com.smartinvest.ui.investments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.domain.engine.FundStabilityEngine
import com.smartinvest.domain.model.FundBenchmarkComparison
import com.smartinvest.domain.model.FundCatalogEntry
import com.smartinvest.domain.model.FundEvaluation
import com.smartinvest.domain.model.FundFilterOption
import com.smartinvest.domain.model.FundOverlay
import com.smartinvest.domain.model.FundReturnHorizon
import com.smartinvest.domain.model.FundSimulation
import com.smartinvest.domain.model.FundSortOption
import com.smartinvest.domain.model.FundStabilityTier
import com.smartinvest.domain.model.FundUniverse
import com.smartinvest.ui.components.ChipRow
import com.smartinvest.ui.components.DropdownField
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.HeroAmountText
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.MetricPill
import com.smartinvest.ui.components.OverlayChart
import com.smartinvest.ui.components.PortfolioChart
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.StatTile
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Orange
import com.smartinvest.ui.theme.PlexMono
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.theme.TextMuted
import com.smartinvest.ui.util.Fmt
import java.time.LocalDate
import kotlin.math.roundToLong

@Composable
fun FundEvaluatorScreen(
    viewModel: FundEvaluatorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Evaluador FCI", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Rentabilidad y estabilidad como parámetros independientes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
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

        state.message?.let { message ->
            item {
                Column {
                    InfoBanner(message, color = Sky)
                    TextButton(onClick = viewModel::clearMessage) { Text("Cerrar") }
                }
            }
        }

        item {
            InfoBanner(
                text = "Ordená por rentabilidad, por estabilidad o por caída, y poné un piso " +
                    "de estabilidad opcional. Podés acotar el análisis con un rango de fechas y " +
                    "superponer dos FCI para comparar la misma serie. Cuotapartes de CAFCI/CNV " +
                    "vía compararfondos.com.ar.",
                color = Sky,
            )
        }

        if (state.isLoading) {
            item {
                SmartCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Emerald,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Cargando catálogo e historial diario…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        state.benchmark?.let { benchmark ->
            item {
                BenchmarkCard(
                    benchmark = benchmark,
                    dataAsOf = state.dataAsOf,
                )
            }
        }

        item {
            FiltersCard(
                universe = state.universe,
                catalogSize = state.catalogSize,
                windowDays = state.windowDays,
                hasCustomRange = state.hasCustomRange,
                horizon = state.horizon,
                sort = state.sort,
                filter = state.filter,
                minStability = state.minStability,
                minimumLabel = state.minimumLabel,
                onUniverse = viewModel::selectUniverse,
                onWindow = viewModel::selectWindow,
                onHorizon = viewModel::selectHorizon,
                onSort = viewModel::selectSort,
                onFilter = viewModel::selectFilter,
                onMinStability = viewModel::setMinStability,
            )
        }

        if (state.customRangeOpen || state.hasCustomRange) {
            item {
                DateRangeCard(
                    startText = state.rangeStartText,
                    endText = state.rangeEndText,
                    error = state.rangeError,
                    hasRange = state.hasCustomRange,
                    onStartChange = viewModel::onRangeStartChange,
                    onEndChange = viewModel::onRangeEndChange,
                    onApply = viewModel::applyRange,
                    onClear = viewModel::clearRange,
                    onQuickRange = viewModel::applyQuickRange,
                    onClose = viewModel::closeCustomRange,
                )
            }
        }

        if (state.selectedOutOfRange) {
            item {
                Column {
                    InfoBanner(
                        "La clase elegida no tiene cuotapartes en el rango. Probá ampliar las fechas.",
                        color = Amber,
                    )
                    TextButton(onClick = viewModel::clearSelection) { Text("Volver al ranking") }
                }
            }
        }

        item {
            SectionHeader(
                title = if (state.universe == FundUniverse.WATCHLIST) {
                    "Ranking del seguimiento"
                } else {
                    "Ranking de todo el catálogo"
                },
                subtitle = rankingSubtitle(state),
                actionLabel = if (state.universe == FundUniverse.WATCHLIST) "Restaurar" else null,
                onAction = if (state.universe == FundUniverse.WATCHLIST) {
                    viewModel::resetWatchlist
                } else {
                    null
                },
            )
        }

        if (state.universe == FundUniverse.CATALOG && state.ranked.isNotEmpty()) {
            item {
                Text(
                    "Tocá una clase para cargar su historial diario, ver el gráfico y simular. " +
                        "El ranking usa las métricas de 90 días publicadas en el catálogo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // A selected fund can fall outside the current filter, or be the benchmark
        // (excluded from the ranking). Keep its detail visible above the list.
        val detached = state.selected?.takeIf { selected ->
            state.ranked.none { it.name.equals(selected.name, ignoreCase = true) }
        }
        if (detached != null && !state.isLoading) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (detached.name.equals(FundStabilityEngine.BENCHMARK_NAME, ignoreCase = true)) {
                            "REFERENCIA (FUERA DEL RANKING)"
                        } else {
                            "DETALLE FUERA DEL RANKING ACTUAL"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FundDetailGroup(
                        evaluation = detached,
                        comparison = state.selectedComparison,
                        amountText = state.simulationAmountText,
                        simulation = state.simulation,
                        horizon = state.horizon,
                        detailLoading = state.detailLoading,
                        overlayName = state.overlayName,
                        overlay = state.overlay,
                        overlayCandidates = state.overlayCandidates,
                        isFollowed = isFollowed(state.watchlist, detached.name),
                        onAmountChange = viewModel::onSimulationAmountChange,
                        onSimulate = viewModel::simulate,
                        onFollow = viewModel::followSelected,
                        onRemove = { viewModel.removeFund(detached.name) },
                        onSelectOverlay = viewModel::selectOverlay,
                    )
                }
            }
        }

        if (!state.isLoading && state.ranked.isEmpty()) {
            item {
                val title: String
                val message: String
                when {
                    state.hasCustomRange && state.universe == FundUniverse.WATCHLIST -> {
                        title = "Sin datos en el rango elegido"
                        message = "Ningún fondo del seguimiento tiene cuotapartes en esas fechas. Ampliá el rango o volvé a una ventana fija."
                    }
                    state.universe == FundUniverse.CATALOG && state.catalogSize == 0 -> {
                        title = "Catálogo no disponible"
                        message = "No se pudo descargar el catálogo. Actualizá con el botón de arriba e intentá de nuevo."
                    }
                    state.hasMinimum -> {
                        title = "Ninguna clase alcanza ${state.minimumLabel}"
                        message = "Bajá el mínimo de estabilidad o ampliá el filtro para ver más resultados."
                    }
                    state.filter != FundFilterOption.ALL -> {
                        title = "Ningún fondo cumple el filtro"
                        message = "Probá con «Todos» o cambiá la ventana de análisis para ampliar el resultado."
                    }
                    state.universe == FundUniverse.CATALOG -> {
                        title = "Sin clases para mostrar"
                        message = "Actualizá el catálogo o probá con otro orden."
                    }
                    else -> {
                        title = "Sin fondos para comparar"
                        message = "Agregá clases desde el buscador del catálogo para verlas en el ranking."
                    }
                }
                EmptyState(
                    icon = Icons.Rounded.Search,
                    title = title,
                    message = message,
                )
            }
        }

        items(state.ranked, key = { it.name }) { evaluation ->
            val isSelected = evaluation.name.equals(state.selectedName, ignoreCase = true)
            val detail = if (isSelected) state.selected else null
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FundRankRow(
                    evaluation = evaluation,
                    selected = isSelected,
                    returnLabel = returnLabel(evaluation, state.horizon),
                    summary = evaluation.fromSummary,
                    onSelect = { viewModel.selectFund(evaluation.name) },
                )
                if (detail != null) {
                    FundDetailGroup(
                        evaluation = detail,
                        comparison = state.selectedComparison,
                        amountText = state.simulationAmountText,
                        simulation = state.simulation,
                        horizon = state.horizon,
                        detailLoading = state.detailLoading,
                        overlayName = state.overlayName,
                        overlay = state.overlay,
                        overlayCandidates = state.overlayCandidates,
                        isFollowed = isFollowed(state.watchlist, detail.name),
                        onAmountChange = viewModel::onSimulationAmountChange,
                        onSimulate = viewModel::simulate,
                        onFollow = viewModel::followSelected,
                        onRemove = { viewModel.removeFund(detail.name) },
                        onSelectOverlay = viewModel::selectOverlay,
                    )
                }
            }
        }

        item {
            CatalogSearchCard(
                query = state.query,
                results = state.searchResults,
                onQueryChange = viewModel::onQueryChange,
                onAdd = viewModel::addFund,
            )
        }

        item {
            Text(
                state.attribution,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun rankingSubtitle(state: FundEvaluatorUiState): String = buildString {
    if (state.universe == FundUniverse.WATCHLIST) {
        append("${state.ranked.size} fondos")
        if (state.hasCustomRange) {
            append(" · rango ")
            append(rangeLabel(state.rangeStart, state.rangeEnd))
        } else {
            append(" · ventana ")
            append(windowLabel(state.windowDays))
        }
    } else {
        append("${state.ranked.size}")
        if (state.catalogSize > 0) append(" de ${state.catalogSize}")
        append(" clases · renta ")
        append(state.horizon.label.lowercase())
        append(" · estabilidad 90 días")
    }
    append(" · ${state.sort.label}")
    if (state.hasMinimum) append(" · est. ${state.minimumLabel}")
    if (state.filter != FundFilterOption.ALL) append(" · ${state.filter.label}")
}

@Composable
private fun BenchmarkCard(
    benchmark: FundEvaluation,
    dataAsOf: LocalDate?,
) {
    val metrics = benchmark.metrics
    SmartCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "REFERENCIA",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    benchmark.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TagPill(benchmark.stability.tier.label, tierColor(benchmark.stability.tier))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            HeroAmountText(
                text = "${formatScore(benchmark.stability.score)} / 100",
                color = tierColor(benchmark.stability.tier),
                modifier = Modifier.weight(1f),
            )
            Text(
                "score de estabilidad",
                modifier = Modifier.padding(bottom = 4.dp, start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "Rent. ${analysisShort(benchmark)}",
                value = Fmt.signedPercent(metrics.totalReturnPercent),
                caption = "${metrics.observations} ruedas",
                accent = returnColor(metrics.totalReturnPercent),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Máx. caída",
                value = Fmt.signedPercent(metrics.maxDrawdownPercent),
                caption = "peor pico a valle",
                accent = returnColor(metrics.maxDrawdownPercent),
                modifier = Modifier.weight(1f),
                help = "La mayor caída desde un máximo previo dentro de la ventana. Es el dato que más pesa en el score.",
            )
            StatTile(
                label = "Volatilidad",
                value = Fmt.percent(metrics.annualizedVolatilityPercent),
                caption = "anualizada",
                accent = Amber,
                modifier = Modifier.weight(1f),
                help = "Desvío estándar de los retornos diarios, anualizado a 252 ruedas.",
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetricPill(
                text = "Días positivos ${Fmt.percent(metrics.positiveDayRatioPercent, 0)}",
                color = returnColor(metrics.positiveDayRatioPercent - 50.0),
            )
            Spacer(Modifier.weight(1f))
            if (dataAsOf != null) {
                Text(
                    "Datos al ${Fmt.shortDate(dataAsOf)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun FiltersCard(
    universe: FundUniverse,
    catalogSize: Int,
    windowDays: Int,
    hasCustomRange: Boolean,
    horizon: FundReturnHorizon,
    sort: FundSortOption,
    filter: FundFilterOption,
    minStability: Double,
    minimumLabel: String,
    onUniverse: (FundUniverse) -> Unit,
    onWindow: (Int) -> Unit,
    onHorizon: (FundReturnHorizon) -> Unit,
    onSort: (FundSortOption) -> Unit,
    onFilter: (FundFilterOption) -> Unit,
    onMinStability: (Double) -> Unit,
) {
    SmartCard {
        Text(
            "UNIVERSO",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ChipRow(
            options = FundUniverse.entries.toList(),
            selected = universe,
            label = { option ->
                if (option == FundUniverse.CATALOG && catalogSize > 0) {
                    "${option.label} · $catalogSize"
                } else {
                    option.label
                }
            },
            onSelect = onUniverse,
        )
        Spacer(Modifier.height(12.dp))

        if (universe == FundUniverse.WATCHLIST) {
            Text(
                "VENTANA DE ANÁLISIS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            ChipRow(
                options = FundStabilityEngine.WINDOWS + FundStabilityEngine.CUSTOM_WINDOW,
                selected = if (hasCustomRange) {
                    FundStabilityEngine.CUSTOM_WINDOW
                } else {
                    windowDays
                },
                label = { option ->
                    if (option == FundStabilityEngine.CUSTOM_WINDOW) {
                        "Rango de fechas"
                    } else {
                        windowLabel(option)
                    }
                },
                onSelect = onWindow,
            )
        } else {
            Text(
                "RENTABILIDAD A MOSTRAR",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            ChipRow(
                options = FundReturnHorizon.entries.toList(),
                selected = horizon,
                label = { it.label },
                onSelect = onHorizon,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ESTABILIDAD MÍNIMA",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                minimumLabel,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = PlexMono),
                color = if (minStability > 0.0) Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        Slider(
            value = minStability.toFloat(),
            onValueChange = { onMinStability(it.toDouble()) },
            valueRange = 0f..100f,
            steps = 19,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (minStability > 0.0) {
                "Solo se muestran clases con score de estabilidad ≥ ${minStability.toInt()}."
            } else {
                "Sin piso: se muestran todas las clases, incluso las volátiles."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "ORDENAR POR",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ChipRow(
            options = FundSortOption.entries.toList(),
            selected = sort,
            label = { it.label },
            onSelect = onSort,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "FILTRAR",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ChipRow(
            options = FundFilterOption.entries.toList(),
            selected = filter,
            label = { it.label },
            onSelect = onFilter,
        )
        if (universe == FundUniverse.CATALOG) {
            Spacer(Modifier.height(10.dp))
            Text(
                "En el catálogo, la estabilidad se estima con volatilidad, máxima caída y " +
                    "días positivos a 90 días. La rentabilidad usa el período elegido." +
                    if (hasCustomRange) {
                        " El rango de fechas personalizado se aplica al seguimiento y al detalle, " +
                            "no al ranking completo del catálogo."
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateRangeCard(
    startText: String,
    endText: String,
    error: String?,
    hasRange: Boolean,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    onQuickRange: (Int?) -> Unit,
    onClose: () -> Unit,
) {
    SmartCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Rango de fechas", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Aplica al ranking, al gráfico, a la comparación y al simulador",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClose) { Text("Cerrar") }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = startText,
                onValueChange = onStartChange,
                label = { Text("Desde") },
                placeholder = { Text("dd/MM/aaaa") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = endText,
                onValueChange = onEndChange,
                label = { Text("Hasta") },
                placeholder = { Text("dd/MM/aaaa") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f),
            )
        }
        error?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = Rose,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onApply) { Text("Aplicar") }
            TextButton(
                onClick = onClear,
                enabled = hasRange || startText.isNotBlank() || endText.isNotBlank(),
            ) {
                Text("Limpiar")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "ATAJOS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onQuickRange(null) }, label = { Text("Año actual") })
            AssistChip(onClick = { onQuickRange(90) }, label = { Text("Últimos 90 días") })
            AssistChip(onClick = { onQuickRange(365) }, label = { Text("Últimos 12 meses") })
        }
        if (hasRange) {
            Spacer(Modifier.height(6.dp))
            Text(
                "El rango activo reemplaza a la ventana fija. Si un fondo no tiene cuotapartes " +
                    "en esas fechas, no aparece en el ranking ni en la superposición.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FundRankRow(
    evaluation: FundEvaluation,
    selected: Boolean,
    returnLabel: String,
    summary: Boolean,
    onSelect: () -> Unit,
) {
    val metrics = evaluation.metrics
    val tier = evaluation.stability.tier
    SmartCard(
        modifier = Modifier.clickable(onClick = onSelect),
        containerColor = if (selected) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    evaluation.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    managerLine(evaluation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    scoreText(evaluation),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = PlexMono,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = tierColor(tier),
                    maxLines = 1,
                    softWrap = false,
                )
                TagPill(tier.label, tierColor(tier))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniMetric(
                label = returnLabel,
                value = returnText(evaluation),
                color = returnColor(
                    if (metrics.returnAvailable) metrics.totalReturnPercent else 0.0,
                ),
                modifier = Modifier.weight(1f),
            )
            MiniMetric(
                label = if (summary) "Máx. caída 90d" else "Máx. caída",
                value = if (evaluation.stability.hasData) {
                    Fmt.signedPercent(metrics.maxDrawdownPercent)
                } else {
                    "—"
                },
                color = if (evaluation.stability.hasData) {
                    returnColor(metrics.maxDrawdownPercent)
                } else {
                    TextMuted
                },
                modifier = Modifier.weight(1f),
            )
            MiniMetric(
                label = if (summary) "Volat. 90d" else "Volatilidad",
                value = if (evaluation.stability.hasData) {
                    Fmt.percent(metrics.annualizedVolatilityPercent)
                } else {
                    "—"
                },
                color = if (evaluation.stability.hasData) Amber else TextMuted,
                modifier = Modifier.weight(1f),
            )
            MiniMetric(
                label = "Días +",
                value = if (evaluation.stability.hasData) {
                    Fmt.percent(metrics.positiveDayRatioPercent, 0)
                } else {
                    "—"
                },
                color = if (evaluation.stability.hasData) Emerald else TextMuted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MiniMetric(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono),
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun FundDetailGroup(
    evaluation: FundEvaluation,
    comparison: FundBenchmarkComparison?,
    amountText: String,
    simulation: FundSimulation?,
    horizon: FundReturnHorizon,
    detailLoading: Boolean,
    overlayName: String?,
    overlay: FundOverlay?,
    overlayCandidates: List<String>,
    isFollowed: Boolean,
    onAmountChange: (String) -> Unit,
    onSimulate: () -> Unit,
    onFollow: () -> Unit,
    onRemove: () -> Unit,
    onSelectOverlay: (String?) -> Unit,
) {
    if (evaluation.fromSummary) {
        FundSummaryDetailCard(
            evaluation = evaluation,
            horizon = horizon,
            detailLoading = detailLoading,
        )
    } else {
        FundDetailCard(evaluation = evaluation)
        FundOverlayCard(
            evaluation = evaluation,
            candidates = overlayCandidates,
            overlayName = overlayName,
            overlay = overlay,
            onSelect = onSelectOverlay,
        )
        FundMetricsCard(evaluation = evaluation)
        if (comparison != null) {
            ComparisonCard(evaluation = evaluation, comparison = comparison)
        }
        SimulatorCard(
            evaluation = evaluation,
            amountText = amountText,
            simulation = simulation,
            onAmountChange = onAmountChange,
            onSimulate = onSimulate,
        )
    }
    if (!evaluation.name.equals(FundStabilityEngine.BENCHMARK_NAME, ignoreCase = true)) {
        if (isFollowed) {
            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Quitar del seguimiento", maxLines = 1, softWrap = false)
            }
        } else {
            OutlinedButton(
                onClick = onFollow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                Icon(Icons.Rounded.AddCircleOutline, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Agregar al seguimiento", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun FundOverlayCard(
    evaluation: FundEvaluation,
    candidates: List<String>,
    overlayName: String?,
    overlay: FundOverlay?,
    onSelect: (String?) -> Unit,
) {
    SmartCard {
        SectionHeader(
            title = "Superponer dos FCI",
            subtitle = "Ambas series se normalizan a 100 al inicio del rango",
        )
        Spacer(Modifier.height(10.dp))

        if (candidates.isEmpty()) {
            Text(
                "Agregá otra clase al seguimiento (o tocá una del catálogo para cargar su " +
                    "historial) y después elegila acá para superponerla contra ${evaluation.name}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SmartCard
        }

        DropdownField(
            label = "Comparar con",
            selected = overlayName ?: OVERLAY_NONE,
            options = listOf(OVERLAY_NONE) + candidates,
            optionLabel = { it },
            onSelect = { name -> onSelect(if (name == OVERLAY_NONE) null else name) },
        )

        if (overlayName == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Elegí un fondo de referencia para ver las dos curvas en el mismo gráfico y " +
                    "la diferencia de retorno del período.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SmartCard
        }

        if (overlay == null) {
            Spacer(Modifier.height(8.dp))
            InfoBanner(
                "No hay fechas en común para $overlayName en el rango elegido. Ampliá el " +
                    "rango o volvé a una ventana fija.",
                color = Amber,
            )
            return@SmartCard
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "${rangeLabel(overlay.startDate, overlay.endDate)} · ${overlay.observations} ruedas",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OverlayChart(
            primary = overlay.points.map { it.primaryIndex },
            secondary = overlay.points.map { it.secondaryIndex },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            primaryColor = Emerald,
            secondaryColor = Sky,
        )
        Spacer(Modifier.height(10.dp))
        OverlayLegendRow(
            color = Emerald,
            name = overlay.primaryName,
            returnPercent = overlay.primaryReturnPercent,
            drawdownPercent = overlay.primaryDrawdownPercent,
            volatilityPercent = overlay.primaryVolatilityPercent,
        )
        Spacer(Modifier.height(6.dp))
        OverlayLegendRow(
            color = Sky,
            name = overlay.secondaryName,
            returnPercent = overlay.secondaryReturnPercent,
            drawdownPercent = overlay.secondaryDrawdownPercent,
            volatilityPercent = overlay.secondaryVolatilityPercent,
        )
        ThinDivider()
        KeyValueRow(
            "Diferencia de retorno",
            formatPp(overlay.differencePp),
            valueColor = returnColor(overlay.differencePp),
            strong = true,
        )
        Spacer(Modifier.height(8.dp))
        InfoBanner(
            overlay.verdict,
            color = if (overlay.differencePp >= 0.0) Emerald else Amber,
        )
        if (overlay.currencyMismatch) {
            Spacer(Modifier.height(6.dp))
            InfoBanner(
                "Los fondos están en monedas distintas: la serie se normaliza igual, pero el " +
                    "retorno no descuenta el tipo de cambio.",
                color = Amber,
            )
        }
    }
}

@Composable
private fun OverlayLegendRow(
    color: Color,
    name: String,
    returnPercent: Double,
    drawdownPercent: Double,
    volatilityPercent: Double,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "●",
            style = MaterialTheme.typography.titleSmall,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Caída ${Fmt.signedPercent(drawdownPercent, 2)} · " +
                    "volat. ${Fmt.percent(volatilityPercent, 1)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            Fmt.signedPercent(returnPercent, 2),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = PlexMono,
                fontWeight = FontWeight.SemiBold,
            ),
            color = returnColor(returnPercent),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun FundSummaryDetailCard(
    evaluation: FundEvaluation,
    horizon: FundReturnHorizon,
    detailLoading: Boolean,
) {
    val metrics = evaluation.metrics
    SmartCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    evaluation.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    managerLine(evaluation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TagPill(evaluation.stability.tier.label, tierColor(evaluation.stability.tier))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            HeroAmountText(
                text = scoreText(evaluation),
                color = tierColor(evaluation.stability.tier),
                modifier = Modifier.weight(1f),
            )
            Text(
                "estabilidad estimada",
                modifier = Modifier.padding(bottom = 4.dp, start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "Rent. ${horizon.shortLabel}",
                value = returnText(evaluation),
                caption = "catálogo",
                accent = returnColor(
                    if (metrics.returnAvailable) metrics.totalReturnPercent else 0.0,
                ),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Máx. caída",
                value = if (evaluation.stability.hasData) {
                    Fmt.signedPercent(metrics.maxDrawdownPercent)
                } else {
                    "—"
                },
                caption = "90 días",
                accent = if (evaluation.stability.hasData) {
                    returnColor(metrics.maxDrawdownPercent)
                } else {
                    TextMuted
                },
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Volatilidad",
                value = if (evaluation.stability.hasData) {
                    Fmt.percent(metrics.annualizedVolatilityPercent)
                } else {
                    "—"
                },
                caption = "90 días",
                accent = if (evaluation.stability.hasData) Amber else TextMuted,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        if (evaluation.stability.hasData) {
            MetricPill(
                text = "Días positivos ${Fmt.percent(metrics.positiveDayRatioPercent, 0)}",
                color = returnColor(metrics.positiveDayRatioPercent - 50.0),
            )
        } else {
            InfoBanner(
                text = "Esta clase no publica volatilidad, caída y días positivos a 90 días. " +
                    "No se le asigna score de estabilidad.",
                color = Amber,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (detailLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Emerald,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (detailLoading) {
                    "Cargando historial diario para el gráfico y el simulador…"
                } else {
                    "No se pudo cargar el historial diario. Tocá la fila de nuevo para reintentar."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FundDetailCard(evaluation: FundEvaluation) {
    val metrics = evaluation.metrics
    val chartValues = remember(
        evaluation.history,
        evaluation.windowDays,
        evaluation.rangeStart,
        evaluation.rangeEnd,
    ) {
        FundStabilityEngine.evaluationPoints(evaluation)
            .map { (it.value * 10_000.0).roundToLong() }
    }
    SmartCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    evaluation.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    managerLine(evaluation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TagPill(evaluation.stability.tier.label, tierColor(evaluation.stability.tier))
        }
        Spacer(Modifier.height(12.dp))
        PortfolioChart(
            values = chartValues,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            lineColor = returnColor(metrics.totalReturnPercent),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "Rent. ${analysisShort(evaluation)}",
                value = Fmt.signedPercent(metrics.totalReturnPercent),
                caption = evaluation.lastDate?.let { "al ${Fmt.shortDate(it)}" } ?: "serie diaria",
                accent = returnColor(metrics.totalReturnPercent),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Máx. caída",
                value = Fmt.signedPercent(metrics.maxDrawdownPercent),
                caption = "peor pico a valle",
                accent = returnColor(metrics.maxDrawdownPercent),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Volatilidad",
                value = Fmt.percent(metrics.annualizedVolatilityPercent),
                caption = "anualizada",
                accent = Amber,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FundMetricsCard(evaluation: FundEvaluation) {
    val metrics = evaluation.metrics
    val scope = if (evaluation.hasCustomRange) {
        "Rango ${analysisLabel(evaluation)}"
    } else {
        "Ventana ${windowLabel(evaluation.windowDays)}"
    }
    SmartCard {
        SectionHeader(
            title = "Cómo se comportó",
            subtitle = "$scope · ${metrics.observations} observaciones",
        )
        Spacer(Modifier.height(8.dp))
        KeyValueRow(
            "Peor día",
            Fmt.signedPercent(metrics.worstDayPercent),
            valueColor = returnColor(metrics.worstDayPercent),
        )
        KeyValueRow(
            "Mejor día",
            Fmt.signedPercent(metrics.bestDayPercent),
            valueColor = returnColor(metrics.bestDayPercent),
        )
        KeyValueRow("Días positivos", Fmt.percent(metrics.positiveDayRatioPercent), valueColor = Emerald)
        KeyValueRow(
            "Score de estabilidad",
            "${formatScore(evaluation.stability.score)} / 100",
            valueColor = tierColor(evaluation.stability.tier),
            strong = true,
        )
        ThinDivider()
        Text(
            "El score combina 40% máxima caída, 28% volatilidad, 14% peor día y 18% consistencia. " +
                "La rentabilidad no suma puntos: se informa por separado.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComparisonCard(
    evaluation: FundEvaluation,
    comparison: FundBenchmarkComparison,
) {
    val good = comparison.saferThanBenchmark || comparison.stabilityDifference >= 0.0
    val color = if (good) Emerald else Amber
    SmartCard {
        SectionHeader(
            title = "Vs. Adcap Balanceado III",
            subtitle = "Mismo rango: ${analysisLabel(evaluation)}",
        )
        Spacer(Modifier.height(8.dp))
        Text(
            comparison.verdict,
            style = MaterialTheme.typography.titleSmall,
            color = color,
        )
        Spacer(Modifier.height(8.dp))
        KeyValueRow(
            "Diferencia de rentabilidad",
            formatPp(comparison.returnDifferencePp),
            valueColor = returnColor(comparison.returnDifferencePp),
        )
        KeyValueRow(
            "Diferencia de máxima caída",
            formatPp(comparison.drawdownDifferencePp),
            valueColor = returnColor(comparison.drawdownDifferencePp),
        )
        KeyValueRow(
            "Diferencia de volatilidad",
            formatPp(comparison.volatilityDifferencePp),
            valueColor = if (comparison.volatilityDifferencePp <= 0.0) Emerald else Rose,
        )
        KeyValueRow(
            "Diferencia de score",
            "${if (comparison.stabilityDifference >= 0) "+" else ""}${formatScore(comparison.stabilityDifference)} pts",
            valueColor = color,
            strong = true,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (good) {
                "En esta ventana ${evaluation.name} cae y oscila igual o menos que la referencia."
            } else {
                "En esta ventana la referencia se movió con menos saltos que ${evaluation.name}."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SimulatorCard(
    evaluation: FundEvaluation,
    amountText: String,
    simulation: FundSimulation?,
    onAmountChange: (String) -> Unit,
    onSimulate: () -> Unit,
) {
    SmartCard {
        val isUsd = evaluation.currency.equals("USD", ignoreCase = true)
        val amountCurrency = if (isUsd) "USD" else "ARS"
        val finalText = if (isUsd) Fmt.usd(simulation?.finalCents ?: 0L) else Fmt.ars(simulation?.finalCents ?: 0L)
        val signedText: (Long) -> String = if (isUsd) Fmt::signedUsd else Fmt::signedArs
        SectionHeader(
            title = "Simulador del fondo",
            subtitle = "Monto inicial en $amountCurrency · ${analysisLabel(evaluation)}",
        )
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = amountText,
                onValueChange = onAmountChange,
                label = { Text("Monto inicial ($amountCurrency)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onSimulate,
                modifier = Modifier.height(44.dp),
            ) {
                Text("Simular", maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (simulation != null) {
            Text(
                "VALOR FINAL ESTIMADO",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HeroAmountText(
                text = finalText,
                color = returnColor(simulation.gainCents.toDouble()),
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetricPill(
                    text = Fmt.signedPercent(simulation.returnPercent, 2),
                    color = returnColor(simulation.gainCents.toDouble()),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Ganancia ${signedText(simulation.gainCents)}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono),
                    color = returnColor(simulation.gainCents.toDouble()),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            PortfolioChart(
                values = simulation.values,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                lineColor = returnColor(simulation.gainCents.toDouble()),
            )
            Spacer(Modifier.height(8.dp))
            KeyValueRow(
                "Máx. caída del período",
                Fmt.signedPercent(simulation.maxDrawdownPercent),
                valueColor = returnColor(simulation.maxDrawdownPercent),
            )
            KeyValueRow(
                "Período simulado",
                buildString {
                    simulation.startDate?.let { append(Fmt.shortDate(it)) }
                    append(" → ")
                    simulation.endDate?.let { append(Fmt.shortDate(it)) }
                },
            )
            KeyValueRow("Ruedas usadas", "${simulation.values.size}")
        } else {
            Text(
                "Ingresá un monto y tocá Simular para ver la evolución real del fondo en esta ventana, " +
                    "incluida su peor caída.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CatalogSearchCard(
    query: String,
    results: List<FundCatalogEntry>,
    onQueryChange: (String) -> Unit,
    onAdd: (FundCatalogEntry) -> Unit,
) {
    SmartCard {
        SectionHeader(
            title = "Agregar del catálogo",
            subtitle = "2.300+ clases de todas las gestoras",
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Buscar fondo, gestora o categoría") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        when {
            query.trim().length < 2 -> Text(
                "Escribí al menos 2 letras. También podés buscar por gestora (Adcap, Balanz, Delta…).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            results.isEmpty() -> Text(
                "Sin coincidencias nuevas. Revisá la ortografía o probá con el nombre base del fondo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                Text(
                    "${results.size} coincidencias",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                results.forEach { entry ->
                    CatalogResultRow(entry = entry, onAdd = { onAdd(entry) })
                }
            }
        }
    }
}

@Composable
private fun CatalogResultRow(
    entry: FundCatalogEntry,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    catalogSubtitle(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                entry.return365?.let { ret ->
                    Text(
                        " · 12m ${Fmt.signedPercent(ret)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = returnColor(ret),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.Add, contentDescription = "Agregar ${entry.name}", tint = Emerald)
        }
    }
}

private const val OVERLAY_NONE = "Ninguno"

private fun isFollowed(watchlist: List<String>, name: String): Boolean =
    watchlist.any { it.equals(name, ignoreCase = true) }

private fun rangeLabel(start: LocalDate?, end: LocalDate?): String = when {
    start != null && end != null -> "${Fmt.shortDate(start)} → ${Fmt.shortDate(end)}"
    start != null -> "desde ${Fmt.shortDate(start)}"
    end != null -> "hasta ${Fmt.shortDate(end)}"
    else -> "sin rango"
}

private fun analysisLabel(evaluation: FundEvaluation): String =
    if (evaluation.hasCustomRange) {
        rangeLabel(evaluation.rangeStart, evaluation.rangeEnd)
    } else {
        windowLabel(evaluation.windowDays)
    }

private fun analysisShort(evaluation: FundEvaluation): String =
    if (evaluation.hasCustomRange) "rango" else windowShort(evaluation.windowDays)

private fun returnLabel(evaluation: FundEvaluation, horizon: FundReturnHorizon): String =
    if (evaluation.fromSummary) {
        "Rent. ${horizon.shortLabel}"
    } else {
        "Rent. ${analysisShort(evaluation)}"
    }

private fun returnText(evaluation: FundEvaluation): String =
    if (evaluation.metrics.returnAvailable) {
        Fmt.signedPercent(evaluation.metrics.totalReturnPercent)
    } else {
        "—"
    }

private fun scoreText(evaluation: FundEvaluation): String =
    if (evaluation.stability.hasData) formatScore(evaluation.stability.score) else "—"

private fun managerLine(evaluation: FundEvaluation): String = buildString {
    if (evaluation.manager.isNotBlank()) append(evaluation.manager)
    if (evaluation.type.isNotBlank()) {
        if (isNotEmpty()) append(" · ")
        append(typeLabel(evaluation.type))
    }
    if (evaluation.currency.isNotBlank()) {
        if (isNotEmpty()) append(" · ")
        append(evaluation.currency)
    }
}

private fun catalogSubtitle(entry: FundCatalogEntry): String = buildString {
    if (entry.manager.isNotBlank()) append(entry.manager)
    if (entry.type.isNotBlank()) {
        if (isNotEmpty()) append(" · ")
        append(typeLabel(entry.type))
    }
    append(" · ")
    append(entry.currency)
}

private fun typeLabel(type: String): String = when (type.uppercase()) {
    "MM" -> "Money market"
    "RFP" -> "Renta fija pesos"
    "RF" -> "Renta fija"
    "RV" -> "Renta variable"
    "MIX" -> "Mixto"
    "MULTI" -> "Multimercado"
    "RT" -> "Retorno total"
    "FC" -> "Fondo cerrado"
    else -> type
}

private fun windowLabel(days: Int): String = if (days >= 365) "1 año" else "$days días"

private fun windowShort(days: Int): String = if (days >= 365) "12m" else "${days}d"

private fun formatScore(value: Double): String =
    String.format(java.util.Locale.forLanguageTag("es-AR"), "%.1f", value)

private fun formatPp(value: Double): String {
    val sign = if (value > 0) "+" else ""
    return sign + String.format(java.util.Locale.forLanguageTag("es-AR"), "%.2f pp", value)
}

private fun returnColor(value: Double): Color = when {
    value > 0.0001 -> Emerald
    value < -0.0001 -> Rose
    else -> com.smartinvest.ui.theme.TextPrimary
}

private fun tierColor(tier: FundStabilityTier): Color = when (tier) {
    FundStabilityTier.VERY_STABLE -> Emerald
    FundStabilityTier.STABLE -> Sky
    FundStabilityTier.MODERATE -> Amber
    FundStabilityTier.SENSITIVE -> Orange
    FundStabilityTier.VOLATILE -> Rose
    FundStabilityTier.NO_DATA -> TextMuted
}
