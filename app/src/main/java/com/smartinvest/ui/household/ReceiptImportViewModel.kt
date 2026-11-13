package com.smartinvest.ui.household

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.importer.ParsedReceipt
import com.smartinvest.data.importer.ReceiptParser
import com.smartinvest.data.importer.ReceiptTextExtractor
import com.smartinvest.data.importer.ReceiptKind
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.repository.HouseholdRepository
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.NewExpense
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

data class ReceiptForm(
    val description: String = "",
    val amountText: String = "",
    val date: LocalDate = DateUtils.today(),
    val categoryId: Long? = null,
    val memberId: Long? = null,
    val paymentMethod: ExpensePaymentMethod = ExpensePaymentMethod.WALLET_FCI,
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val receiptPath: String? = savedStateHandle.get<String>("receiptPath")
        ?.let { runCatching { android.net.Uri.decode(it) }.getOrNull() ?: it }

    private val parsed = MutableStateFlow<ParsedReceipt?>(null)
    private val loading = MutableStateFlow(true)
    private val extractionFailed = MutableStateFlow(false)
    private val form = MutableStateFlow(ReceiptForm())
    private var latestWalletHoldings: List<Holding> = emptyList()

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
                    )
                }
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
                form.update { current ->
                    current.copy(
                        description = cleanDesc,
                        amountText = centsToInput(receipt.amountCents),
                        date = receipt.date ?: DateUtils.today(),
                        isOwnTransfer = receipt.likelyOwnTransfer && receipt.kind == ReceiptKind.TRANSFER,
                        notes = buildNotes(receipt),
                    )
                }
            }
            loading.value = false
        }
    }

    private fun buildNotes(receipt: ParsedReceipt): String = buildString {
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
    fun setDate(value: LocalDate) = form.update { it.copy(date = value) }
    fun setCategory(id: Long) = form.update { it.copy(categoryId = id) }
    fun setMember(id: Long) = form.update { it.copy(memberId = id) }
    fun setPaymentMethod(method: ExpensePaymentMethod) = form.update { it.copy(paymentMethod = method, error = null) }
    fun setWalletHolding(id: Long) = form.update { it.copy(walletHoldingId = id, error = null) }
    fun setNotes(value: String) = form.update { it.copy(notes = value) }
    fun setOwnTransfer(value: Boolean) = form.update { it.copy(isOwnTransfer = value, error = null) }

    fun save() {
        val current = form.value
        if (current.isSaving || current.saved) return
        if (current.isOwnTransfer) {
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
            form.update { it.copy(error = "Ingresá un monto válido") }
            return
        }
        val categoryId = current.categoryId
        if (categoryId == null) {
            form.update { it.copy(error = "Elegí una categoría") }
            return
        }
        val memberId = current.memberId
        if (memberId == null) {
            form.update { it.copy(error = "Elegí quién pagó") }
            return
        }
        val walletId = current.walletHoldingId ?: latestWalletHoldings.firstOrNull()?.id
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

        form.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                repository.addExpense(
                    NewExpense(
                        description = cleanDescription,
                        totalCents = amount,
                        date = current.date,
                        categoryId = categoryId,
                        paidByMemberId = memberId,
                        paymentMethod = current.paymentMethod,
                        walletHoldingId = if (current.paymentMethod == ExpensePaymentMethod.WALLET_FCI) walletId else null,
                        notes = current.notes.trim(),
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
