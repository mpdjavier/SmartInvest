package com.smartinvest.ui.household

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.local.dao.ExpenseDao
import com.smartinvest.domain.engine.HistoricalExpenseRecord
import com.smartinvest.domain.engine.PaymentAgendaEngine
import com.smartinvest.domain.engine.PaymentAgendaMonthSummary
import com.smartinvest.domain.engine.PaymentAgendaRow
import com.smartinvest.domain.engine.PaymentAgendaStatus
import com.smartinvest.domain.engine.PaymentAgendaTemplate
import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.ExpenseRecurrence
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.repository.HouseholdRepository
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.NewExpense
import com.smartinvest.domain.repository.SettingsRepository
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.HeroAmountText
import com.smartinvest.ui.components.InfoBanner
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
import com.smartinvest.ui.util.Fmt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Inject

data class PaymentCalendarUiState(
    val month: YearMonth = YearMonth.now(),
    val summary: PaymentAgendaMonthSummary? = null,
    val selectedDate: LocalDate = DateUtils.today(),
    val walletHoldings: List<Holding> = emptyList(),
    val payDraft: PayAgendaItemDraft? = null,
    val message: String? = null,
)

private data class CalendarSources(
    val expenses: List<HistoricalExpenseRecord>,
    val categories: List<HouseholdCategory> = emptyList(),
    val members: List<HouseholdMember> = emptyList(),
    val walletHoldings: List<Holding> = emptyList(),
    val agendaJson: String = "",
    val creditCards: List<CreditCard> = emptyList(),
)

@HiltViewModel
class PaymentCalendarViewModel @Inject constructor(
    expenseDao: ExpenseDao,
    private val householdRepository: HouseholdRepository,
    investmentsRepository: InvestmentRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val selectedDate = MutableStateFlow<LocalDate?>(null)
    private val payDraft = MutableStateFlow<PayAgendaItemDraft?>(null)
    private val message = MutableStateFlow<String?>(null)

    private var latestCategories: List<HouseholdCategory> = emptyList()
    private var latestMembers: List<HouseholdMember> = emptyList()
    private var latestWallets: List<Holding> = emptyList()

    private val baseSourcesFlow = combine(
        expenseDao.observeAll(),
        householdRepository.observeCategories(includeArchived = false),
        householdRepository.observeMembers(),
        investmentsRepository.observeWalletHoldings(),
        settingsRepository.settings,
    ) { expenses, categories, members, wallets, settings ->
        val records = expenses.map {
            HistoricalExpenseRecord(
                id = it.id,
                description = it.description,
                amountCents = it.totalAmountCents,
                date = LocalDate.ofEpochDay(it.dateEpochDay),
                categoryId = it.categoryId,
                isRecurring = it.isRecurring,
                recurrenceMonths = it.recurrenceMonths,
            )
        }
        CalendarSources(
            expenses = records,
            categories = categories,
            members = members,
            walletHoldings = wallets,
            agendaJson = settings.paymentAgendaJson,
        )
    }

    private val sourcesFlow = combine(
        baseSourcesFlow,
        householdRepository.observeCards(includeInactive = false),
    ) { base, cards ->
        base.copy(creditCards = cards)
    }

    val uiState = combine(
        month,
        selectedDate,
        sourcesFlow,
        payDraft,
        message,
    ) { selectedMonth, picked, sources, currentPay, msg ->
        latestCategories = sources.categories
        latestMembers = sources.members
        latestWallets = sources.walletHoldings
        val today = DateUtils.today()
        val templates = PaymentAgendaEngine.decodeTemplates(sources.agendaJson)
        val summary = PaymentAgendaEngine.buildForMonth(
            month = selectedMonth,
            today = today,
            expenses = sources.expenses,
            serviceCategoryIds = emptySet(),
            templates = templates,
            recurringOnly = true,
            creditCards = sources.creditCards,
        )
        val effectiveSelected = picked
            ?.takeIf { YearMonth.from(it) == selectedMonth }
            ?: if (YearMonth.from(today) == selectedMonth) {
                today
            } else {
                summary.rows.minOfOrNull { it.scheduledDate } ?: selectedMonth.atDay(1)
            }
        PaymentCalendarUiState(
            month = selectedMonth,
            summary = summary,
            selectedDate = effectiveSelected,
            walletHoldings = sources.walletHoldings,
            payDraft = currentPay,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentCalendarUiState())

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
        selectedDate.value = null
    }

    fun nextMonth() {
        month.value = month.value.plusMonths(1)
        selectedDate.value = null
    }

    fun goToToday() {
        val today = DateUtils.today()
        month.value = YearMonth.from(today)
        selectedDate.value = today
    }

    fun selectDate(date: LocalDate) {
        selectedDate.value = date
    }

    fun clearMessage() {
        message.value = null
    }

    /**
     * Manual paid tick. Rows already backed by an expense in the month (registered from
     * Hogar or imported) are automatic and read-only; the rest are toggled through
     * [PaymentAgendaTemplate.manualPaidMonths] so the agenda stays in sync.
     */
    fun togglePaid(row: PaymentAgendaRow) {
        if (row.paidExpenseId != null) return
        val monthStr = month.value.toString()
        val markPaid = !row.isPaid
        viewModelScope.launch {
            val currentSettings = settingsRepository.currentSettings()
            val list = PaymentAgendaEngine.decodeTemplates(currentSettings.paymentAgendaJson).toMutableList()
            val idx = list.indexOfFirst { it.key == row.key }
            val existing = if (idx >= 0) list[idx] else null
            val manual = existing?.manualPaidMonths.orEmpty()
            val updatedManual = if (markPaid) {
                (manual + monthStr).distinct()
            } else {
                manual - monthStr
            }
            val updated = existing?.copy(
                name = existing.name.ifBlank { row.name },
                manualPaidMonths = updatedManual,
                isDeleted = false,
            ) ?: PaymentAgendaTemplate(
                key = row.key,
                name = row.name,
                manualPaidMonths = updatedManual,
            )
            if (idx >= 0) {
                list[idx] = updated
            } else {
                list += updated
            }
            settingsRepository.update(
                currentSettings.copy(paymentAgendaJson = PaymentAgendaEngine.encodeTemplates(list)),
            )
            message.value = if (markPaid) {
                "${row.name} marcado como pagado."
            } else {
                "Se desmarcó el pago de ${row.name}."
            }
        }
    }

    /**
     * Moves the payment of [row] to [newDate] inside the month shown in the calendar.
     * The invoice often changes the due date, so the new date is stored as a monthly
     * override and the rest of the agenda (name, amount, cadence) stays untouched.
     */
    fun movePayment(row: PaymentAgendaRow, newDate: LocalDate) {
        if (row.paidExpenseId != null) return
        if (newDate == row.scheduledDate) return
        val selectedMonth = month.value
        if (YearMonth.from(newDate) != selectedMonth) return
        val monthStr = selectedMonth.toString()
        viewModelScope.launch {
            val currentSettings = settingsRepository.currentSettings()
            val list = PaymentAgendaEngine.decodeTemplates(currentSettings.paymentAgendaJson).toMutableList()
            val idx = list.indexOfFirst { it.key == row.key }
            val existing = if (idx >= 0) list[idx] else null
            val overrides = existing?.monthDateOverrides.orEmpty() + (monthStr to newDate.toString())
            // Keep the cadence anchor null when there was no template: the engine then
            // falls back to the first recurring expense, so a bimonthly service stays bimonthly.
            val updated = existing?.copy(
                name = existing.name.ifBlank { row.name },
                monthDateOverrides = overrides,
                isDeleted = false,
            ) ?: PaymentAgendaTemplate(
                key = row.key,
                name = row.name,
                intervalMonths = row.intervalMonths.coerceAtLeast(1),
                monthDateOverrides = overrides,
            )
            if (idx >= 0) {
                list[idx] = updated
            } else {
                list += updated
            }
            settingsRepository.update(
                currentSettings.copy(paymentAgendaJson = PaymentAgendaEngine.encodeTemplates(list)),
            )
            selectedDate.value = newDate
            message.value = "${row.name}: la fecha de pago pasó al ${Fmt.longDate(newDate)}."
        }
    }

    fun startPayItem(row: PaymentAgendaRow) {
        val amountStr = if (row.amountCents > 0L) {
            String.format(Locale.forLanguageTag("es-AR"), "%.2f", row.amountCents / 100.0)
        } else {
            ""
        }
        val today = DateUtils.today()
        val defaultDate = if (YearMonth.from(today) == month.value) today else row.scheduledDate
        payDraft.value = PayAgendaItemDraft(
            row = row,
            amountText = amountStr,
            date = defaultDate,
            useWalletFci = latestWallets.isNotEmpty(),
        )
    }

    fun updatePayDraft(transform: (PayAgendaItemDraft) -> PayAgendaItemDraft) {
        payDraft.value = payDraft.value?.let(transform)
    }

    fun dismissPayDraft() {
        payDraft.value = null
    }

    fun confirmPayItem() {
        val draft = payDraft.value ?: return
        val amountCents = MoneyParser.parseToCents(draft.amountText)
        if (amountCents == null || amountCents <= 0L) {
            message.value = "Ingresá un monto válido para registrar el pago."
            return
        }
        val serviceCategory = latestCategories.firstOrNull {
            it.name.contains("servicio", ignoreCase = true)
        } ?: latestCategories.firstOrNull()
        val payer = latestMembers.firstOrNull { it.isCurrentUser } ?: latestMembers.firstOrNull()

        if (serviceCategory == null || payer == null) {
            message.value = "Verificá que exista al menos un integrante y la categoría Servicios."
            return
        }

        val wallet = latestWallets.firstOrNull()
        val method = if (draft.useWalletFci && wallet != null) {
            ExpensePaymentMethod.WALLET_FCI
        } else {
            ExpensePaymentMethod.CASH
        }

        viewModelScope.launch {
            runCatching {
                householdRepository.addExpense(
                    NewExpense(
                        description = PaymentAgendaEngine.cleanDisplayName(draft.row.name),
                        totalCents = amountCents,
                        date = draft.date,
                        categoryId = serviceCategory.id,
                        paidByMemberId = payer.id,
                        paymentMethod = method,
                        walletHoldingId = if (method == ExpensePaymentMethod.WALLET_FCI) wallet?.id else null,
                        notes = draft.row.notes,
                    ),
                )
            }.onSuccess {
                payDraft.value = null
                val originNote = if (method == ExpensePaymentMethod.WALLET_FCI && wallet != null) {
                    " y descontado de ${wallet.symbol}"
                } else {
                    ""
                }
                message.value = "Pago de ${draft.row.name} (${MoneyFormatter.ars(amountCents)}) registrado$originNote."
            }.onFailure { error ->
                message.value = error.message ?: "No se pudo registrar el pago"
            }
        }
    }
}

private val WEEKDAY_LABELS = listOf("L", "M", "M", "J", "V", "S", "D")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentCalendarScreen(
    viewModel: PaymentCalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val summary = state.summary
    val rows = summary?.rows.orEmpty()
    val rowsByDate = rows.groupBy { it.scheduledDate }
    val today = DateUtils.today()
    var reschedulingRow by remember { mutableStateOf<PaymentAgendaRow?>(null) }
    var showDatePickerForPay by remember { mutableStateOf(false) }

    if (showDatePickerForPay && state.payDraft != null) {
        val draft = state.payDraft!!
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = draft.date
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePickerForPay = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            viewModel.updatePayDraft { it.copy(date = picked) }
                        }
                        showDatePickerForPay = false
                    },
                ) { Text("Aceptar") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerForPay = false }) { Text("Cancelar") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    reschedulingRow?.let { row ->
        val monthStartMillis = state.month.atDay(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val monthEndMillis = state.month.atEndOfMonth().plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli() - 1L
        val monthSelector = remember(monthStartMillis, monthEndMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis in monthStartMillis..monthEndMillis

                override fun isSelectableYear(year: Int): Boolean = year == state.month.year
            }
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = row.scheduledDate
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
            selectableDates = monthSelector,
        )
        DatePickerDialog(
            onDismissRequest = { reschedulingRow = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val newDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            viewModel.movePayment(row, newDate)
                        }
                        reschedulingRow = null
                    },
                ) { Text("Mover") }
            },
            dismissButton = {
                TextButton(onClick = { reschedulingRow = null }) { Text("Cancelar") }
            },
        ) {
            DatePicker(
                state = pickerState,
                title = {
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp)) {
                        Text("Nueva fecha de pago", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${row.name} · solo días de ${Fmt.month(state.month)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("Calendario de pagos", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Solo gastos marcados como «Recurrente» y los pagos agendados. " +
                        "Tocá un día para ver, tildar o cambiar la fecha de sus pagos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            MonthSelector(
                label = Fmt.month(state.month),
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
            )
        }

        if (state.month != YearMonth.from(today)) {
            item {
                TextButton(onClick = viewModel::goToToday) {
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
                            "TOTAL RECURRENTE DEL MES",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                        HeroAmountText(text = Fmt.ars(summary?.totalScheduledCents ?: 0L))
                    }
                }
                ThinDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = "Pagados",
                        value = "${summary?.paidCount ?: 0}",
                        caption = "tildados este mes",
                        accent = Emerald,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Pendientes",
                        value = "${summary?.pendingCount ?: 0}",
                        caption = "${summary?.dueOrOverdueCount ?: 0} por vencer / vencidos",
                        accent = if ((summary?.dueOrOverdueCount ?: 0) > 0) Amber else Sky,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            SmartCard {
                CalendarMonthGrid(
                    month = state.month,
                    today = today,
                    selectedDate = state.selectedDate,
                    rowsByDate = rowsByDate,
                    onSelect = viewModel::selectDate,
                )
            }
        }

        item {
            SectionHeader(
                title = "Pagos del ${Fmt.shortDate(state.selectedDate)}",
                subtitle = if (rowsByDate[state.selectedDate].isNullOrEmpty()) {
                    "Sin pagos recurrentes para este día"
                } else {
                    "${rowsByDate[state.selectedDate].orEmpty().size} pago(s) en el día"
                },
            )
        }

        val dayRows = rowsByDate[state.selectedDate].orEmpty()
        if (dayRows.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.CalendarMonth,
                    title = "Día sin pagos",
                    message = "Marcá un gasto como «Recurrente» al cargarlo o agregalo desde la agenda " +
                        "para verlo en el calendario.",
                )
            }
        } else {
            items(dayRows, key = { "${it.key}-${it.scheduledDate}" }) { row ->
                CalendarPaymentRow(
                    row = row,
                    onTogglePaid = { viewModel.togglePaid(row) },
                    onPay = { viewModel.startPayItem(row) },
                    onMoveToAnotherDate = { reschedulingRow = row },
                )
            }
        }

        item {
            Text(
                "Los pagos registrados desde Hogar o con el botón «Pagar» descuentan el saldo de tu billetera FCI " +
                    "y quedan tildados automáticamente. «Cambiar fecha de pago» mueve solo el mes que estás viendo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    state.payDraft?.let { draft ->
        AgendaPayDialog(
            draft = draft,
            walletHolding = state.walletHoldings.firstOrNull(),
            onUpdate = viewModel::updatePayDraft,
            onPickDate = { showDatePickerForPay = true },
            onConfirm = viewModel::confirmPayItem,
            onDismiss = viewModel::dismissPayDraft,
        )
    }
}

@Composable
private fun CalendarMonthGrid(
    month: YearMonth,
    today: LocalDate,
    selectedDate: LocalDate,
    rowsByDate: Map<LocalDate, List<PaymentAgendaRow>>,
    onSelect: (LocalDate) -> Unit,
) {
    val leadingBlanks = month.atDay(1).dayOfWeek.value - 1
    val daysInMonth = month.lengthOfMonth()
    val cellCount = leadingBlanks + daysInMonth
    val weekCount = (cellCount + 6) / 7

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            WEEKDAY_LABELS.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        for (week in 0 until weekCount) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (weekday in 0..6) {
                    val dayNumber = week * 7 + weekday - leadingBlanks + 1
                    if (dayNumber in 1..daysInMonth) {
                        val date = month.atDay(dayNumber)
                        CalendarDayCell(
                            date = date,
                            isToday = date == today,
                            isSelected = date == selectedDate,
                            rows = rowsByDate[date].orEmpty(),
                            onClick = { onSelect(date) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    rows: List<PaymentAgendaRow>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    val border = when {
        isSelected -> BorderStroke(1.5.dp, Emerald)
        isToday -> BorderStroke(1.dp, Sky)
        else -> null
    }
    val background = when {
        isSelected -> Emerald.copy(alpha = 0.16f)
        isToday -> Sky.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val indicatorColor = when {
        rows.isEmpty() -> Color.Transparent
        rows.all { it.isPaid } -> Emerald
        rows.any { it.status == PaymentAgendaStatus.OVERDUE } -> Rose
        else -> Amber
    }
    val bordered = if (border != null) {
        modifier.border(border, shape)
    } else {
        modifier
    }
    Box(
        modifier = bordered
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isSelected -> Emerald
                    isToday -> Sky
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            // Always reserve the indicator strip so the day number keeps the same
            // vertical position with or without pending payments.
            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier.height(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (rows.isNotEmpty()) {
                    repeat(rows.size.coerceAtMost(3)) {
                        Box(
                            Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(indicatorColor),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarPaymentRow(
    row: PaymentAgendaRow,
    onTogglePaid: () -> Unit,
    onPay: (() -> Unit)? = null,
    onMoveToAnotherDate: (() -> Unit)? = null,
) {
    val isAutomatic = row.paidExpenseId != null
    val isClosingEvent = row.key.startsWith("cierre ")
    val isCardStatementDue = row.key.startsWith("resumen ")
    // Hide the manual checkbox on closing events and on unpaid credit-card statements so the user
    // uses "Pagar resumen" (which records the expense and debits the wallet FCI).
    val showManualCheckbox = !isClosingEvent && (!isCardStatementDue || row.isPaid)
    val statusColor = when {
        row.isPaid -> Emerald
        row.status == PaymentAgendaStatus.OVERDUE -> Rose
        row.status == PaymentAgendaStatus.DUE_SOON -> Amber
        else -> Sky
    }
    val detail = when {
        isAutomatic -> "Registrado en Hogar"
        row.isPaid -> "Tildado a mano (sin gasto ni débito)"
        isClosingEvent -> "Cierre de resumen"
        row.status == PaymentAgendaStatus.OVERDUE -> "Vencido"
        row.daysUntilDue == 0L -> "Vence hoy"
        row.status == PaymentAgendaStatus.DUE_SOON -> "Vence en ${row.daysUntilDue} d"
        else -> "Agendado"
    } + if (row.intervalMonths > 1) {
        " · ${ExpenseRecurrence.fromMonths(row.intervalMonths).label}"
    } else {
        ""
    }

    SmartCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showManualCheckbox) {
                    Checkbox(
                        checked = row.isPaid,
                        onCheckedChange = if (isAutomatic) null else { _ -> onTogglePaid() },
                        enabled = !isAutomatic,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.isPaid) Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (row.amountCents > 0L) {
                        Text(
                            Fmt.ars(row.amountCents),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (row.isPaid) Emerald else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (!isClosingEvent) {
                        TagPill(
                            text = if (row.isPaid) "Pagado" else "Pendiente",
                            color = statusColor,
                        )
                    } else {
                        TagPill(
                            text = "Cierre",
                            color = Sky,
                        )
                    }
                }
            }
            if (!isAutomatic) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onMoveToAnotherDate != null) {
                        TextButton(
                            onClick = onMoveToAnotherDate,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(
                                Icons.Rounded.EditCalendar,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (row.isPaid) "Cambiar fecha" else "Cambiar fecha de pago")
                        }
                    }
                    if (!isClosingEvent && onPay != null) {
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = onPay,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            val payLabel = when {
                                isCardStatementDue -> "Pagar resumen"
                                row.isPaid -> "Registrar pago"
                                else -> "Pagar"
                            }
                            Text(payLabel, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
