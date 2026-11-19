package com.smartinvest.ui.rebalancing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.model.AllocationBucket
import com.smartinvest.domain.model.CarryRiskAssessment
import com.smartinvest.domain.model.CarryRiskBand
import com.smartinvest.domain.model.CarryRiskComponent
import com.smartinvest.domain.model.MacroRiskFactor
import com.smartinvest.domain.model.OrganicAllocationLeg
import com.smartinvest.domain.usecase.MacroRebalanceContext
import com.smartinvest.ui.components.AmountInput
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.LinearBar
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.StatTile
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Outline
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.theme.Violet
import com.smartinvest.ui.util.Fmt
import com.smartinvest.ui.util.MoneyVisibility
import kotlin.math.roundToLong

@Composable
fun RebalancingScreen(
    onOpenSettings: () -> Unit,
    onOpenRem: () -> Unit,
    viewModel: RebalancingViewModel = hiltViewModel(),
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
                    Text("Asistente macro", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Riesgo de carry y rebalanceo orgánico. Lectura semanal; decisión de " +
                            "flujo mensual en la fecha del sueldo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::refresh, enabled = !state.isWorking) {
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

        state.message?.let { message ->
            item {
                Column {
                    InfoBanner(message, color = Sky)
                    TextButton(onClick = viewModel::clearMessage) { Text("Cerrar") }
                }
            }
        }

        item {
            RebalancingCard(
                state = state,
                onSalaryChange = viewModel::setSalary,
                onUseLastSalary = viewModel::useLastSalary,
                onOpenSettings = onOpenSettings,
            )
        }

        item {
            MacroAssumptionsCard(
                context = state.context,
                onOpenSettings = onOpenSettings,
            )
        }

        item {
            SmartCard {
                SectionHeader(
                    title = "Comparador REM",
                    subtitle = "¿Letra, bono CER, dollar linked o tasa en pesos?",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Proyecta instrumentos de corto plazo con las medianas del REM y los compara " +
                        "contra un crawling peg ficticio del 1% mensual. Horizonte máximo: abril de 2027.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onOpenRem) {
                    Text("Abrir comparador REM")
                }
            }
        }

        item {
            Text(
                "El motor no ejecuta órdenes ni vende activos: solo propone cómo asignar ingresos nuevos. " +
                    "No constituye asesoramiento financiero.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Interactive rebalancing module: macro traffic light, current vs. target
 * distribution and the exact split of the next income.
 */
@Composable
fun RebalancingCard(
    state: RebalancingUiState,
    onSalaryChange: (String) -> Unit,
    onUseLastSalary: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RiskSemaphoreCard(
            assessment = state.context?.assessment,
            onOpenSettings = onOpenSettings,
        )
        DistributionCard(context = state.context)
        NextIncomeCard(
            state = state,
            onSalaryChange = onSalaryChange,
            onUseLastSalary = onUseLastSalary,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun RiskSemaphoreCard(
    assessment: CarryRiskAssessment?,
    onOpenSettings: () -> Unit,
) {
    val color = riskColor(assessment?.band)
    var showBreakdown by remember { mutableStateOf(false) }
    SmartCard {
        SectionHeader(
            title = "Semáforo macro",
            subtitle = "Índice de riesgo de carry 0 – 100",
            actionLabel = "Supuestos",
            onAction = onOpenSettings,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "ÍNDICE DE CARRY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        assessment?.score?.toString() ?: "—",
                        style = MaterialTheme.typography.headlineLarge,
                        color = color,
                    )
                    Text(
                        " / 100",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            val band = assessment?.band
            if (band == null) {
                TagPill("Calculando…", Sky)
            } else {
                TagPill("${band.emoji} ${band.label}", color)
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearBar(
            fraction = (assessment?.score ?: 0) / 100f,
            color = color,
            height = 10,
        )
        Spacer(Modifier.height(12.dp))
        if (assessment == null) {
            Text(
                "Cargá billeteras, activos y cotizaciones para calcular el entorno macro.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            InfoBanner(assessment.suggestion, color = color)
            Spacer(Modifier.height(12.dp))
            assessment.factors.forEach { factor ->
                RiskFactorRow(factor = factor, color = color)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = { showBreakdown = true }) {
                    Text("Ver desglose")
                }
                val automatic = assessment.breakdown.dataPoints.any { !it.isFallback }
                Text(
                    if (automatic) {
                        "Fuentes: ArgentinaDatos + DolarAPI"
                    } else {
                        "Datos manuales de Ajustes"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (assessment.countryRiskCeilingActive) {
                val trend = if (assessment.countryRiskTrendActive) {
                    " y variación 30 días " +
                        "${if (assessment.countryRisk30dChange >= 0) "+" else "−"}" +
                        "${kotlin.math.abs(assessment.countryRisk30dChange)} pts activa"
                } else {
                    ""
                }
                InfoBanner(
                    "Techo AMARILLO por riesgo país: nivel ${assessment.countryRiskPoints} pts " +
                        "(umbral 800)$trend.",
                    color = Amber,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (assessment.hysteresisApplied) {
                InfoBanner(
                    "Histéresis: el estado se mantiene en " +
                        "${assessment.band.label.lowercase()} hasta que el índice caiga 10 puntos " +
                        "por debajo del umbral (ROJO sale en 55; VERDE se entra en 20).",
                    color = color,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (assessment.electoralCapApplied) {
                InfoBanner(
                    "Calendario electoral: el estado VERDE tiene techo de " +
                        "${Fmt.percent(assessment.electoralCapPesoPercent, 0)} en pesos. " +
                        "La desprotección se hace antes del clima electoral, no durante.",
                    color = Amber,
                )
            }
        }
    }
    if (showBreakdown && assessment != null) {
        MacroBreakdownDialog(
            assessment = assessment,
            onDismiss = { showBreakdown = false },
        )
    }
}

/**
 * Full explanation of the index: every scored component, the rule applied, the
 * raw data and its source. Scrollable so it works on small screens.
 */
@Composable
private fun MacroBreakdownDialog(
    assessment: CarryRiskAssessment,
    onDismiss: () -> Unit,
) {
    val breakdown = assessment.breakdown
    Dialog(onDismissRequest = onDismiss) {
        SmartCard(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SectionHeader(
                    title = "Desglose del índice",
                    subtitle = "${assessment.score} / 100 · ${assessment.band.emoji} " +
                        assessment.band.label,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "El índice solo usa variables de mercado. El desvío de cartera no suma: " +
                        "solo dimensiona el flujo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "COMPONENTES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                breakdown.components.forEach { component ->
                    BreakdownComponentRow(component)
                }
                ThinDivider()
                KeyValueRow(
                    "Suma de componentes",
                    "${breakdown.scoreBeforeCap} pts",
                    strong = true,
                )
                KeyValueRow("Índice final (tope 100)", "${assessment.score} pts", strong = true)
                breakdown.scoreCapNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "DATOS Y FUENTES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                breakdown.dataPoints.forEach { point ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                point.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                point.value,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Text(
                            buildString {
                                append(point.source)
                                point.asOf?.let { append(" · $it") }
                                if (point.isFallback) append(" · respaldo manual")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (point.isFallback) Amber else Emerald,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "ESTADO Y TECHOS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                KeyValueRow("Banda por puntaje", "${assessment.rawBand.emoji} ${assessment.rawBand.label}")
                KeyValueRow(
                    "Banda vigente",
                    "${assessment.band.emoji} ${assessment.band.label}",
                    strong = true,
                )
                KeyValueRow(
                    "Meta en pesos",
                    "${Fmt.percent(assessment.riskAdjustedTargetPesoPercent, 0)} · " +
                        "estratégica ${Fmt.percent(assessment.strategicTargetPesoPercent, 0)}",
                )
                KeyValueRow(
                    "Techo VERDE electoral",
                    Fmt.percent(assessment.electoralCapPesoPercent, 0),
                    valueColor = if (assessment.electoralCapApplied) Amber else Emerald,
                )
                KeyValueRow(
                    "Riesgo país",
                    if (assessment.countryRiskConfigured) {
                        "${assessment.countryRiskPoints} pts · " +
                            "${if (assessment.countryRisk30dChange >= 0) "+" else "−"}" +
                            "${kotlin.math.abs(assessment.countryRisk30dChange)} en 30 d"
                    } else {
                        "Sin dato"
                    },
                    valueColor = if (assessment.countryRiskCeilingActive) Amber else Emerald,
                )
                KeyValueRow(
                    "Colchón de carry",
                    Fmt.signedPercent(assessment.carryCushionPercent, 2) + "/mes",
                    valueColor = if (assessment.carryCushionPercent > 0.0) Emerald else Rose,
                )
                KeyValueRow(
                    "Brecha MEP / blue",
                    "${Fmt.percent(assessment.fxGapPercent, 2)} · " +
                        "${Fmt.percent(assessment.blueGapPercent, 2)}",
                )
                KeyValueRow(
                    "Histéresis",
                    if (assessment.hysteresisApplied) "Activa" else "No aplica",
                    valueColor = if (assessment.hysteresisApplied) Amber else Emerald,
                )
                if (breakdown.notes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    InfoBanner(breakdown.notes.joinToString("\n\n"), color = Sky)
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cerrar")
            }
        }
    }
}

@Composable
private fun BreakdownComponentRow(component: CarryRiskComponent) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                component.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (component.isInformational) "Info" else "${component.points}/${component.maxPoints}",
                style = MaterialTheme.typography.titleSmall,
                color = if (component.isInformational) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    if (component.points == 0) Emerald else if (component.points >= component.maxPoints) Rose else Amber
                },
            )
        }
        Text(
            component.input,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            component.rule,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            component.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RiskFactorRow(factor: MacroRiskFactor, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                factor.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (factor.isInformational) "Info" else "${factor.score}/${factor.maxScore}",
                style = MaterialTheme.typography.titleSmall,
                color = if (factor.isInformational) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    color
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearBar(
            fraction = if (factor.maxScore > 0) factor.score.toFloat() / factor.maxScore else 0f,
            color = color,
            height = 5,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            factor.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DistributionCard(context: MacroRebalanceContext?) {
    val valuation = context?.valuation
    val assessment = context?.assessment
    val target = context?.settings?.rebalanceTargetPesoPercent?.toDouble() ?: 60.0
    val tolerance = context?.settings?.rebalanceTolerancePp ?: 5.0
    val currentPeso = valuation?.pesoSharePercent ?: 0.0
    val targetPeso = target.coerceIn(0.0, 100.0)
    val adjustedTarget = assessment?.riskAdjustedTargetPesoPercent ?: targetPeso

    SmartCard {
        SectionHeader(
            title = "Distribución actual vs. meta",
            subtitle = "Los CEDEARs (SPY) y bonos (AO27 / AO27D) cuentan como exposición en dólares",
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Pesos",
                value = Fmt.percent(currentPeso),
                caption = Fmt.compact(valuation?.arsAssetsCents ?: 0),
                accent = Emerald,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Dólares",
                value = Fmt.percent(100.0 - currentPeso),
                caption = Fmt.compact(valuation?.usdAssetsArsCents ?: 0),
                accent = Sky,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Cartera actual",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(5.dp))
        AllocationBar(pesoFraction = (currentPeso / 100.0).toFloat())
        Spacer(Modifier.height(5.dp))
        Row {
            Text("${Fmt.percent(currentPeso)} pesos", style = MaterialTheme.typography.bodySmall, color = Emerald)
            Spacer(Modifier.weight(1f))
            Text(
                "${Fmt.percent(100.0 - currentPeso)} dólares",
                style = MaterialTheme.typography.bodySmall,
                color = Sky,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Meta estratégica",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(5.dp))
        AllocationBar(pesoFraction = (targetPeso / 100.0).toFloat())
        Spacer(Modifier.height(5.dp))
        Row {
            Text("${Fmt.percent(targetPeso)} pesos", style = MaterialTheme.typography.bodySmall, color = Emerald)
            Spacer(Modifier.weight(1f))
            Text(
                "${Fmt.percent(100.0 - targetPeso)} dólares",
                style = MaterialTheme.typography.bodySmall,
                color = Sky,
            )
        }

        if (assessment != null && kotlin.math.abs(adjustedTarget - targetPeso) > 0.01) {
            Spacer(Modifier.height(10.dp))
            InfoBanner(
                "Meta ajustada por riesgo (${assessment.band.label.lowercase()}): " +
                    "${Fmt.percent(adjustedTarget)} pesos / ${Fmt.percent(100.0 - adjustedTarget)} dólares.",
                color = riskColor(assessment.band),
            )
        }

        ThinDivider()
        KeyValueRow(
            "Desvío vs. meta estratégica",
            if (assessment == null) "—" else Fmt.pp(assessment.deviationPp),
            valueColor = if (assessment == null) {
                Sky
            } else if (kotlin.math.abs(assessment.deviationPp) <= tolerance) {
                Emerald
            } else {
                Amber
            },
        )
        if (assessment != null) {
            KeyValueRow(
                "Techo VERDE por calendario",
                Fmt.percent(assessment.electoralCapPesoPercent, 0),
                valueColor = if (assessment.electoralCapApplied) Amber else Emerald,
            )
        }
        if (assessment != null) {
            ThinDivider()
            KeyValueRow(
                "Guardarraíl de liquidez",
                if (assessment.liquidityGuardrail.isSatisfied) "Cumple" else "Revisar",
                valueColor = if (assessment.liquidityGuardrail.isSatisfied) Emerald else Amber,
                strong = !assessment.liquidityGuardrail.isSatisfied,
            )
            Text(
                if (MoneyVisibility.hidden) {
                    "Detalle oculto por privacidad"
                } else {
                    assessment.liquidityGuardrail.detail
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val exclusions = context?.settings?.carryExcludedPatterns.orEmpty()
            if (exclusions.isNotBlank()) {
                KeyValueRow("Fuera de la ratio", exclusions)
            }
        }
    }
}

@Composable
private fun NextIncomeCard(
    state: RebalancingUiState,
    onSalaryChange: (String) -> Unit,
    onUseLastSalary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = state.context
    val plan = state.plan
    val latestSalary = context?.latestSalaryCents ?: 0L

    SmartCard {
        SectionHeader(
            title = "Rebalanceo orgánico",
            subtitle = "Sin vender activos: distribuye el próximo ingreso",
            actionLabel = "Ajustar reparto",
            onAction = onOpenSettings,
        )
        Spacer(Modifier.height(10.dp))
        AmountInput(
            value = state.salaryText,
            onValueChange = onSalaryChange,
            label = "Próximo sueldo / ingreso a invertir",
            supportingText = "El motor calcula cuánto destinar a pesos y cuánto a cobertura en dólares.",
        )
        if (latestSalary > 0L) {
            TextButton(onClick = onUseLastSalary) {
                Text("Usar último sueldo guardado (${Fmt.ars(latestSalary)})")
            }
        }
        Spacer(Modifier.height(8.dp))

        if (plan == null || plan.nextIncomeCents <= 0L) {
            InfoBanner(
                "Ingresá el próximo sueldo para ver el desglose exacto por destino. " +
                    "También podés guardar un plan en la pantalla Sueldo y usarlo como base.",
                color = Sky,
            )
            if (plan != null && plan.maturityFlows.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Vencimientos próximos (flujo orgánico)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                plan.maturityFlows.forEach { flow ->
                    KeyValueRow(
                        "${flow.symbol} · ${DateUtils.dateLabel(flow.maturityDate)}",
                        Fmt.ars(flow.amountCents),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (MoneyVisibility.hidden) {
                        "Detalle oculto por privacidad"
                    } else {
                        plan.maturityDetail
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val accent = if (plan.dollarPercentOfIncome >= 50) Sky else Emerald
            InfoBanner(plan.headline, color = accent)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "A pesos",
                    value = Fmt.ars(plan.toPesosCents),
                    caption = "${plan.pesoPercentOfIncome}% del ingreso",
                    accent = Emerald,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "A dólares",
                    value = Fmt.ars(plan.toDollarsCents),
                    caption = "${plan.dollarPercentOfIncome}% del ingreso",
                    accent = Sky,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            AllocationBar(
                pesoFraction = plan.toPesosCents.toFloat() / plan.nextIncomeCents.toFloat(),
                height = 14,
            )
            Spacer(Modifier.height(10.dp))
            plan.legs.forEach { leg ->
                AllocationLegRow(leg)
            }
            if (plan.maturityFlows.isNotEmpty()) {
                ThinDivider()
                Text(
                    "Vencimientos = flujo orgánico",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                plan.maturityFlows.forEach { flow ->
                    KeyValueRow(
                        "${flow.symbol} · ${DateUtils.dateLabel(flow.maturityDate)}",
                        Fmt.ars(flow.amountCents),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (MoneyVisibility.hidden) {
                        "Detalle oculto por privacidad"
                    } else {
                        plan.maturityDetail
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }
            ThinDivider()
            KeyValueRow(
                "Cartera después del ingreso",
                "${Fmt.percent(plan.currentPesoPercent)} → ${Fmt.percent(plan.projectedPesoPercent)} en pesos",
                strong = true,
            )
            val months = plan.monthsToRealign
            KeyValueRow(
                "Tiempo de realineamiento",
                if (months == null) "—" else "≈ ${formatMonths(months)} meses de ahorro",
            )
            KeyValueRow(
                "Meta ajustada por riesgo",
                "${Fmt.percent(plan.riskAdjustedTargetPesoPercent)} pesos / " +
                    "${Fmt.percent(100.0 - plan.riskAdjustedTargetPesoPercent)} dólares",
            )
            Spacer(Modifier.height(6.dp))
            TagPill(
                text = if (plan.isBalancedAfterIncome) "Queda dentro de la banda" else "Ajuste gradual",
                color = if (plan.isBalancedAfterIncome) Emerald else Amber,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (MoneyVisibility.hidden) "Detalle oculto por privacidad" else plan.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AllocationLegRow(leg: OrganicAllocationLeg) {
    val color = when (leg.bucket) {
        AllocationBucket.LIQUIDITY -> Emerald
        AllocationBucket.CARRY_FCI -> Violet
        AllocationBucket.FIXED_RATE -> Amber
        AllocationBucket.MEP -> Sky
        AllocationBucket.SOVEREIGN_BOND -> Rose
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(leg.label, style = MaterialTheme.typography.titleSmall)
            Text(
                leg.destination,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Fmt.ars(leg.amountCents),
                style = MaterialTheme.typography.titleSmall,
                color = color,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                "${leg.percent}% del ingreso",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun MacroAssumptionsCard(
    context: MacroRebalanceContext?,
    onOpenSettings: () -> Unit,
) {
    SmartCard {
        SectionHeader(
            title = "Supuestos y fuentes",
            subtitle = "ArgentinaDatos + DolarAPI + tasas BCRA",
            actionLabel = "Editar",
            onAction = onOpenSettings,
        )
        ThinDivider()
        if (context == null) {
            Text(
                "Cargando datos de cartera y cotizaciones…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val assessment = context.assessment
            KeyValueRow(
                "TNA corta en pesos (TEM base)",
                Fmt.rate(assessment.referenceTnaPercent) +
                    if (context.settings.macroMarket.shortTnaPercent != null) {
                        " · mediana plazo fijo 30 d"
                    } else if (context.settings.pesoReferenceTnaPercent > 0.0) {
                        " · manual"
                    } else {
                        " · ponderada de cartera"
                    },
            )
            KeyValueRow(
                "Devaluación mensual esperada",
                Fmt.percent(assessment.monthlyDevaluationPercent, 1),
            )
            KeyValueRow(
                "Colchón de carry (TEM − crawl)",
                Fmt.signedPercent(assessment.carryCushionPercent, 2) + "/mes",
                valueColor = if (assessment.carryCushionPercent > 0.0) Emerald else Rose,
                strong = true,
            )
            KeyValueRow(
                "Dólar oficial",
                Fmt.ars((assessment.officialRate * 100.0).roundToLong()),
            )
            KeyValueRow(
                "Dólar MEP",
                Fmt.ars((assessment.mepRate * 100.0).roundToLong()),
            )
            KeyValueRow(
                "Dólar mayorista",
                Fmt.ars((assessment.wholesaleRate * 100.0).roundToLong()),
            )
            KeyValueRow(
                "Brecha MEP (MEP ÷ mayorista)",
                Fmt.percent(assessment.fxGapPercent, 2),
                valueColor = when {
                    assessment.fxGapPercent < 5.0 -> Emerald
                    assessment.fxGapPercent <= 12.0 -> Amber
                    else -> Rose
                },
            )
            KeyValueRow(
                "Velocidad de la brecha (14 días)",
                if (assessment.fxGapPreviousPercent == null) {
                    "Sin lectura previa"
                } else {
                    Fmt.pp(assessment.fxGapSpeedPp)
                },
                valueColor = if (assessment.fxGapSpeedPp > 0.0) Amber else Emerald,
            )
            KeyValueRow(
                "Brecha del blue",
                Fmt.percent(assessment.blueGapPercent, 2),
                valueColor = if (assessment.blueGapPercent > 5.0) Rose else Emerald,
            )
            KeyValueRow(
                "Riesgo país",
                if (assessment.countryRiskConfigured) {
                    "${assessment.countryRiskPoints} pts · " +
                        "${if (assessment.countryRisk30dChange >= 0) "+" else "−"}" +
                        "${kotlin.math.abs(assessment.countryRisk30dChange)} en 30 días" +
                        (assessment.countryRiskDate?.let { " · $it" } ?: "")
                } else {
                    "Sin dato"
                },
                valueColor = if (assessment.countryRiskCeilingActive) Amber else Emerald,
            )
            KeyValueRow(
                "Objetivo ROJO",
                "${Fmt.percent(assessment.redTargetPesoPercent, 0)} pesos / " +
                    "${Fmt.percent(100.0 - assessment.redTargetPesoPercent, 0)} dólares",
            )
            KeyValueRow(
                "Techo VERDE por calendario electoral",
                Fmt.percent(assessment.electoralCapPesoPercent, 0),
                valueColor = if (assessment.electoralCapApplied) Amber else Emerald,
            )
            KeyValueRow(
                "Tasa real mensual",
                Fmt.signedPercent(assessment.realMonthlyRatePercent, 2),
                valueColor = if (assessment.realMonthlyRatePercent >= 0.0) Emerald else Rose,
                strong = true,
            )
            KeyValueRow(
                "Tasa real anualizada",
                Fmt.signedPercent(assessment.realAnnualRatePercent, 1),
                valueColor = if (assessment.realAnnualRatePercent >= 0.0) Emerald else Rose,
            )
            Spacer(Modifier.height(10.dp))
            InfoBanner(
                "El índice mide solo el mercado: brecha MEP nivel (30) + velocidad (15) + " +
                    "brecha blue (10) + colchón de carry (30) + riesgo país nivel (20) + " +
                    "variación 30 d (10), con tope 100. El desvío de cartera no suma puntos: " +
                    "se usa para dimensionar el flujo. La ratio excluye " +
                    "«${context.settings.carryExcludedPatterns}»; los CEDEARs y los bonos " +
                    "(AO27 / AO27D) suman exposición en dólares aunque coticen en pesos.",
                color = Sky,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Cambiar riesgo país, brecha previa y calendario electoral")
            }
        }
    }
}

@Composable
private fun AllocationBar(
    pesoFraction: Float,
    height: Int = 11,
) {
    val pesos = pesoFraction.coerceIn(0f, 1f)
    val dollars = 1f - pesos
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(CircleShape)
            .background(Outline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(pesos.coerceAtLeast(0.0001f))
                .background(Emerald),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(dollars.coerceAtLeast(0.0001f))
                .background(Sky),
        )
    }
}

private fun riskColor(band: CarryRiskBand?): Color = when (band) {
    CarryRiskBand.LOW -> Emerald
    CarryRiskBand.MODERATE -> Amber
    CarryRiskBand.HIGH -> Rose
    null -> Sky
}

private fun formatMonths(value: Double): String =
    String.format(java.util.Locale.forLanguageTag("es-AR"), "%.1f", value)
