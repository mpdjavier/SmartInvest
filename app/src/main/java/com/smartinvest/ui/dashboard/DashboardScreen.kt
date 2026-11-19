package com.smartinvest.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.engine.PaymentAgendaRow
import com.smartinvest.domain.model.AlertSeverity
import com.smartinvest.domain.model.AppAlert
import com.smartinvest.domain.model.ComparaTasasCategory
import com.smartinvest.domain.model.ComparaTasasConfig
import com.smartinvest.domain.model.ExpenseRecurrence
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.HouseholdSummary
import com.smartinvest.domain.model.MemberSettlement
import com.smartinvest.domain.model.PerformanceWindow
import com.smartinvest.domain.usecase.PortfolioOverview
import com.smartinvest.ui.components.HelpHint
import com.smartinvest.ui.components.HeroAmountText
import com.smartinvest.ui.components.IconBubble
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.LinearBar
import com.smartinvest.ui.components.MetricPill
import com.smartinvest.ui.components.PortfolioChart
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.StatTile
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.investments.categoryAccent
import com.smartinvest.ui.investments.sortItemsForCategory
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.EmeraldSoft
import com.smartinvest.ui.theme.PlexMono
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.util.Fmt
import com.smartinvest.ui.util.MoneyVisibility
import com.smartinvest.ui.util.colorFromHex
import com.smartinvest.ui.util.severityColor
import java.time.LocalDate
import kotlin.math.roundToLong

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    onNewExpense: () -> Unit,
    onFundMovement: () -> Unit,
    onOpenHousehold: () -> Unit,
    onOpenInvestments: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenSalary: () -> Unit,
    onOpenEmergency: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenExpenseSearch: () -> Unit = {},
    onOpenExpenseReport: () -> Unit = {},
    onOpenPortfolioReconciliation: () -> Unit = {},
    onOpenRem: () -> Unit = {},
    onOpenPaymentCalendar: () -> Unit = {},
    onOpenComparaTasas: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Emerald),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "EN VIVO · ${Fmt.longDate(DateUtils.today()).uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Tu panorama financiero",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                IconButton(onClick = viewModel::togglePrivacy) {
                    Icon(
                        if (state.hideAmounts) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (state.hideAmounts) {
                            "Mostrar cifras"
                        } else {
                            "Ocultar cifras"
                        },
                        tint = if (state.hideAmounts) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
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
                    TextButton(onClick = viewModel::clearMessage) {
                        Text("Cerrar", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            HeroCard(
                overview = state.overview,
                onRefresh = viewModel::refresh,
                isRefreshing = state.isRefreshing,
            )
        }

        item {
            PaymentsDueCard(
                payments = state.upcomingPayments,
                today = state.today,
                onOpenCalendar = onOpenPaymentCalendar,
            )
        }

        item {
            state.overview?.let { overview ->
                SmartCard {
                    SectionHeader(title = "Flujo estimado", subtitle = "Tasas y proyecciones")
                    Spacer(Modifier.height(12.dp))
                    // FlowRow keeps every figure complete: when the three tiles do not fit on
                    // one line they wrap instead of ellipsizing "Interés / día".
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val dailyInterest = overview.analytics.dailyInterestCents
                        val monthlyInterest = overview.analytics.monthlyInterestCents
                        StatTile(
                            label = "Interés / día",
                            value = Fmt.signedArs(dailyInterest),
                            caption = "Día a día",
                            accent = if (dailyInterest >= 0) EmeraldSoft else Rose,
                            help = "Interés del día calculado con la misma metodología de Intereses día a día " +
                                "(capitalización diaria y variación de cuotapartes/mercado).",
                        )
                        StatTile(
                            label = "Proyección mes",
                            value = Fmt.signedArs(monthlyInterest),
                            caption = "Prom. × 30d",
                            accent = if (monthlyInterest >= 0) Sky else Rose,
                            help = "Promedio de los intereses diarios del mes multiplicado por 30 días.",
                        )
                        StatTile(
                            label = "Ganancia 30d",
                            value = Fmt.signedArs(overview.analytics.trailing30dGainCents),
                            caption = "${Fmt.signedArs(overview.analytics.trailing30dGainPerDayCents)} / día",
                            accent = if (overview.analytics.trailing30dGainCents >= 0) Emerald else Rose,
                            help = "Variación del patrimonio en los últimos 30 días según los " +
                                "snapshots diarios. Incluye rendimiento y cambios de valuación.",
                        )
                    }
                }
            }
        }

        item {
            PortfolioCard(overview = state.overview, onOpenAnalytics = onOpenAnalytics)
        }

        state.household?.let { household ->
            item {
                HouseholdCard(
                    summary = household,
                    onOpenHousehold = onOpenHousehold,
                )
            }
        }

        item {
            DollarCard(rates = state.fxRates)
        }

        if (state.comparaTasas.showOnDashboard) {
            item {
                ComparaTasasDashboardCard(
                    config = state.comparaTasas,
                    onOpenComparaTasas = onOpenComparaTasas,
                )
            }
        }

        if (state.alerts.isNotEmpty()) {
            item {
                AlertsCard(
                    alerts = state.alerts.take(3),
                    unread = state.unreadCount,
                    onOpenAlerts = onOpenAlerts,
                )
            }
        }

        item {
            SmartCard {
                SectionHeader(title = "Acciones rápidas")
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = onNewExpense,
                        label = { Text("Nuevo gasto") },
                        leadingIcon = { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(17.dp)) },
                    )
                    AssistChip(
                        onClick = onFundMovement,
                        label = { Text("Fondo común") },
                        leadingIcon = { Icon(Icons.Rounded.Savings, null, modifier = Modifier.size(17.dp)) },
                    )
                    AssistChip(
                        onClick = onOpenSalary,
                        label = { Text("Repartir sueldo") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TrendingUp, null, modifier = Modifier.size(17.dp)) },
                    )
                    AssistChip(
                        onClick = onOpenEmergency,
                        label = { Text("Rescate") },
                        leadingIcon = { Icon(Icons.Rounded.HealthAndSafety, null, modifier = Modifier.size(17.dp)) },
                    )
                    AssistChip(
                        onClick = onOpenAnalytics,
                        label = { Text("Analítica") },
                        leadingIcon = { Icon(Icons.Rounded.Insights, null, modifier = Modifier.size(17.dp)) },
                    )
                    AssistChip(
                        onClick = onOpenExpenseSearch,
                        label = { Text("Buscar gastos") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(17.dp)) },
                    )
                    AssistChip(
                        onClick = onOpenExpenseReport,
                        label = { Text("Reportes") },
                        leadingIcon = { Icon(Icons.Rounded.Description, null, modifier = Modifier.size(17.dp)) },
                    )
                    AssistChip(
                        onClick = onOpenPortfolioReconciliation,
                        label = { Text("Ajustar saldos") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(17.dp)) },
                    )
                    AssistChip(
                        onClick = onOpenRem,
                        label = { Text("Comparador REM") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TrendingUp, null, modifier = Modifier.size(17.dp)) },
                    )
                    AssistChip(
                        onClick = onOpenComparaTasas,
                        label = { Text("ComparaTasas") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TrendingUp, null, modifier = Modifier.size(17.dp)) },
                    )
                }
            }
        }

        item {
            Text(
                "Los rendimientos son estimaciones sobre saldos y TNA configurados. " +
                    "No constituyen asesoramiento financiero.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(
    overview: PortfolioOverview?,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
) {
    val valuation = overview?.valuation
    SmartCard(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PATRIMONIO TOTAL",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.14.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            MetricPill(
                text = if (valuation?.mepIsEstimated == true) "MEP estimado" else "MEP en vivo",
                color = if (valuation?.mepIsEstimated == true) Amber else Emerald,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 9.dp),
            color = Amber.copy(alpha = 0.45f),
        )
        Spacer(Modifier.height(10.dp))
        HeroAmountText(
            text = Fmt.ars(valuation?.totalArsCents ?: 0),
        )
        Text(
            "${Fmt.usd(valuation?.totalUsdCents ?: 0)} · MEP ${Fmt.ars(((valuation?.mepRate ?: 0.0) * 100).roundToLong())}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = com.smartinvest.ui.theme.PlexMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val change30 = overview?.analytics?.change30d?.percent
            MetricPill(
                text = "30d ${Fmt.signedPercent(change30)}",
                color = when {
                    change30 == null -> Sky
                    change30 >= 0 -> Emerald
                    else -> Rose
                },
            )
            val dailyInterest = valuation?.dailyInterestCents ?: 0L
            MetricPill(
                text = "Interés hoy ${Fmt.signedArs(dailyInterest)}",
                color = if (dailyInterest >= 0) EmeraldSoft else Rose,
            )
            if (valuation?.unrealizedGainCents != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetricPill(
                        text = "No realizada ${Fmt.signedArs(valuation.unrealizedGainCents)}",
                        color = if (valuation.unrealizedGainCents >= 0) Emerald else Rose,
                    )
                    HelpHint(
                        "Ganancia no realizada: diferencia entre el valor actual de tus activos " +
                            "y lo que pagaste por ellos. No es plata cobrada hasta que vendas.",
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (valuation?.mepIsEstimated == true) {
                    "Actualizá DolarAPI para valuar dólares con MEP real."
                } else {
                    "Cartera valuada a MEP venta."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !isRefreshing) {
                Text("Actualizar")
            }
        }
    }
}

@Composable
private fun PaymentsDueCard(
    payments: List<PaymentAgendaRow>,
    today: LocalDate,
    onOpenCalendar: () -> Unit,
) {
    val total = payments.sumOf { it.amountCents }
    SmartCard {
        SectionHeader(
            title = "Pagos de hoy y mañana",
            subtitle = if (payments.isEmpty()) {
                "Sin vencimientos pendientes"
            } else {
                "${payments.size} pendiente${if (payments.size == 1) "" else "s"} · ${Fmt.ars(total)}"
            },
            actionLabel = "Calendario",
            onAction = onOpenCalendar,
        )
        Spacer(Modifier.height(8.dp))
        if (payments.isEmpty()) {
            InfoBanner(
                "No hay pagos recurrentes para hoy ni mañana. Marcá un gasto como " +
                    "«Recurrente» y elegí la frecuencia para verlo acá y en el calendario.",
                color = Sky,
            )
        } else {
            payments.forEach { row ->
                val isToday = row.scheduledDate == today
                Row(
                    modifier = Modifier.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBubble(
                        icon = Icons.Rounded.CalendarMonth,
                        tint = if (isToday) Amber else Emerald,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(if (isToday) "Vence hoy" else "Vence mañana")
                                append(" · ")
                                append(Fmt.shortDate(row.scheduledDate))
                                if (row.intervalMonths > 1) {
                                    append(" · ")
                                    append(ExpenseRecurrence.fromMonths(row.intervalMonths).label)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Fmt.ars(row.amountCents),
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = PlexMono),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            ThinDivider()
            TextButton(onClick = onOpenCalendar) {
                Text("Ver el calendario completo")
            }
        }
    }
}

@Composable
private fun PortfolioCard(
    overview: PortfolioOverview?,
    onOpenAnalytics: () -> Unit,
) {
    val analytics = overview?.analytics
    val series = analytics?.series.orEmpty()
    val values = series.map { it.totalArsCents }
    SmartCard {
        SectionHeader(
            title = "Evolución patrimonial",
            subtitle = if (series.isNotEmpty()) {
                "${Fmt.shortDate(series.first().date)} → ${Fmt.shortDate(series.last().date)}"
            } else {
                "Se construye con un snapshot diario"
            },
            actionLabel = "Analítica",
            onAction = onOpenAnalytics,
        )
        Spacer(Modifier.height(12.dp))
        if (values.size >= 2) {
            PortfolioChart(
                values = values,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                lineColor = Emerald,
            )
        } else {
            InfoBanner(
                "Todavía no hay suficientes snapshots. Se guarda uno por día para calcular 7d, 30d e YTD.",
                color = Sky,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WindowPill(analytics?.change7d, Modifier.weight(1f))
            WindowPill(analytics?.change30d, Modifier.weight(1f))
            WindowPill(analytics?.ytd, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WindowPill(window: PerformanceWindow?, modifier: Modifier = Modifier) {
    val percent = window?.percent
    val color = when {
        percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
        percent >= 0 -> Emerald
        else -> Rose
    }
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Text(
            (window?.label ?: "—").uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            Fmt.signedPercent(window?.percent),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = PlexMono),
            color = color,
        )
    }
}

@Composable
private fun HouseholdCard(
    summary: HouseholdSummary,
    onOpenHousehold: () -> Unit,
) {
    SmartCard {
        SectionHeader(
            title = "Cuentas claras",
            subtitle = Fmt.month(summary.month),
            actionLabel = "Ver hogar",
            onAction = onOpenHousehold,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Gasto del mes",
                value = Fmt.compact(summary.totalSpendCents),
                caption = "Cuotas ${Fmt.ars(summary.installmentSpendCents)}",
                accent = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Fondo común",
                value = Fmt.compact(summary.fundBalanceCents),
                caption = "Aportes ${Fmt.ars(summary.fundContributionsCents)}",
                accent = Sky,
                modifier = Modifier.weight(1f),
            )
        }
        ThinDivider()
        val transfer = summary.mainTransfer
        if (transfer != null) {
            InfoBanner(
                "${transfer.fromName} debe transferir ${Fmt.ars(transfer.amountCents)} a " +
                    "${transfer.toName} para empatar a partes iguales.",
                color = Amber,
            )
        } else if (summary.hasActivity) {
            InfoBanner("Cuentas equilibradas este mes. No hay transferencias pendientes.", color = Emerald)
        } else {
            Text(
                "Sin movimientos compartidos este mes. Registrá el primer gasto del hogar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (summary.members.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val max = summary.members.maxOfOrNull { it.netContributionCents }?.coerceAtLeast(1) ?: 1
            summary.members.forEach { member ->
                MemberBar(member = member, maxNet = max)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Objetivo: ${Fmt.ars(summary.fairShareCents)} por integrante · " +
                    "${summary.installmentCount} cuotas del mes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MemberBar(member: MemberSettlement, maxNet: Long) {
    val color = colorFromHex(member.colorHex, Emerald)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            member.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            Fmt.ars(member.netContributionCents),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
    Spacer(Modifier.height(5.dp))
    LinearBar(
        fraction = member.netContributionCents.toFloat() / maxNet.toFloat(),
        color = color,
        height = 6,
    )
}

@Composable
private fun DollarCard(rates: List<FxRate>) {
    val preferred = listOf("oficial", "blue", "bolsa", "tarjeta")
    val shown = preferred.mapNotNull { house -> rates.firstOrNull { it.house == house } }
    SmartCard {
        SectionHeader(title = "Dólar hoy", subtitle = "DolarAPI · venta")
        Spacer(Modifier.height(10.dp))
        if (shown.isEmpty()) {
            Text(
                "Sin cotizaciones cacheadas. Usá Actualizar o esperá la sincronización.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                shown.forEach { rate ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                            Text(
                                rate.name.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                Fmt.ars((rate.sell * 100).roundToLong()),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = PlexMono,
                                ),
                                color = if (rate.house == "bolsa") Emerald else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComparaTasasDashboardCard(
    config: ComparaTasasConfig,
    onOpenComparaTasas: () -> Unit,
) {
    val active = config.activeCategories
    val items = config.catalog.items
    val locale = java.util.Locale.forLanguageTag("es-AR")
    SmartCard {
        SectionHeader(
            title = "Resumen ComparaTasas",
            subtitle = if (active.isEmpty()) {
                "Elegí qué secciones ver desde el formulario"
            } else {
                "${active.size} secciones tildadas · top por categoría"
            },
            actionLabel = "Qué ver / Ver todo",
            onAction = onOpenComparaTasas,
        )
        Spacer(Modifier.height(10.dp))
        if (active.isEmpty()) {
            Text(
                "No hay categorías seleccionadas. Tocá «Qué ver / Ver todo» para tildar cuentas remuneradas, FCI, plazos fijos, USD, cripto o comisiones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (items.isEmpty()) {
            Text(
                "Tocá «Qué ver / Ver todo» o el botón Actualizar arriba a la derecha para descargar el relevamiento de ComparaTasas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                active.forEach { category ->
                    val topItems = sortItemsForCategory(
                        category,
                        items.filter { it.categoryName == category.name },
                    ).take(3)
                    if (topItems.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                category.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = categoryAccent(category),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                topItems.forEach { item ->
                                    val badge = item.metricLabel.ifBlank {
                                        item.ratePercent?.let {
                                            "TNA " + String.format(locale, "%.2f%%", it)
                                        } ?: ""
                                    }
                                    TagPill(
                                        text = "${item.entityName}: $badge",
                                        color = categoryAccent(category),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertsCard(
    alerts: List<AppAlert>,
    unread: Int,
    onOpenAlerts: () -> Unit,
) {
    SmartCard {
        SectionHeader(
            title = "Alertas inteligentes",
            subtitle = if (unread > 0) "$unread sin leer" else "Todo revisado",
            actionLabel = "Ver todas",
            onAction = onOpenAlerts,
        )
        Spacer(Modifier.height(6.dp))
        alerts.forEach { alert ->
            AlertRow(alert)
        }
    }
}

@Composable
private fun AlertRow(alert: AppAlert) {
    val color = severityColor(alert.severity)
    Row(
        modifier = Modifier.padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconBubble(
            icon = if (alert.severity == AlertSeverity.INFO) Icons.Rounded.Info else Icons.Rounded.WarningAmber,
            tint = color,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(alert.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (MoneyVisibility.hidden) "Detalle oculto por privacidad" else alert.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
