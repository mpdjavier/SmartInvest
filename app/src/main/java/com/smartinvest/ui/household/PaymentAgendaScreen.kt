package com.smartinvest.ui.household

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.repository.AlertsRepository
import com.smartinvest.domain.repository.HouseholdRepository
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.NewExpense
import com.smartinvest.domain.repository.SettingsRepository
import com.smartinvest.ui.components.AmountInput
import com.smartinvest.ui.components.ChipRow
import com.smartinvest.ui.components.DateField
import com.smartinvest.ui.components.EmptyState
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

data class AgendaItemDraft(
    val originalKey: String = "",
    val name: String = "",
    val scheduledDate: LocalDate = DateUtils.today(),
    val amountText: String = "",
    val alertEnabled: Boolean = true,
    val alertDaysBefore: Int = 3,
    val notes: String = "",
    val isEditingExisting: Boolean = false,
)

data class PayAgendaItemDraft(
    val row: PaymentAgendaRow,
    val amountText: String,
    val date: LocalDate,
    val useWalletFci: Boolean,
)

data class PaymentAgendaUiState(
    val month: YearMonth = YearMonth.now(),
    val summary: PaymentAgendaMonthSummary? = null,
    val walletHoldings: List<Holding> = emptyList(),
    val editDraft: AgendaItemDraft? = null,
    val payDraft: PayAgendaItemDraft? = null,
    val message: String? = null,
)

private data class AgendaSources(
    val expenses: List<HistoricalExpenseRecord>,
    val categories: List<HouseholdCategory>,
    val members: List<HouseholdMember>,
    val walletHoldings: List<Holding>,
    val agendaJson: String,
)

@HiltViewModel
class PaymentAgendaViewModel @Inject constructor(
    expenseDao: ExpenseDao,
    private val householdRepository: HouseholdRepository,
    investmentsRepository: InvestmentRepository,
    private val settingsRepository: SettingsRepository,
    private val alertsRepository: AlertsRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val editDraft = MutableStateFlow<AgendaItemDraft?>(null)
    private val payDraft = MutableStateFlow<PayAgendaItemDraft?>(null)
    private val message = MutableStateFlow<String?>(null)

    private var latestCategories: List<HouseholdCategory> = emptyList()
    private var latestMembers: List<HouseholdMember> = emptyList()
    private var latestWallets: List<Holding> = emptyList()
    private var latestTemplates: List<PaymentAgendaTemplate> = emptyList()

    private val sourcesFlow = combine(
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
            )
        }
        AgendaSources(records, categories, members, wallets, settings.paymentAgendaJson)
    }

    val uiState = combine(
        month,
        sourcesFlow,
        editDraft,
        payDraft,
        message,
    ) { selectedMonth, sources, currentEdit, currentPay, msg ->
        latestCategories = sources.categories
        latestMembers = sources.members
        latestWallets = sources.walletHoldings
        val templates = PaymentAgendaEngine.decodeTemplates(sources.agendaJson)
        latestTemplates = templates

        val serviceCatIds = sources.categories
            .filter { it.name.contains("servicio", ignoreCase = true) }
            .map { it.id }
            .toSet()

        val summary = PaymentAgendaEngine.buildForMonth(
            month = selectedMonth,
            today = DateUtils.today(),
            expenses = sources.expenses,
            serviceCategoryIds = serviceCatIds,
            templates = templates,
        )

        PaymentAgendaUiState(
            month = selectedMonth,
            summary = summary,
            walletHoldings = sources.walletHoldings,
            editDraft = currentEdit,
            payDraft = currentPay,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentAgendaUiState())

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun nextMonth() {
        month.value = month.value.plusMonths(1)
    }

    fun goToCurrentMonth() {
        month.value = YearMonth.now()
    }

    fun clearMessage() {
        message.value = null
    }

    fun startNewItem() {
        val selected = month.value
        val today = DateUtils.today()
        val defaultDate = if (YearMonth.from(today) == selected) today else selected.atDay(10)
        editDraft.value = AgendaItemDraft(
            originalKey = "",
            name = "",
            scheduledDate = defaultDate,
            amountText = "",
            alertEnabled = true,
            alertDaysBefore = 3,
            notes = "",
            isEditingExisting = false,
        )
    }

    fun startEditItem(row: PaymentAgendaRow) {
        val amountStr = if (row.amountCents > 0L) {
            String.format(Locale.forLanguageTag("es-AR"), "%.2f", row.amountCents / 100.0)
        } else {
            ""
        }
        editDraft.value = AgendaItemDraft(
            originalKey = row.key,
            name = row.name,
            scheduledDate = row.scheduledDate,
            amountText = amountStr,
            alertEnabled = row.alertEnabled,
            alertDaysBefore = row.alertDaysBefore,
            notes = row.notes,
            isEditingExisting = true,
        )
    }

    fun updateEditDraft(transform: (AgendaItemDraft) -> AgendaItemDraft) {
        editDraft.value = editDraft.value?.let(transform)
    }

    fun dismissEditDraft() {
        editDraft.value = null
    }

    fun saveEditDraft() {
        val draft = editDraft.value ?: return
        val cleanName = PaymentAgendaEngine.cleanDisplayName(draft.name)
        if (cleanName.isBlank()) {
            message.value = "Ingresá el nombre del servicio o pago."
            return
        }
        val key = draft.originalKey.ifBlank { PaymentAgendaEngine.normalizeKey(cleanName) }
        val amountCents = if (draft.amountText.isBlank()) {
            0L
        } else {
            MoneyParser.parseToCents(draft.amountText) ?: 0L
        }
        val selectedMonthStr = YearMonth.from(draft.scheduledDate).toString()

        viewModelScope.launch {
            val currentSettings = settingsRepository.currentSettings()
            val list = PaymentAgendaEngine.decodeTemplates(currentSettings.paymentAgendaJson).toMutableList()
            val idx = list.indexOfFirst { it.key == key }
            val existing = if (idx >= 0) list[idx] else null

            val updatedOverrides = (existing?.monthDateOverrides.orEmpty() + (selectedMonthStr to draft.scheduledDate.toString()))
            val updated = PaymentAgendaTemplate(
                key = key,
                name = cleanName,
                customDayOfMonth = draft.scheduledDate.dayOfMonth,
                estimatedAmountCents = amountCents,
                alertEnabled = draft.alertEnabled,
                alertDaysBefore = draft.alertDaysBefore.coerceIn(0, 30),
                notes = draft.notes.trim(),
                isDeleted = false,
                monthDateOverrides = updatedOverrides,
                manualPaidMonths = existing?.manualPaidMonths.orEmpty(),
            )
            if (idx >= 0) {
                list[idx] = updated
            } else {
                list += updated
            }
            settingsRepository.update(
                currentSettings.copy(paymentAgendaJson = PaymentAgendaEngine.encodeTemplates(list)),
            )
            runCatching { alertsRepository.scanAlerts() }
            editDraft.value = null
            message.value = "Agenda actualizada para $cleanName (${Fmt.longDate(draft.scheduledDate)})."
        }
    }

    fun removeAgendaItem(key: String) {
        viewModelScope.launch {
            val currentSettings = settingsRepository.currentSettings()
            val list = PaymentAgendaEngine.decodeTemplates(currentSettings.paymentAgendaJson).toMutableList()
            val idx = list.indexOfFirst { it.key == key }
            if (idx >= 0) {
                list[idx] = list[idx].copy(isDeleted = true)
            } else {
                list += PaymentAgendaTemplate(key = key, name = key, isDeleted = true)
            }
            settingsRepository.update(
                currentSettings.copy(paymentAgendaJson = PaymentAgendaEngine.encodeTemplates(list)),
            )
            editDraft.value = null
            message.value = "Ítem quitado de la agenda."
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
            ExpensePaymentMethod.DEBIT
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentAgendaScreen(
    viewModel: PaymentAgendaViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val summary = state.summary
    var showDatePickerForEdit by remember { mutableStateOf(false) }
    var showDatePickerForPay by remember { mutableStateOf(false) }

    if (showDatePickerForEdit && state.editDraft != null) {
        val draft = state.editDraft!!
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = draft.scheduledDate
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePickerForEdit = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            viewModel.updateEditDraft { it.copy(scheduledDate = picked) }
                        }
                        showDatePickerForEdit = false
                    },
                ) { Text("Aceptar") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerForEdit = false }) { Text("Cancelar") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("Agenda de pagos", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Toma por defecto la fecha del pago anterior. Podés modificar fechas, configurar alertas y agregar nuevos pagos.",
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
                            "TOTAL AGENDADO EN EL MES",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            Fmt.ars(summary?.totalScheduledCents ?: 0L),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                    Button(onClick = viewModel::startNewItem) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Agregar")
                    }
                }
                ThinDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = "Pagados",
                        value = "${summary?.paidCount ?: 0}",
                        caption = "al día este mes",
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

        val rows = summary?.rows.orEmpty()
        if (rows.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.CalendarMonth,
                    title = "Agenda vacía en este mes",
                    message = "Los pagos de servicios que registres o importes por comprobante aparecerán acá automáticamente con la fecha del pago anterior. También podés agregarlos con el botón «+ Agregar».",
                )
            }
        } else {
            items(rows, key = { it.key }) { row ->
                PaymentAgendaRowCard(
                    row = row,
                    onEdit = { viewModel.startEditItem(row) },
                    onDelete = { viewModel.removeAgendaItem(row.key) },
                    onPay = { viewModel.startPayItem(row) },
                )
            }
        }
    }

    state.editDraft?.let { draft ->
        AgendaEditDialog(
            draft = draft,
            onUpdate = viewModel::updateEditDraft,
            onPickDate = { showDatePickerForEdit = true },
            onSave = viewModel::saveEditDraft,
            onDelete = if (draft.isEditingExisting) {
                { viewModel.removeAgendaItem(draft.originalKey) }
            } else {
                null
            },
            onDismiss = viewModel::dismissEditDraft,
        )
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
private fun PaymentAgendaRowCard(
    row: PaymentAgendaRow,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPay: () -> Unit,
) {
    val statusColor = when (row.status) {
        PaymentAgendaStatus.PAID -> Emerald
        PaymentAgendaStatus.OVERDUE -> Rose
        PaymentAgendaStatus.DUE_SOON -> Amber
        PaymentAgendaStatus.UPCOMING -> Sky
    }
    val statusText = when {
        row.isPaid && row.paidDate != null -> "Pagado ${Fmt.shortDate(row.paidDate)}"
        row.status == PaymentAgendaStatus.OVERDUE -> "Vencido (${Fmt.shortDate(row.scheduledDate)})"
        row.daysUntilDue == 0L -> "Vence hoy"
        row.status == PaymentAgendaStatus.DUE_SOON -> "Vence en ${row.daysUntilDue} d"
        else -> "Agendado ${Fmt.shortDate(row.scheduledDate)}"
    }

    SmartCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append("Fecha agendada: ")
                        append(Fmt.longDate(row.scheduledDate))
                        if (row.previousPaymentDate != null) {
                            append(" · Pago ant.: ")
                            append(Fmt.shortDate(row.previousPaymentDate))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (row.amountCents > 0L) {
                    Text(
                        Fmt.ars(row.amountCents),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (row.isPaid) Emerald else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(4.dp))
                TagPill(statusText, statusColor)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (row.alertEnabled) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                contentDescription = null,
                tint = if (row.alertEnabled) Sky else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (row.alertEnabled) {
                    if (row.alertDaysBefore == 0) "Alerta el mismo día" else "Alerta ${row.alertDaysBefore} día(s) antes"
                } else {
                    "Sin alerta"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "Eliminar",
                    tint = Rose,
                )
            }
            TextButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Modificar")
            }
            if (!row.isPaid) {
                Spacer(Modifier.width(4.dp))
                Button(onClick = onPay) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pagar")
                }
            }
        }
        if (row.notes.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                row.notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgendaEditDialog(
    draft: AgendaItemDraft,
    onUpdate: ((AgendaItemDraft) -> AgendaItemDraft) -> Unit,
    onPickDate: () -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (draft.isEditingExisting) "Modificar pago agendado" else "Nuevo pago en agenda")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { value -> onUpdate { it.copy(name = value) } },
                    label = { Text("Servicio / concepto") },
                    placeholder = { Text("SM Tucumán CISI / Luz / Gas") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DateField(
                    label = "Fecha agendada (vencimiento)",
                    value = Fmt.longDate(draft.scheduledDate),
                    onClick = onPickDate,
                )
                AmountInput(
                    value = draft.amountText,
                    onValueChange = { value -> onUpdate { it.copy(amountText = value) } },
                    label = "Monto estimado (opcional)",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Alerta de vencimiento", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Genera aviso en Alertas y al escanear vencimientos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.alertEnabled,
                        onCheckedChange = { enabled -> onUpdate { it.copy(alertEnabled = enabled) } },
                    )
                }
                if (draft.alertEnabled) {
                    Text(
                        "Anticipación de la alerta",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipRow(
                        options = listOf(0, 1, 3, 5, 7),
                        selected = draft.alertDaysBefore,
                        label = { days -> if (days == 0) "Mismo día" else "${days}d antes" },
                        onSelect = { days -> onUpdate { it.copy(alertDaysBefore = days) } },
                    )
                }
                OutlinedTextField(
                    value = draft.notes,
                    onValueChange = { value -> onUpdate { it.copy(notes = value) } },
                    label = { Text("Notas (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Quitar de la agenda", color = Rose)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun AgendaPayDialog(
    draft: PayAgendaItemDraft,
    walletHolding: Holding?,
    onUpdate: ((PayAgendaItemDraft) -> PayAgendaItemDraft) -> Unit,
    onPickDate: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar pago · ${draft.row.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Se registrará como gasto en la categoría Servicios y la fecha de este pago quedará como referencia para el mes siguiente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AmountInput(
                    value = draft.amountText,
                    onValueChange = { value -> onUpdate { it.copy(amountText = value) } },
                    label = "Monto abonado",
                )
                DateField(
                    label = "Fecha de pago",
                    value = Fmt.longDate(draft.date),
                    onClick = onPickDate,
                )
                if (walletHolding != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Descontar de FCI billetera (${walletHolding.symbol})",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "Disponible: ${Fmt.ars(walletHolding.currentValueCents)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.useWalletFci,
                            onCheckedChange = { use -> onUpdate { it.copy(useWalletFci = use) } },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Confirmar pago")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
    )
}
