package com.smartinvest.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.data.backup.AppBackupManager
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.repository.HouseholdRepository
import com.smartinvest.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryDraft(
    val id: Long = 0,
    val name: String = "",
    val emoji: String = "🏠",
    val colorHex: String = "#38BDF8",
) {
    val isEditing: Boolean get() = id > 0
}

data class CardDraft(
    val id: Long = 0,
    val name: String = "",
    val issuer: String = "",
    val brand: String = "VISA",
    val closingDay: Int = 25,
    val dueDay: Int = 10,
    val colorHex: String = "#F59E0B",
    val isActive: Boolean = true,
) {
    val isEditing: Boolean get() = id > 0

    companion object {
        fun from(card: CreditCard): CardDraft = CardDraft(
            id = card.id,
            name = card.name,
            issuer = card.issuer,
            brand = card.brand,
            closingDay = card.closingDay,
            dueDay = card.dueDay,
            colorHex = card.colorHex,
            isActive = card.isActive,
        )
    }
}

data class MemberDraft(
    val id: Long = 0,
    val name: String = "",
)

data class SettingsUiState(
    val members: List<HouseholdMember> = emptyList(),
    val categories: List<HouseholdCategory> = emptyList(),
    val cards: List<CreditCard> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val categoryDraft: CategoryDraft? = null,
    val cardDraft: CardDraft? = null,
    val memberDraft: MemberDraft? = null,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val household: HouseholdRepository,
    private val preferences: SettingsRepository,
    private val backupManager: AppBackupManager,
) : ViewModel() {

    private val categoryDraft = MutableStateFlow<CategoryDraft?>(null)
    private val cardDraft = MutableStateFlow<CardDraft?>(null)
    private val memberDraft = MutableStateFlow<MemberDraft?>(null)
    private val message = MutableStateFlow<String?>(null)

    val uiState = combine(
        household.observeMembers(),
        household.observeCategories(includeArchived = true),
        household.observeCards(includeInactive = true),
        preferences.settings,
        combine(categoryDraft, cardDraft, memberDraft, message) { category, card, member, msg ->
            SettingsDrafts(category, card, member, msg)
        },
    ) { members, categories, cards, settings, drafts ->
        SettingsUiState(
            members = members,
            categories = categories,
            cards = cards,
            settings = settings,
            categoryDraft = drafts.category,
            cardDraft = drafts.card,
            memberDraft = drafts.member,
            message = drafts.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    // Categories

    fun startCategory(id: Long? = null) {
        val existing = id?.let { target -> uiState.value.categories.firstOrNull { it.id == target } }
        categoryDraft.value = if (existing == null) {
            CategoryDraft()
        } else {
            CategoryDraft(existing.id, existing.name, existing.emoji, existing.colorHex)
        }
    }

    fun updateCategoryDraft(transform: (CategoryDraft) -> CategoryDraft) {
        categoryDraft.value = categoryDraft.value?.let(transform)
    }

    fun dismissCategoryDraft() {
        categoryDraft.value = null
    }

    fun saveCategoryDraft() {
        val draft = categoryDraft.value ?: return
        if (draft.name.isBlank()) {
            message.value = "La categoría necesita un nombre."
            return
        }
        viewModelScope.launch {
            runCatching {
                if (draft.isEditing) {
                    household.updateCategory(draft.id, draft.name, draft.emoji, draft.colorHex)
                } else {
                    household.addCategory(draft.name, draft.emoji, draft.colorHex)
                }
            }.onSuccess {
                message.value = "Categoría guardada."
                categoryDraft.value = null
            }.onFailure { message.value = it.message ?: "No se pudo guardar la categoría" }
        }
    }

    fun toggleCategoryArchived(category: HouseholdCategory) {
        viewModelScope.launch {
            runCatching { household.setCategoryArchived(category.id, !category.isArchived) }
                .onSuccess {
                    message.value = if (category.isArchived) "Categoría reactivada." else "Categoría archivada."
                }
                .onFailure { message.value = it.message ?: "No se pudo actualizar" }
        }
    }

    // Cards

    fun startCard(id: Long? = null) {
        val existing = id?.let { target -> uiState.value.cards.firstOrNull { it.id == target } }
        cardDraft.value = existing?.let(CardDraft::from) ?: CardDraft()
    }

    fun updateCardDraft(transform: (CardDraft) -> CardDraft) {
        cardDraft.value = cardDraft.value?.let(transform)
    }

    fun dismissCardDraft() {
        cardDraft.value = null
    }

    fun saveCardDraft() {
        val draft = cardDraft.value ?: return
        if (draft.name.isBlank()) {
            message.value = "La tarjeta necesita un nombre."
            return
        }
        if (draft.closingDay !in 1..31 || draft.dueDay !in 1..31) {
            message.value = "Cierre y vencimiento deben estar entre 1 y 31."
            return
        }
        viewModelScope.launch {
            runCatching {
                household.upsertCard(
                    CreditCard(
                        id = draft.id,
                        name = draft.name.trim(),
                        issuer = draft.issuer.trim(),
                        brand = draft.brand.trim().ifBlank { "VISA" },
                        closingDay = draft.closingDay,
                        dueDay = draft.dueDay,
                        colorHex = draft.colorHex,
                        isActive = draft.isActive,
                    ),
                )
            }.onSuccess {
                message.value = "Tarjeta guardada."
                cardDraft.value = null
            }.onFailure { message.value = it.message ?: "No se pudo guardar la tarjeta" }
        }
    }

    fun toggleCardActive(card: CreditCard) {
        viewModelScope.launch {
            runCatching { household.setCardActive(card.id, !card.isActive) }
                .onSuccess {
                    message.value = if (card.isActive) "Tarjeta desactivada." else "Tarjeta activada."
                }
                .onFailure { message.value = it.message ?: "No se pudo actualizar" }
        }
    }

    // Members

    fun startMember(id: Long? = null) {
        val existing = id?.let { target -> uiState.value.members.firstOrNull { it.id == target } }
        memberDraft.value = MemberDraft(id = existing?.id ?: 0, name = existing?.name.orEmpty())
    }

    fun updateMemberDraft(value: String) {
        memberDraft.value = memberDraft.value?.copy(name = value)
    }

    fun dismissMemberDraft() {
        memberDraft.value = null
    }

    fun saveMemberDraft() {
        val draft = memberDraft.value ?: return
        val value = draft.name.trim()
        if (value.isBlank()) {
            message.value = "El nombre no puede estar vacío."
            return
        }
        viewModelScope.launch {
            runCatching {
                if (draft.id > 0) {
                    household.renameMember(draft.id, value)
                } else {
                    household.addMember(value)
                }
            }.onSuccess {
                message.value = "Integrante guardado."
                memberDraft.value = null
            }.onFailure { message.value = it.message ?: "No se pudo guardar el integrante" }
        }
    }

    fun toggleMemberActive(member: HouseholdMember) {
        viewModelScope.launch {
            runCatching { household.setMemberActive(member.id, !member.isActive) }
                .onSuccess {
                    message.value = if (member.isActive) "${member.name} desactivado." else "${member.name} activado."
                }
                .onFailure { message.value = it.message ?: "No se pudo actualizar" }
        }
    }

    // Settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(uiState.value.settings)
        viewModelScope.launch {
            runCatching { preferences.update(updated) }
                .onFailure { message.value = it.message ?: "No se pudo guardar la configuración" }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { preferences.setNotificationsEnabled(enabled) }
                .onFailure { message.value = it.message ?: "No se pudo guardar" }
        }
    }

    fun clearMessage() {
        message.value = null
    }

    fun exportBackup(onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { backupManager.exportBackupJson() }
                .onSuccess { json ->
                    onReady(json)
                    message.value = "Respaldo JSON exportado correctamente."
                }
                .onFailure { message.value = it.message ?: "No se pudo exportar el respaldo" }
        }
    }

    fun importBackup(rawJson: String) {
        viewModelScope.launch {
            runCatching { backupManager.importBackupJson(rawJson) }
                .onSuccess { summary -> message.value = summary }
                .onFailure { message.value = it.message ?: "El archivo de respaldo no es válido" }
        }
    }

    fun syncBrokerMovements() {
        viewModelScope.launch {
            runCatching { backupManager.syncExportedBrokerHoldings() }
                .onSuccess { summary -> message.value = summary }
                .onFailure { message.value = it.message ?: "No se pudieron sincronizar los movimientos" }
        }
    }
}

private data class SettingsDrafts(
    val category: CategoryDraft?,
    val card: CardDraft?,
    val member: MemberDraft?,
    val message: String?,
)
