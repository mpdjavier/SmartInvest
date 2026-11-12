package com.smartinvest.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.FundMovement
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.model.HouseholdSummary
import com.smartinvest.domain.model.MonthlyExpenseItem
import com.smartinvest.domain.model.SettlementTransfer
import com.smartinvest.domain.repository.HouseholdRepository
import com.smartinvest.domain.repository.NewSettlement
import com.smartinvest.domain.usecase.ObserveHouseholdSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class HouseholdUiState(
    val month: YearMonth = YearMonth.now(),
    val members: List<HouseholdMember> = emptyList(),
    val categories: List<HouseholdCategory> = emptyList(),
    val cards: List<CreditCard> = emptyList(),
    val expenses: List<MonthlyExpenseItem> = emptyList(),
    val fundMovements: List<FundMovement> = emptyList(),
    val summary: HouseholdSummary? = null,
    val fundBalanceCents: Long = 0,
    val isWorking: Boolean = false,
    val message: String? = null,
)

private data class HouseholdBase(
    val members: List<HouseholdMember>,
    val categories: List<HouseholdCategory>,
    val cards: List<CreditCard>,
    val fundBalanceCents: Long,
)

private data class HouseholdMonthContent(
    val month: YearMonth,
    val summary: HouseholdSummary,
    val expenses: List<MonthlyExpenseItem>,
    val movements: List<FundMovement>,
    val base: HouseholdBase,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HouseholdViewModel @Inject constructor(
    private val repository: HouseholdRepository,
    observeSummary: ObserveHouseholdSummaryUseCase,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val message = MutableStateFlow<String?>(null)
    private val working = MutableStateFlow(false)

    private val base = combine(
        repository.observeMembers(),
        repository.observeCategories(),
        repository.observeCards(),
        repository.observeFundBalance(),
    ) { members, categories, cards, fundBalance ->
        HouseholdBase(members, categories, cards, fundBalance)
    }

    private val content = month.flatMapLatest { selectedMonth ->
        combine(
            observeSummary(selectedMonth),
            repository.observeMonthlyExpenses(selectedMonth),
            repository.observeFundMovements(selectedMonth),
            base,
        ) { summary, expenses, movements, baseData ->
            HouseholdMonthContent(
                month = selectedMonth,
                summary = summary,
                expenses = expenses,
                movements = movements,
                base = baseData,
            )
        }
    }

    val uiState = combine(content, message, working) { data, msg, isWorking ->
        HouseholdUiState(
            month = data.month,
            members = data.base.members,
            categories = data.base.categories,
            cards = data.base.cards,
            expenses = data.expenses,
            fundMovements = data.movements,
            summary = data.summary,
            fundBalanceCents = data.base.fundBalanceCents,
            isWorking = isWorking,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HouseholdUiState())

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun nextMonth() {
        month.value = month.value.plusMonths(1)
    }

    fun goToCurrentMonth() {
        month.value = YearMonth.now()
    }

    fun deleteExpense(id: Long) = perform("Gasto eliminado. Se actualizó el fondo si correspondía.") {
        repository.deleteExpense(id)
    }

    fun setInstallmentPaid(installmentId: Long, paid: Boolean) = perform(
        if (paid) "Cuota marcada como pagada." else "Cuota marcada como pendiente.",
    ) {
        repository.setInstallmentPaid(installmentId, paid)
    }

    fun deleteFundMovement(id: Long) = perform("Movimiento eliminado.") {
        repository.deleteFundMovement(id)
    }

    fun registerSettlement(
        transfer: SettlementTransfer?,
        amountText: String,
        note: String,
        date: LocalDate = DateUtils.today(),
        advanceToNextMonth: Boolean = false,
    ) {
        val parsedAmount = if (transfer == null) {
            0L
        } else {
            val typed = MoneyParser.parseToCents(amountText)
            if (typed == null || typed <= 0L) {
                message.value = "Ingresá un monto mayor a cero para registrar la transferencia."
                return
            }
            typed
        }
        val selectedMonth = month.value
        val isPartial = transfer != null && parsedAmount < transfer.amountCents
        val remainingCents = if (transfer != null) (transfer.amountCents - parsedAmount).coerceAtLeast(0L) else 0L
        val confirmMsg = if (isPartial) {
            "Pago parcial registrado (${com.smartinvest.core.money.MoneyFormatter.ars(parsedAmount)}). " +
                "Quedan ${com.smartinvest.core.money.MoneyFormatter.ars(remainingCents)} pendientes."
        } else {
            "Liquidación registrada. El mes quedó saldado."
        }
        perform(confirmMsg) {
            repository.registerSettlement(
                NewSettlement(
                    month = selectedMonth,
                    fromMemberId = transfer?.fromMemberId ?: 0L,
                    fromName = transfer?.fromName.orEmpty(),
                    toMemberId = transfer?.toMemberId ?: 0L,
                    toName = transfer?.toName.orEmpty(),
                    amountCents = parsedAmount,
                    date = date,
                    note = note,
                ),
            )
            if (advanceToNextMonth && !isPartial) {
                month.value = selectedMonth.plusMonths(1)
            }
        }
    }

    fun reopenSettlement(settlementId: Long) = perform("Mes reabierto. La liquidación fue eliminada.") {
        repository.deleteSettlement(settlementId)
    }

    fun clearMessage() {
        message.value = null
    }

    private fun perform(successMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            working.value = true
            runCatching { block() }
                .onSuccess { message.value = successMessage }
                .onFailure { message.value = it.message ?: "No se pudo completar la operación" }
            working.value = false
        }
    }
}
