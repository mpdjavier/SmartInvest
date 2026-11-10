package com.smartinvest.ui.investments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.model.WalletType
import com.smartinvest.domain.repository.InvestmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WalletDraft(
    val id: Long = 0,
    val name: String = "",
    val institution: String = "",
    val type: WalletType = WalletType.WALLET,
    val currency: CurrencyCode = CurrencyCode.ARS,
    val balanceText: String = "",
    val tnaText: String = "",
    val capText: String = "",
    val notes: String = "",
    val isActive: Boolean = true,
    val rateSource: String = "manual",
    val liquidityPriority: Int = 2,
    val rateUpdatedAt: Long? = null,
    val linkedHoldingId: Long? = null,
) {
    val isEditing: Boolean get() = id > 0

    companion object {
        fun from(wallet: Wallet): WalletDraft = WalletDraft(
            id = wallet.id,
            name = wallet.name,
            institution = wallet.institution,
            type = wallet.type,
            currency = wallet.currency,
            balanceText = centsToInput(wallet.balanceCents),
            tnaText = trimNumber(wallet.tnaPercent),
            capText = if (wallet.remuneratedCapCents > 0) centsToInput(wallet.remuneratedCapCents) else "",
            notes = wallet.notes,
            isActive = wallet.isActive,
            rateSource = wallet.rateSource,
            liquidityPriority = wallet.liquidityPriority,
            rateUpdatedAt = wallet.rateUpdatedAt,
            linkedHoldingId = wallet.linkedHoldingId,
        )

        private fun centsToInput(cents: Long): String =
            if (cents <= 0) "" else (cents / 100.0).toString().replace('.', ',')

        private fun trimNumber(value: Double): String =
            if (value <= 0.0) "" else value.toString().replace('.', ',')
    }
}

data class WalletsUiState(
    val wallets: List<Wallet> = emptyList(),
    val holdings: List<Holding> = emptyList(),
    val draft: WalletDraft? = null,
    val isWorking: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val fciHoldings: List<Holding> get() = holdings.filter { it.assetType == AssetType.FCI }
}

@HiltViewModel
class WalletsViewModel @Inject constructor(
    private val investments: InvestmentRepository,
) : ViewModel() {

    private val draft = MutableStateFlow<WalletDraft?>(null)
    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)

    val uiState = combine(
        combine(investments.observeWallets(), investments.observeHoldings()) { wallets, holdings ->
            wallets to holdings
        },
        draft,
        working,
        message,
        error,
    ) { (wallets, holdings), currentDraft, isWorking, msg, err ->
        WalletsUiState(
            wallets = wallets,
            holdings = holdings,
            draft = currentDraft,
            isWorking = isWorking,
            message = msg,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletsUiState())

    fun startNew() {
        error.value = null
        draft.value = WalletDraft()
    }

    fun startEdit(wallet: Wallet) {
        error.value = null
        draft.value = WalletDraft.from(wallet)
    }

    /** Fills the draft with a real-world wallet preset and links its FCI when present. */
    fun applyPreset(preset: WalletPreset) {
        error.value = null
        val holdings = uiState.value.holdings
        val linked = findLinkedHolding(preset, holdings)
        draft.update { current ->
            val base = current ?: WalletDraft()
            base.copy(
                name = preset.name,
                institution = preset.institution,
                type = preset.type,
                currency = preset.currency,
                notes = if (base.notes.isBlank()) preset.notes else base.notes,
                linkedHoldingId = linked?.id,
            )
        }
        if (linked != null) {
            message.value = "Preset aplicado. Se vincula el FCI ${linked.symbol.ifBlank { linked.name }}."
        }
    }

    private fun findLinkedHolding(preset: WalletPreset, holdings: List<Holding>): Holding? {
        val fci = holdings.filter { it.assetType == AssetType.FCI }
        val byAlias = fci.firstOrNull { holding ->
            val haystack = "${holding.symbol} ${holding.name}".lowercase()
            preset.linkedAliases.any { alias -> haystack.contains(alias.lowercase()) }
        }
        return byAlias ?: fci.firstOrNull { holding ->
            preset.institution.isNotBlank() &&
                "${holding.symbol} ${holding.name}".contains(preset.institution, ignoreCase = true)
        }
    }

    fun dismissDraft() {
        draft.value = null
        error.value = null
    }

    fun updateDraft(transform: (WalletDraft) -> WalletDraft) {
        draft.update { current -> current?.let(transform) }
    }

    fun saveDraft() {
        val current = draft.value ?: return
        if (working.value) return
        if (current.name.isBlank()) {
            error.value = "La cuenta necesita un nombre"
            return
        }
        val balance = if (current.balanceText.isBlank()) 0L else MoneyParser.parseToCents(current.balanceText)
        if (balance == null) {
            error.value = "Saldo inválido"
            return
        }
        val tna = current.tnaText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        if (tna < 0.0 || tna > 500.0) {
            error.value = "La TNA debe estar entre 0 y 500"
            return
        }
        val cap = if (current.capText.isBlank()) 0L else MoneyParser.parseToCents(current.capText)
        if (cap == null) {
            error.value = "Tope remunerado inválido"
            return
        }

        val wallet = Wallet(
            id = current.id,
            name = current.name.trim(),
            institution = current.institution.trim(),
            type = current.type,
            currency = current.currency,
            balanceCents = balance,
            tnaPercent = tna,
            remuneratedCapCents = cap,
            isActive = current.isActive,
            rateSource = current.rateSource,
            rateUpdatedAt = current.rateUpdatedAt,
            liquidityPriority = current.liquidityPriority,
            notes = current.notes.trim(),
            linkedHoldingId = current.linkedHoldingId,
        )

        viewModelScope.launch {
            working.value = true
            runCatching { investments.upsertWallet(wallet) }
                .onSuccess {
                    message.value = "Cuenta guardada."
                    draft.value = null
                    error.value = null
                }
                .onFailure { error.value = it.message ?: "No se pudo guardar la cuenta" }
            working.value = false
        }
    }

    fun setActive(wallet: Wallet, active: Boolean) {
        viewModelScope.launch {
            runCatching { investments.setWalletActive(wallet.id, active) }
                .onSuccess { message.value = if (active) "${wallet.name} activada." else "${wallet.name} desactivada." }
                .onFailure { message.value = it.message ?: "No se pudo actualizar" }
        }
    }

    fun syncRates() {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = null
            runCatching { investments.syncRates() }
                .onSuccess { message.value = "${it.message} · ${it.source}" }
                .onFailure { message.value = "No se pudieron sincronizar las TNA." }
            working.value = false
        }
    }

    fun clearMessage() {
        message.value = null
        error.value = null
    }
}
