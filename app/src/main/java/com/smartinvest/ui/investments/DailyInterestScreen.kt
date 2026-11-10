@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.smartinvest.ui.investments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.smartinvest.domain.model.DailyInterestBreakdown
import com.smartinvest.domain.model.DailyInterestDay
import com.smartinvest.domain.model.InterestLine
import com.smartinvest.domain.model.InterestReconciliation
import com.smartinvest.domain.model.InterestSourceKind
import com.smartinvest.domain.model.InterestSourceSummary
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.usecase.ObserveInterestBreakdownUseCase
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.MonthSelector
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.StatTile
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.theme.Violet
import com.smartinvest.ui.util.Fmt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

data class DailyInterestUiState(
    val month: YearMonth = YearMonth.now(),
    val breakdown: DailyInterestBreakdown? = null,
    val isWorking: Boolean = false,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DailyInterestViewModel @Inject constructor(
    observeBreakdown: ObserveInterestBreakdownUseCase,
    private val investments: InvestmentRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val breakdownFlow = month.flatMapLatest { selected ->
        observeBreakdown(selected)
    }

    val uiState = combine(month, breakdownFlow, working, message) { m, breakdown, isWorking, msg ->
        DailyInterestUiState(
            month = m,
            breakdown = breakdown,
            isWorking = isWorking,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyInterestUiState())

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun nextMonth() {
        month.value = month.value.plusMonths(1)
    }

    fun goToCurrentMonth() {
        month.value = YearMonth.now()
    }

    fun refreshAll() {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = null
            investments.refreshDollarRates()
            val tnaResult = runCatching { investments.syncRates() }.getOrNull()
            val marketResult = runCatching { investments.syncMarketQuotes() }.getOrNull()
            investments.refreshSnapshot()
            message.value = buildString {
                append(marketResult?.message ?: "Cotizaciones sincronizadas")
                if (tnaResult != null) {
                    append(" · ")
                    append(tnaResult.message)
                }
            }
            working.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }
}

@Composable
fun DailyInterestScreen(
    viewModel: DailyInterestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val breakdown = state.breakdown

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Intereses día a día", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Base de cálculo por cuenta remunerada, letra, bono y FCI",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::refreshAll, enabled = !state.isWorking) {
                    if (state.isWorking) {
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

        item {
            MonthSelector(
                label = Fmt.month(state.month),
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
            )
        }

        if (state.month != YearMonth.now()) {
            item {
                TextButton(onClick = viewModel::goToCurrentMonth) {
                    Text("Volver al mes actual")
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

        item {
            SmartCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "ACUMULADO EN EL MES",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            Fmt.signedArs(breakdown?.totalArsCents ?: 0L),
                            style = MaterialTheme.typography.headlineLarge,
                            color = signedColor(breakdown?.totalArsCents ?: 0L),
                        )
                    }
                    TagPill(
                        text = if (breakdown?.hasMarketData == true) "Con datos de mercado" else "TNA / 365",
                        color = if (breakdown?.hasMarketData == true) Sky else Emerald,
                    )
                }
                ThinDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = "Promedio / día",
                        value = Fmt.signedArs(breakdown?.averageDailyArsCents ?: 0L),
                        caption = "${breakdown?.days?.size ?: 0} días con devengamiento",
                        accent = signedColor(breakdown?.averageDailyArsCents ?: 0L),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Fuentes activas",
                        value = (breakdown?.sources?.size ?: 0).toString(),
                        caption = "Billeteras y activos",
                        accent = Sky,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            InfoBanner(
                "Cómo se calcula cada línea:\n" +
                    "· Billeteras y activos con TNA: Base remunerada (hasta el tope) × TNA ÷ 365.\n" +
                    "· Instrumentos con cotización real (Rava / CAFCI): Cantidad × (Precio cierre − Precio anterior).",
                color = Sky,
            )
        }

        if (breakdown != null) {
            item {
                InterestReconciliationCard(breakdown.reconciliation)
            }
        }

        if (breakdown != null && breakdown.sources.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Resumen por fuente",
                    subtitle = "Contribución acumulada en ${Fmt.month(state.month)}",
                )
            }
            items(breakdown.sources, key = { it.sourceId }) { source ->
                InterestSourceCard(source)
            }
        }

        item {
            SectionHeader(
                title = "Desglose día por día",
                subtitle = "${breakdown?.days?.size ?: 0} jornadas calculadas",
            )
        }

        if (breakdown == null || breakdown.days.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Calculate,
                    title = "Sin intereses para mostrar",
                    message = "Cargá saldo y TNA en tus billeteras o configurá cantidad y fuente (Rava / CAFCI) en tus activos.",
                )
            }
        } else {
            items(breakdown.days, key = { it.date.toEpochDay() }) { day ->
                DailyInterestDayCard(day)
            }
        }
    }
}

@Composable
private fun InterestReconciliationCard(reconciliation: InterestReconciliation) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SmartCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "¿No coinciden los números?",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Compará la proyección diaria contra el acumulado del mes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Ocultar" else "Desglosar")
            }
        }
        if (!expanded) return@SmartCard

        ThinDivider()
        KeyValueRow("Días considerados", reconciliation.daysConsidered.toString())
        KeyValueRow(
            "TNA estimada por día",
            Fmt.ars(reconciliation.accrualPerDayArsCents),
        )
        KeyValueRow(
            "Proyección del mes (× días)",
            Fmt.ars(reconciliation.projectedMonthArsCents),
        )
        KeyValueRow(
            "Acumulado real del mes",
            Fmt.signedArs(reconciliation.totalArsCents),
            valueColor = signedColor(reconciliation.totalArsCents),
            strong = true,
        )
        ThinDivider()
        KeyValueRow(
            "Diferencia vs proyección",
            Fmt.signedArs(reconciliation.differenceVsProjectionArsCents),
            valueColor = signedColor(reconciliation.differenceVsProjectionArsCents),
        )
        KeyValueRow(
            "Explicado por mercado",
            Fmt.signedArs(reconciliation.marketTotalArsCents),
            valueColor = Sky,
        )
        KeyValueRow(
            "Sin explicar",
            Fmt.signedArs(reconciliation.unexplainedArsCents),
            valueColor = if (reconciliation.isFullyExplained) Emerald else Amber,
        )
        if (reconciliation.notes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            reconciliation.notes.forEach { note ->
                Text(
                    "· $note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun InterestSourceCard(source: InterestSourceSummary) {
    val color = kindColor(source.kind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(source.sourceName, style = MaterialTheme.typography.titleSmall)
                TagPill(source.kind.label, color)
                TagPill(
                    if (source.isMarketBased) "Mercado" else "TNA",
                    if (source.isMarketBased) Sky else Emerald,
                )
            }
            Text(
                "${source.basisLabel} · ${source.days} días",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Fmt.signedArs(source.totalArsCents),
                style = MaterialTheme.typography.titleSmall,
                color = signedColor(source.totalArsCents),
            )
            Text(
                "${Fmt.signedArs(source.averageDailyArsCents)} / día",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DailyInterestDayCard(day: DailyInterestDay) {
    SmartCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Fmt.longDate(day.date),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                Fmt.signedArs(day.totalArsCents),
                style = MaterialTheme.typography.titleMedium,
                color = signedColor(day.totalArsCents),
            )
        }
        ThinDivider()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            day.lines.forEach { line ->
                InterestLineRow(line)
            }
        }
    }
}

@Composable
private fun InterestLineRow(line: InterestLine) {
    val color = kindColor(line.kind)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TagPill(line.kind.label, color)
            Spacer(Modifier.width(6.dp))
            Text(
                line.sourceName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                Fmt.signedArs(line.dailyArsCents),
                style = MaterialTheme.typography.titleSmall,
                color = signedColor(line.dailyArsCents),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            line.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun signedColor(cents: Long): Color = if (cents >= 0L) Emerald else Rose

private fun kindColor(kind: InterestSourceKind): Color = when (kind) {
    InterestSourceKind.WALLET -> Sky
    InterestSourceKind.MONEY_MARKET -> Emerald
    InterestSourceKind.FCI -> Violet
    InterestSourceKind.LECAP -> Emerald
    InterestSourceKind.BOND -> Sky
    InterestSourceKind.CEDEAR -> Amber
    InterestSourceKind.OTHER -> Sky
}
