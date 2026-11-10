@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.smartinvest.ui.investments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.model.WalletType
import com.smartinvest.domain.model.effectiveTnaPercent
import com.smartinvest.ui.components.AmountInput
import com.smartinvest.ui.components.ChipRow
import com.smartinvest.ui.components.DropdownField
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.theme.Violet
import com.smartinvest.ui.util.Fmt

@Composable
fun WalletsScreen(
    viewModel: WalletsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Billeteras y cuentas", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Topes remunerados y TNA por cuenta",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = viewModel::syncRates, enabled = !state.isWorking) {
                if (state.isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Emerald,
                    )
                } else {
                    Icon(Icons.Rounded.Sync, contentDescription = "Sincronizar", tint = Emerald)
                }
            }
            IconButton(onClick = viewModel::startNew) {
                Icon(Icons.Rounded.Add, contentDescription = "Agregar", tint = Emerald)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.message?.let { message ->
                item {
                    Column {
                        InfoBanner(message, color = Sky)
                        TextButton(onClick = viewModel::clearMessage) { Text("Cerrar") }
                    }
                }
            }

            item {
                InfoBanner(
                    "Configurá el tope remunerado real de cada billetera. Dejalo vacío si el saldo completo rinde TNA. " +
                        "La sincronización usa Comparatasas; si falla, la TNA manual se conserva.",
                    color = Sky,
                )
            }

            if (state.wallets.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Add,
                        title = "Sin cuentas",
                        message = "Agregá Mercado Pago, Ualá, FCI eco, tu bróker y banco para estimar intereses.",
                    )
                }
            } else {
                items(state.wallets, key = { it.id }) { wallet ->
                    WalletCard(
                        wallet = wallet,
                        linkedHolding = state.holdings.firstOrNull { it.id == wallet.linkedHoldingId },
                        effectiveTnaPercent = wallet.effectiveTnaPercent(state.holdings),
                        onEdit = { viewModel.startEdit(wallet) },
                        onToggle = { viewModel.setActive(wallet, it) },
                    )
                }
            }

            item {
                Button(
                    onClick = viewModel::startNew,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Agregar cuenta")
                }
            }
        }
    }

    state.draft?.let { draft ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDraft,
            title = { Text(if (draft.isEditing) "Editar cuenta" else "Nueva cuenta") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column {
                        Text(
                            "Presets con datos reales",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            WalletPresets.forEach { preset ->
                                AssistChip(
                                    onClick = { viewModel.applyPreset(preset) },
                                    label = { Text(preset.name) },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(name = value) } },
                        label = { Text("Nombre") },
                        placeholder = { Text("Mercado Pago") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.institution,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(institution = value) } },
                        label = { Text("Institución") },
                        placeholder = { Text("Mercado Pago, Adcap, Galicia…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val selectedType = draft.type
                    DropdownField(
                        label = "Tipo de cuenta",
                        selected = selectedType,
                        options = WalletType.entries,
                        optionLabel = { it.label },
                        onSelect = { value -> viewModel.updateDraft { it.copy(type = value) } },
                    )
                    Column {
                        Text(
                            "Moneda",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        ChipRow(
                            options = CurrencyCode.entries,
                            selected = draft.currency,
                            label = { it.label },
                            onSelect = { value -> viewModel.updateDraft { it.copy(currency = value) } },
                        )
                    }
                    AmountInput(
                        value = draft.balanceText,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(balanceText = value) } },
                        label = "Saldo actual",
                        prefix = if (draft.currency == CurrencyCode.USD) "US$" else "$",
                    )
                    OutlinedTextField(
                        value = draft.tnaText,
                        onValueChange = { value ->
                            if (value.isEmpty() || value.all { it.isDigit() || it == ',' || it == '.' }) {
                                viewModel.updateDraft { it.copy(tnaText = value) }
                            }
                        },
                        label = { Text("TNA %") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AmountInput(
                        value = draft.capText,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(capText = value) } },
                        label = "Tope remunerado (vacío = sin tope)",
                        prefix = if (draft.currency == CurrencyCode.USD) "US$" else "$",
                    )
                    if (state.fciHoldings.isNotEmpty()) {
                        Column {
                            Text(
                                "FCI vinculado (opcional)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            ChipRow(
                                options = listOf<Long?>(null) + state.fciHoldings.map { it.id },
                                selected = draft.linkedHoldingId,
                                label = { id ->
                                    if (id == null) {
                                        "Sin vínculo"
                                    } else {
                                        state.fciHoldings.firstOrNull { it.id == id }
                                            ?.let { it.symbol.ifBlank { it.name } }
                                            ?: "FCI"
                                    }
                                },
                                onSelect = { id ->
                                    viewModel.updateDraft { it.copy(linkedHoldingId = id) }
                                },
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Si el FCI tiene TNA cargada, esa tasa reemplaza la TNA manual de la cuenta.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft.notes,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(notes = value) } },
                        label = { Text("Notas") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Cuenta activa",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = draft.isActive,
                            onCheckedChange = { value -> viewModel.updateDraft { it.copy(isActive = value) } },
                        )
                    }
                    state.error?.let { error ->
                        InfoBanner(error, color = Rose)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::saveDraft, enabled = !state.isWorking) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDraft) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun WalletCard(
    wallet: Wallet,
    linkedHolding: Holding?,
    effectiveTnaPercent: Double,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        wallet.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        wallet.institution.ifBlank { wallet.type.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = wallet.isActive, onCheckedChange = onToggle)
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = "Editar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            FlowRow(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TagPill(wallet.type.label, Sky)
                TagPill(wallet.currency.symbol, Emerald)
                TagPill("TNA ${Fmt.rate(effectiveTnaPercent)}", Amber)
                if (linkedHolding != null) {
                    TagPill("FCI ${linkedHolding.symbol.ifBlank { linkedHolding.name }}", Violet)
                }
                if (!wallet.isActive) TagPill("Inactiva", Rose)
            }
            if (linkedHolding != null) {
                Text(
                    if ((linkedHolding.tnaPercent ?: 0.0) > 0.0) {
                        "TNA tomada del FCI vinculado."
                    } else {
                        "FCI vinculado sin TNA: se mantiene la TNA manual."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if ((linkedHolding.tnaPercent ?: 0.0) > 0.0) Violet else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Saldo",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(Fmt.ars(wallet.balanceCents), style = MaterialTheme.typography.titleSmall)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Tope remunerado",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (wallet.remuneratedCapCents > 0) Fmt.ars(wallet.remuneratedCapCents) else "Sin tope",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (wallet.remuneratedCapCents > 0) Amber else Emerald,
                    )
                }
            }
            if (wallet.notes.isNotBlank()) {
                Text(
                    wallet.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                "Fuente de tasa: ${wallet.rateSource}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
