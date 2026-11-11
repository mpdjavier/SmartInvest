package com.smartinvest.ui.household

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.repository.HouseholdRepository
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.NewExpense
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlin.math.min

data class ExpenseEditorData(
    val members: List<HouseholdMember> = emptyList(),
    val categories: List<HouseholdCategory> = emptyList(),
    val cards: List<CreditCard> = emptyList(),
    val fundBalanceCents: Long = 0,
    val walletHoldings: List<Holding> = emptyList(),
)

data class ExpenseForm(
    val description: String = "",
    val amountText: String = "",
    val date: LocalDate,
    val categoryId: Long? = null,
    val paidByMemberId: Long? = null,
    val paymentMethod: ExpensePaymentMethod = ExpensePaymentMethod.DEBIT,
    val cardId: Long? = null,
    val walletHoldingId: Long? = null,
    val installments: Int = 1,
    val notes: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

data class ExpenseEditorUiState(
    val data: ExpenseEditorData = ExpenseEditorData(),
    val form: ExpenseForm,
)

@HiltViewModel
class ExpenseEditorViewModel @Inject constructor(
    private val repository: HouseholdRepository,
    private val investments: InvestmentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val month: YearMonth = savedStateHandle.get<String>("month")
        ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        ?: YearMonth.now()

    private val today = DateUtils.today()
    private val defaultDate: LocalDate = if (YearMonth.from(today) == month) {
        today
    } else {
        month.atDay(min(today.dayOfMonth, month.lengthOfMonth()))
    }

    private val form = MutableStateFlow(ExpenseForm(date = defaultDate))
    private var latestData = ExpenseEditorData()
    private var defaultsApplied = false

    private val dataFlow = combine(
        repository.observeMembers(),
        repository.observeCategories(includeArchived = false),
        repository.observeCards(includeInactive = false),
        repository.observeFundBalance(),
        investments.observeWalletHoldings(),
    ) { members, categories, cards, fundBalance, walletHoldings ->
        ExpenseEditorData(members, categories, cards, fundBalance, walletHoldings)
    }

    val uiState = combine(dataFlow, form) { data, currentForm ->
        ExpenseEditorUiState(data = data, form = currentForm)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ExpenseEditorUiState(form = ExpenseForm(date = defaultDate)),
    )

    init {
        viewModelScope.launch {
            dataFlow.collect { data ->
                latestData = data
                form.update { current ->
                    current.copy(
                        categoryId = current.categoryId
                            ?: data.categories.firstOrNull()?.id,
                        paidByMemberId = current.paidByMemberId
                            ?: (data.members.firstOrNull { it.isCurrentUser } ?: data.members.firstOrNull())?.id,
                        cardId = current.cardId ?: data.cards.firstOrNull()?.id,
                        walletHoldingId = current.walletHoldingId ?: data.walletHoldings.firstOrNull()?.id,
                        paymentMethod = if (!defaultsApplied && data.walletHoldings.isNotEmpty()) {
                            ExpensePaymentMethod.WALLET_FCI
                        } else {
                            current.paymentMethod
                        },
                    )
                }
                defaultsApplied = true
            }
        }
    }

    fun setDescription(value: String) = form.update { it.copy(description = value, error = null) }
    fun setAmount(value: String) = form.update { it.copy(amountText = value, error = null) }
    fun setNotes(value: String) = form.update { it.copy(notes = value) }
    fun setDate(value: LocalDate) = form.update { it.copy(date = value) }
    fun setCategory(id: Long) = form.update { it.copy(categoryId = id) }
    fun setMember(id: Long) = form.update { it.copy(paidByMemberId = id) }
    fun setCard(id: Long) = form.update { it.copy(cardId = id) }

    fun setPaymentMethod(method: ExpensePaymentMethod) = form.update {
        it.copy(
            paymentMethod = method,
            installments = if (method == ExpensePaymentMethod.CREDIT) it.installments.coerceAtLeast(1) else 1,
            walletHoldingId = if (method == ExpensePaymentMethod.WALLET_FCI) {
                it.walletHoldingId ?: latestData.walletHoldings.firstOrNull()?.id
            } else {
                it.walletHoldingId
            },
            error = null,
        )
    }

    fun setWalletHolding(id: Long) = form.update { it.copy(walletHoldingId = id, error = null) }

    fun setInstallments(count: Int) = form.update { it.copy(installments = count.coerceIn(1, 60)) }

    fun clearError() = form.update { it.copy(error = null) }

    fun save() {
        val current = form.value
        if (current.isSaving || current.saved) return

        val amount = MoneyParser.parseToCents(current.amountText)
        if (current.description.isBlank()) {
            form.update { it.copy(error = "Agregá una descripción") }
            return
        }
        if (amount == null || amount <= 0) {
            form.update { it.copy(error = "Ingresá un monto válido") }
            return
        }
        val categoryId = current.categoryId
        if (categoryId == null) {
            form.update { it.copy(error = "Elegí una categoría") }
            return
        }
        val memberId = current.paidByMemberId
        if (memberId == null) {
            form.update { it.copy(error = "Elegí quién pagó") }
            return
        }
        if (current.paymentMethod == ExpensePaymentMethod.CREDIT && current.cardId == null) {
            form.update { it.copy(error = "Elegí una tarjeta de crédito") }
            return
        }
        if (current.paymentMethod == ExpensePaymentMethod.COMMON_FUND && amount > latestData.fundBalanceCents) {
            form.update { it.copy(error = "El fondo común no alcanza para este gasto") }
            return
        }
        if (current.paymentMethod == ExpensePaymentMethod.WALLET_FCI) {
            val walletId = current.walletHoldingId
            if (walletId == null) {
                form.update { it.copy(error = "Marcá un FCI como billetera para pagar desde ahí") }
                return
            }
            val wallet = latestData.walletHoldings.firstOrNull { it.id == walletId }
            if (wallet == null) {
                form.update { it.copy(error = "El FCI billetera ya no está disponible") }
                return
            }
            if (amount > wallet.currentValueCents) {
                form.update { it.copy(error = "El FCI billetera no alcanza para este gasto") }
                return
            }
        }

        form.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                repository.addExpense(
                    NewExpense(
                        description = current.description.trim(),
                        totalCents = amount,
                        date = current.date,
                        categoryId = categoryId,
                        paidByMemberId = memberId,
                        paymentMethod = current.paymentMethod,
                        creditCardId = if (current.paymentMethod == ExpensePaymentMethod.CREDIT) current.cardId else null,
                        installments = if (current.paymentMethod == ExpensePaymentMethod.CREDIT) current.installments else 1,
                        notes = current.notes.trim(),
                        walletHoldingId = if (current.paymentMethod == ExpensePaymentMethod.WALLET_FCI) {
                            current.walletHoldingId
                        } else {
                            null
                        },
                    ),
                )
            }.onSuccess {
                form.update { it.copy(isSaving = false, saved = true) }
            }.onFailure { error ->
                form.update {
                    it.copy(
                        isSaving = false,
                        error = error.message ?: "No se pudo guardar el gasto",
                    )
                }
            }
        }
    }
}
