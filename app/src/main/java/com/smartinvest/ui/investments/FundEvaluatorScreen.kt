package com.smartinvest.ui.investments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.smartinvest.domain.model.FundReturnHorizon
import com.smartinvest.domain.model.FundSimulation
import com.smartinvest.domain.model.FundSortOption
import com.smartinvest.domain.model.FundStabilityTier
import com.smartinvest.domain.model.FundUniverse
import com.smartinvest.ui.components.ChipRow
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.HeroAmountText
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.MetricPill
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
                    "de estabilidad opcional. Podés comparar tu seguimiento o las " +
                    "2.300+ clases del catálogo. Cuotapartes de CAFCI/CNV vía compararfondos.com.ar.",
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
                    windowDays = state.windowDays,
                    dataAsOf = state.dataAsOf,
                )
            }
        }

        item {
            FiltersCard(
                universe = state.universe,
                catalogSize = state.catalogSize,
                windowDays = state.windowDays,
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
                        isFollowed = isFollowed(state.watchlist, detached.name),
                        onAmountChange = viewModel::onSimulationAmountChange,
                        onSimulate = viewModel::simulate,
                        onFollow = viewModel::followSelected,
                        onRemove = { viewModel.removeFund(detached.name) },
                    )
                }
            }
        }

        if (!state.isLoading && state.ranked.isEmpty()) {
            item {
                val title: String
                val message: String
                when {
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
                        isFollowed = isFollowed(state.watchlist, detail.name),
                        onAmountChange = viewModel::onSimulationAmountChange,
                        onSimulate = viewModel::simulate,
                        onFollow = viewModel::followSelected,
                        onRemove = { viewModel.removeFund(detail.name) },
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
        append(" · ventana ")
        append(windowLabel(state.windowDays))
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
    windowDays: Int,
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
                label = "Rent. ${windowShort(windowDays)}",
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
                options = FundStabilityEngine.WINDOWS,
                selected = windowDays,
                label = { windowLabel(it) },
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
                    "días positivos a 90 días. La rentabilidad usa el período elegido.",
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
    isFollowed: Boolean,
    onAmountChange: (String) -> Unit,
    onSimulate: () -> Unit,
    onFollow: () -> Unit,
    onRemove: () -> Unit,
) {
    if (evaluation.fromSummary) {
        FundSummaryDetailCard(
            evaluation = evaluation,
            horizon = horizon,
            detailLoading = detailLoading,
        )
    } else {
        FundDetailCard(evaluation = evaluation)
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
    val chartValues = remember(evaluation.history, evaluation.windowDays) {
        FundStabilityEngine.windowPoints(evaluation.history, evaluation.windowDays)
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
                label = "Rent. ${windowShort(evaluation.windowDays)}",
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
    SmartCard {
        SectionHeader(
            title = "Cómo se comportó",
            subtitle = "Ventana ${windowLabel(evaluation.windowDays)} · ${metrics.observations} observaciones",
        )
        Spacer(Modifier.height(8.dp))
        KeyValueRow("Peor día", Fmt.signedPercent(metrics.worstDayPercent), valueColor = Rose)
        KeyValueRow("Mejor día", Fmt.signedPercent(metrics.bestDayPercent), valueColor = Emerald)
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
            subtitle = "Misma ventana: ${windowLabel(comparison.windowDays)}",
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
            subtitle = "Monto inicial en $amountCurrency · ventana ${windowLabel(evaluation.windowDays)}",
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
            Text(
                catalogSubtitle(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.Add, contentDescription = "Agregar ${entry.name}", tint = Emerald)
        }
    }
}

private fun isFollowed(watchlist: List<String>, name: String): Boolean =
    watchlist.any { it.equals(name, ignoreCase = true) }

private fun returnLabel(evaluation: FundEvaluation, horizon: FundReturnHorizon): String =
    if (evaluation.fromSummary) "Rent. ${horizon.shortLabel}" else "Rent. ${windowShort(evaluation.windowDays)}"

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
    entry.return365?.let {
        append(" · 12m ")
        append(Fmt.signedPercent(it))
    }
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
