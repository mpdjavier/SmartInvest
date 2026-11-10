package com.smartinvest.ui.household

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.model.FundMovementType
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.repository.HouseholdRepository
import com.smartinvest.domain.repository.NewFundMovement
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

data class FundEditorData(
    val members: List<HouseholdMember> = emptyList(),
    val fundBalanceCents: Long = 0,
)

data class FundForm(
    val type: FundMovementType = FundMovementType.CONTRIBUTION,
    val amountText: String = "",
    val date: LocalDate,
    val memberId: Long? = null,
    val description: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

data class FundEditorUiState(
    val data: FundEditorData = FundEditorData(),
    val form: FundForm,
)

@HiltViewModel
class FundMovementViewModel @Inject constructor(
    private val repository: HouseholdRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val month: YearMonth = savedStateHandle.get<String>("month")
        ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        ?: YearMonth.now()

    private val today = DateUtils.today()
    private val defaultDate = if (YearMonth.from(today) == month) {
        today
    } else {
        month.atDay(min(today.dayOfMonth, month.lengthOfMonth()))
    }

    private val form = MutableStateFlow(FundForm(date = defaultDate))
    private var latestData = FundEditorData()

    private val dataFlow = combine(
        repository.observeMembers(),
        repository.observeFundBalance(),
    ) { members, balance -> FundEditorData(members, balance) }

    val uiState = combine(dataFlow, form) { data, currentForm ->
        FundEditorUiState(data, currentForm)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FundEditorUiState(form = FundForm(date = defaultDate)),
    )

    init {
        viewModelScope.launch {
            dataFlow.collect { data ->
                latestData = data
                form.update { current ->
                    current.copy(
                        memberId = current.memberId
                            ?: (data.members.firstOrNull { it.isCurrentUser } ?: data.members.firstOrNull())?.id,
                    )
                }
            }
        }
    }

    fun setType(type: FundMovementType) = form.update { it.copy(type = type, error = null) }
    fun setAmount(value: String) = form.update { it.copy(amountText = value, error = null) }
    fun setDate(date: LocalDate) = form.update { it.copy(date = date) }
    fun setMember(id: Long) = form.update { it.copy(memberId = id) }
    fun setDescription(value: String) = form.update { it.copy(description = value) }
    fun clearError() = form.update { it.copy(error = null) }

    fun save() {
        val current = form.value
        if (current.isSaving || current.saved) return
        val amount = MoneyParser.parseToCents(current.amountText)
        if (amount == null || amount <= 0) {
            form.update { it.copy(error = "Ingresá un monto válido") }
            return
        }
        val memberId = current.memberId
        if (memberId == null) {
            form.update { it.copy(error = "Elegí quién realiza el movimiento") }
            return
        }
        if (current.type == FundMovementType.WITHDRAWAL && amount > latestData.fundBalanceCents) {
            form.update {
                it.copy(
                    error = "Saldo insuficiente. Disponible: ${MoneyFormatter.ars(latestData.fundBalanceCents)}",
                )
            }
            return
        }

        form.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                repository.addFundMovement(
                    NewFundMovement(
                        type = current.type,
                        amountCents = amount,
                        date = current.date,
                        memberId = memberId,
                        description = current.description.trim(),
                    ),
                )
            }.onSuccess {
                form.update { it.copy(isSaving = false, saved = true) }
            }.onFailure { error ->
                form.update {
                    it.copy(isSaving = false, error = error.message ?: "No se pudo guardar el movimiento")
                }
            }
        }
    }
}
