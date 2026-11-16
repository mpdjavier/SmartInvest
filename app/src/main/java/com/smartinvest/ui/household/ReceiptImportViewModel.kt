package com.smartinvest.ui.household

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.importer.ParsedReceipt
import com.smartinvest.data.importer.ReceiptKind
import com.smartinvest.data.importer.ReceiptParser
import com.smartinvest.data.importer.ReceiptTextExtractor
import com.smartinvest.domain.engine.PaymentAgendaEngine
import com.smartinvest.domain.engine.PaymentAgendaTemplate
import com.smartinvest.domain.model.AlertSeverity
import com.smartinvest.domain.model.AlertType
import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.model.NewAlert
import com.smartinvest.domain.repository.AlertsRepository
import com.smartinvest.domain.repository.HouseholdRepository
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.NewExpense
import com.smartinvest.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import javax.inject.Inject

data class ReceiptForm(
    val description: String = "",
    val amountText: String = "",
    val date: LocalDate = DateUtils.today(),
    val isCardStatement: Boolean = false,
    val cardName: String = "",
    val dueDate: LocalDate = DateUtils.today(),
    val nextClosingDate: LocalDate? = null,
    val dueAlertEnabled: Boolean = true,
    val closingAlertEnabled: Boolean = true,
    val recordExpenseNow: Boolean = true,
    val categoryId: Long? = null,
    val memberId: Long? = null,
    val paymentMethod: ExpensePaymentMethod = ExpensePaymentMethod.CASH,
    val walletHoldingId: Long? = null,
    val isOwnTransfer: Boolean = false,
    val notes: String = "",
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

data class ReceiptImportUiState(
    val isLoading: Boolean = true,
    val parsed: ParsedReceipt? = null,
    val form: ReceiptForm = ReceiptForm(),
    val categories: List<HouseholdCategory> = emptyList(),
    val members: List<HouseholdMember> = emptyList(),
    val walletHoldings: List<Holding> = emptyList(),
    val extractionFailed: Boolean = false,
)

private data class ReceiptBaseData(
    val members: List<HouseholdMember>,
    val categories: List<HouseholdCategory>,
    val walletHoldings: List<Holding>,
)

@HiltViewModel
class ReceiptImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: HouseholdRepository,
    private val investments: InvestmentRepository,
    private val settingsRepository: SettingsRepository,
    private val alertsRepository: AlertsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val receiptPath: String? = savedStateHandle.get<String>("receiptPath")
        ?.let { runCatching { android.net.Uri.decode(it) }.getOrNull() ?: it }

    private val parsed = MutableStateFlow<ParsedReceipt?>(null)
    private val loading = MutableStateFlow(true)
    private val extractionFailed = MutableStateFlow(false)
    private val form = MutableStateFlow(ReceiptForm())
    private var latestWalletHoldings: List<Holding> = emptyList()
    private var paymentDefaultApplied = false

    private val dataFlow = combine(
        repository.observeMembers(),
        repository.observeCategories(includeArchived = false),
        investments.observeWalletHoldings(),
    ) { members, categories, walletHoldings ->
        ReceiptBaseData(members, categories, walletHoldings)
    }

    val uiState = combine(parsed, form, loading, dataFlow) { receipt, currentForm, isLoading, data ->
        ReceiptImportUiState(
            isLoading = isLoading,
            parsed = receipt,
            form = currentForm,
            categories = data.categories,
            members = data.members,
            walletHoldings = data.walletHoldings,
            extractionFailed = extractionFailed.value,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ReceiptImportUiState(),
    )

    init {
        viewModelScope.launch {
            dataFlow.collect { data ->
                latestWalletHoldings = data.walletHoldings
                val serviceCategory = data.categories.firstOrNull {
                    it.name.contains("servicio", ignoreCase = true)
                } ?: data.categories.firstOrNull()
                form.update { current ->
                    current.copy(
                        categoryId = current.categoryId ?: serviceCategory?.id,
                        memberId = current.memberId
                            ?: (data.members.firstOrNull { it.isCurrentUser } ?: data.members.firstOrNull())?.id,
                        walletHoldingId = current.walletHoldingId ?: data.walletHoldings.firstOrNull()?.id,
                        paymentMethod = if (!paymentDefaultApplied && data.walletHoldings.isNotEmpty()) {
                            ExpensePaymentMethod.WALLET_FCI
                        } else {
                            current.paymentMethod
                        },
                    )
                }
                paymentDefaultApplied = true
            }
        }
        loadReceipt()
    }

    private fun loadReceipt() {
        viewModelScope.launch {
            val path = receiptPath
            if (path.isNullOrBlank()) {
                loading.value = false
                extractionFailed.value = true
                return@launch
            }
            val text = withContext(Dispatchers.IO) {
                ReceiptTextExtractor.extract(context, File(path))
            }
            val receipt = if (text.isBlank()) null else ReceiptParser.parse(text)
            parsed.value = receipt
            extractionFailed.value = text.isBlank()
            if (receipt != null) {
                val cleanDesc = ReceiptParser.stripPagoDePrefix(receipt.description)
                    .ifBlank { receipt.description }
                val isStatement = receipt.kind == ReceiptKind.CARD_STATEMENT
                val resolvedDue = receipt.dueDate ?: receipt.date ?: DateUtils.today()
                form.update { current ->
                    current.copy(
                        description = cleanDesc,
                        amountText = centsToInput(receipt.amountCents),
                        date = resolvedDue,
                        isCardStatement = isStatement,
                        cardName = receipt.cardName ?: if (isStatement) "TARJETA" else "",
                        dueDate = resolvedDue,
                        nextClosingDate = receipt.nextClosingDate,
                        dueAlertEnabled = true,
                        closingAlertEnabled = true,
                        recordExpenseNow = !isStatement,
                        isOwnTransfer = receipt.likelyOwnTransfer && receipt.kind == ReceiptKind.TRANSFER,
                        notes = buildNotes(receipt),
                    )
                }
            }
            loading.value = false
        }
    }

    private fun buildNotes(receipt: ParsedReceipt): String = buildString {
        if (receipt.kind == ReceiptKind.CARD_STATEMENT) {
            receipt.cardName?.let { append("Tarjeta: ").append(it).append(". ") }
            receipt.dueDate?.let { append("Vencimiento: ").append(DateUtils.longDateLabel(it)).append(". ") }
            receipt.nextClosingDate?.let { append("Próximo cierre: ").append(DateUtils.longDateLabel(it)).append(". ") }
            receipt.reference?.let { append("Resumen N°: ").append(it).append(".") }
            return@buildString
        }
        receipt.institutionLabel?.let { append("Comprobante: ").append(it).append(". ") }
        receipt.originInstitution?.let { append("Origen: ").append(it).append(". ") }
        receipt.destinationInstitution?.let { append("Destino: ").append(it).append(". ") }
        receipt.reference?.let { append("Referencia: ").append(it).append(". ") }
        if (receipt.kind == ReceiptKind.TRANSFER) append("Importado desde una transferencia.")
    }.trim()

    private fun centsToInput(cents: Long?): String =
        if (cents == null || cents <= 0) "" else String.format(Locale.forLanguageTag("es-AR"), "%.2f", cents / 100.0)

    fun setDescription(value: String) = form.update { it.copy(description = value, error = null) }
    fun setAmount(value: String) = form.update { it.copy(amountText = value, error = null) }
    fun setDate(value: LocalDate) = form.update { it.copy(date = value, dueDate = value) }
    fun setCardStatement(value: Boolean) = form.update {
        it.copy(
            isCardStatement = value,
            recordExpenseNow = if (value) false else true,
            error = null,
        )
    }
    fun setCardName(value: String) = form.update {
        val clean = value.trim()
        it.copy(
            cardName = value,
            description = if (it.isCardStatement && clean.isNotBlank()) "Resumen $clean" else it.description,
            error = null,
        )
    }
    fun setDueDate(value: LocalDate) = form.update { it.copy(dueDate = value, date = value, error = null) }
    fun setNextClosingDate(value: LocalDate?) = form.update { it.copy(nextClosingDate = value, error = null) }
    fun setDueAlertEnabled(value: Boolean) = form.update { it.copy(dueAlertEnabled = value) }
    fun setClosingAlertEnabled(value: Boolean) = form.update { it.copy(closingAlertEnabled = value) }
    fun setRecordExpenseNow(value: Boolean) = form.update { it.copy(recordExpenseNow = value, error = null) }
    fun setCategory(id: Long) = form.update { it.copy(categoryId = id) }
    fun setMember(id: Long) = form.update { it.copy(memberId = id) }
    fun setPaymentMethod(method: ExpensePaymentMethod) = form.update { it.copy(paymentMethod = method, error = null) }
    fun setWalletHolding(id: Long) = form.update { it.copy(walletHoldingId = id, error = null) }
    fun setNotes(value: String) = form.update { it.copy(notes = value) }
    fun setOwnTransfer(value: Boolean) = form.update { it.copy(isOwnTransfer = value, error = null) }

    fun save() {
        val current = form.value
        if (current.isSaving || current.saved) return
        if (current.isOwnTransfer && !current.isCardStatement) {
            form.update {
                it.copy(
                    error = "Si es una transferencia entre tus propias cuentas no es un gasto. " +
                        "Destildá la opción para registrarla igual.",
                )
            }
            return
        }
        val cleanDescription = ReceiptParser.stripPagoDePrefix(current.description).ifBlank { current.description.trim() }
        val amount = MoneyParser.parseToCents(current.amountText)
        if (cleanDescription.isBlank()) {
            form.update { it.copy(error = "Agregá una descripción") }
            return
        }
        if (amount == null || amount <= 0) {
            form.update { it.copy(error = "Ingresá un monto a pagar válido") }
            return
        }

        val shouldRecordExpense = !current.isCardStatement || current.recordExpenseNow
        val categoryId = current.categoryId
        val memberId = current.memberId
        val walletId = current.walletHoldingId ?: latestWalletHoldings.firstOrNull()?.id

        if (shouldRecordExpense) {
            if (categoryId == null) {
                form.update { it.copy(error = "Elegí una categoría") }
                return
            }
            if (memberId == null) {
                form.update { it.copy(error = "Elegí quién pagó") }
                return
            }
            if (current.paymentMethod == ExpensePaymentMethod.WALLET_FCI) {
                val wallet = latestWalletHoldings.firstOrNull { it.id == walletId } ?: latestWalletHoldings.firstOrNull()
                if (wallet == null) {
                    form.update {
                        it.copy(error = "No hay un FCI marcado como billetera. Marcalo en Inversiones o elegí otro medio de pago.")
                    }
                    return
                }
                if (amount > wallet.currentValueCents) {
                    form.update { it.copy(error = "El saldo del FCI billetera (${wallet.symbol}) no alcanza para este monto") }
                    return
                }
            }
        }

        form.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                if (current.isCardStatement) {
                    registerCardStatementAndAlerts(current, amount)
                }
                if (shouldRecordExpense && categoryId != null && memberId != null) {
                    repository.addExpense(
                        NewExpense(
                            description = cleanDescription,
                            totalCents = amount,
                            date = if (current.isCardStatement) current.dueDate else current.date,
                            categoryId = categoryId,
                            paidByMemberId = memberId,
                            paymentMethod = current.paymentMethod,
                            walletHoldingId = if (current.paymentMethod == ExpensePaymentMethod.WALLET_FCI) walletId else null,
                            notes = current.notes.trim(),
                        ),
                    )
                }
            }.onSuccess {
                form.update { it.copy(isSaving = false, saved = true) }
            }.onFailure { error ->
                form.update {
                    it.copy(
                        isSaving = false,
                        error = error.message ?: "No se pudo guardar el comprobante",
                    )
                }
            }
        }
    }

    private suspend fun registerCardStatementAndAlerts(current: ReceiptForm, amountCents: Long) {
        val cardLabel = current.cardName.trim().ifBlank { "Tarjeta de crédito" }
        val issuer = parsed.value?.originInstitution ?: "Banco"
        val brand = when {
            cardLabel.contains("MASTER", ignoreCase = true) -> "MASTERCARD"
            cardLabel.contains("AMEX", ignoreCase = true) || cardLabel.contains("AMERICAN", ignoreCase = true) -> "AMEX"
            else -> "VISA"
        }
        val closingDay = (current.nextClosingDate ?: parsed.value?.currentClosingDate ?: current.dueDate).dayOfMonth.coerceIn(1, 31)
        val dueDay = current.dueDate.dayOfMonth.coerceIn(1, 31)

        // 1. Upsert CreditCard with updated closing and due days
        val existingCards = repository.observeCards(includeInactive = true).first()
        val matchCard = existingCards.firstOrNull {
            it.name.equals(cardLabel, ignoreCase = true) ||
                it.name.contains(cardLabel, ignoreCase = true) ||
                cardLabel.contains(it.name, ignoreCase = true)
        }
        repository.upsertCard(
            CreditCard(
                id = matchCard?.id ?: 0L,
                name = matchCard?.name ?: cardLabel,
                issuer = matchCard?.issuer?.ifBlank { issuer } ?: issuer,
                brand = matchCard?.brand?.ifBlank { brand } ?: brand,
                closingDay = closingDay,
                dueDay = dueDay,
                colorHex = matchCard?.colorHex ?: "#F59E0B",
                isActive = true,
            ),
        )

        // 2. Register Vencimiento (with Monto a pagar) and Próximo cierre in PaymentAgenda
        val settings = settingsRepository.currentSettings()
        val templates = PaymentAgendaEngine.decodeTemplates(settings.paymentAgendaJson).toMutableList()

        val dueTitle = "Resumen $cardLabel"
        val dueKey = PaymentAgendaEngine.normalizeKey(dueTitle)
        val dueMonthStr = YearMonth.from(current.dueDate).toString()
        val existingDue = templates.firstOrNull { it.key == dueKey }
        val dueNotes = buildString {
            append("Vencimiento: ${DateUtils.longDateLabel(current.dueDate)}")
            current.nextClosingDate?.let { append(" · Próximo cierre: ${DateUtils.longDateLabel(it)}") }
            append(" · Monto a pagar: ${MoneyFormatter.ars(amountCents)}")
        }
        val updatedDue = PaymentAgendaTemplate(
            key = dueKey,
            name = dueTitle,
            customDayOfMonth = dueDay,
            estimatedAmountCents = amountCents,
            alertEnabled = current.dueAlertEnabled,
            alertDaysBefore = 5,
            notes = dueNotes,
            isDeleted = false,
            monthDateOverrides = (existingDue?.monthDateOverrides.orEmpty()) + (dueMonthStr to current.dueDate.toString()),
            manualPaidMonths = existingDue?.manualPaidMonths.orEmpty(),
        )
        templates.removeAll { it.key == dueKey }
        templates += updatedDue

        val nextClosing = current.nextClosingDate
        if (nextClosing != null) {
            val closeTitle = "Cierre $cardLabel"
            val closeKey = PaymentAgendaEngine.normalizeKey(closeTitle)
            val closeMonthStr = YearMonth.from(nextClosing).toString()
            val existingClose = templates.firstOrNull { it.key == closeKey }
            val updatedClose = PaymentAgendaTemplate(
                key = closeKey,
                name = closeTitle,
                customDayOfMonth = nextClosing.dayOfMonth.coerceIn(1, 31),
                estimatedAmountCents = 0L,
                alertEnabled = current.closingAlertEnabled,
                alertDaysBefore = 3,
                notes = "Próximo cierre de $cardLabel (${DateUtils.longDateLabel(nextClosing)})",
                isDeleted = false,
                monthDateOverrides = (existingClose?.monthDateOverrides.orEmpty()) + (closeMonthStr to nextClosing.toString()),
                manualPaidMonths = existingClose?.manualPaidMonths.orEmpty(),
            )
            templates.removeAll { it.key == closeKey }
            templates += updatedClose
        }

        settingsRepository.update(
            settings.copy(paymentAgendaJson = PaymentAgendaEngine.encodeTemplates(templates)),
        )

        // 3. Create immediate alerts for Vencimiento and Próximo Cierre
        if (current.dueAlertEnabled) {
            alertsRepository.insertIfNew(
                NewAlert(
                    type = AlertType.CARD_DUE,
                    severity = AlertSeverity.WARNING,
                    title = "Vencimiento $cardLabel: ${DateUtils.longDateLabel(current.dueDate)}",
                    message = "Monto a pagar: ${MoneyFormatter.ars(amountCents)} · " +
                        "Vence el ${DateUtils.longDateLabel(current.dueDate)}" +
                        (nextClosing?.let { " · Próximo cierre: ${DateUtils.longDateLabel(it)}." } ?: "."),
                    dedupeKey = "statement_due:${dueKey}:${current.dueDate}",
                ),
            )
        }
        if (current.closingAlertEnabled && nextClosing != null) {
            alertsRepository.insertIfNew(
                NewAlert(
                    type = AlertType.CARD_DUE,
                    severity = AlertSeverity.INFO,
                    title = "Próximo cierre $cardLabel: ${DateUtils.longDateLabel(nextClosing)}",
                    message = "El próximo cierre de $cardLabel es el ${DateUtils.longDateLabel(nextClosing)}. " +
                        "Resumen actual a pagar: ${MoneyFormatter.ars(amountCents)}.",
                    dedupeKey = "statement_close:${dueKey}:$nextClosing",
                ),
            )
        }
        runCatching { alertsRepository.scanAlerts() }
    }
}
