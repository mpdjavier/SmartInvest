@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.smartinvest.ui.household

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.domain.model.ExpensePeriod
import com.smartinvest.domain.model.ExpenseRecord
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.ui.components.ChipRow
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.PlexMono
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.util.Fmt
import com.smartinvest.ui.util.colorFromHex
import java.time.YearMonth

/**
 * Search every expense by text, category, payment method, member and period.
 * Installments are listed with their due month, matching the report totals.
 */
@Composable
fun ExpenseSearchScreen(
    onEditExpense: (YearMonth, Long) -> Unit,
    viewModel: ExpenseSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val categoriesById = state.categories.associateBy { it.id }
    val membersById = state.members.associateBy { it.id }
    val cardsById = state.cards.associateBy { it.id }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.filter.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Buscar gastos") },
                placeholder = { Text("Descripción, nota, categoría, tarjeta…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.filter.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Borrar búsqueda")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
        }

        item {
            ChipRow(
                options = ExpensePeriod.entries.toList(),
                selected = state.filter.period,
                label = { it.label },
                onSelect = viewModel::setPeriod,
            )
        }

        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MenuFilterChip(
                    label = "Categoría",
                    selectedLabel = state.filter.categoryId
                        ?.let { id -> categoriesById[id]?.let { "${it.emoji} ${it.name}" } },
                    options = state.categories,
                    optionLabel = { "${it.emoji} ${it.name}" },
                    onSelect = { category -> viewModel.setCategory(category?.id) },
                )
                MenuFilterChip(
                    label = "Medio de pago",
                    selectedLabel = state.filter.paymentMethod?.label,
                    options = state.paymentMethods,
                    optionLabel = { it.label },
                    onSelect = viewModel::setPaymentMethod,
                )
                MenuFilterChip(
                    label = "Integrante",
                    selectedLabel = state.filter.memberId
                        ?.let { id -> membersById[id]?.name },
                    options = state.members,
                    optionLabel = { it.name },
                    onSelect = { member -> viewModel.setMember(member?.id) },
                )
                if (state.filter.hasActiveFilters) {
                    TextButton(onClick = viewModel::clearFilters) {
                        Text("Limpiar", color = Amber)
                    }
                }
            }
        }

        item {
            ResultsSummary(state)
        }

        if (state.results.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                    title = if (state.filter.hasActiveFilters) "Sin coincidencias" else "Sin gastos en el período",
                    message = if (state.filter.hasActiveFilters) {
                        "Probá con otra descripción o quitá algunos filtros."
                    } else {
                        "Cambiá el período o cargá gastos para empezar a buscar."
                    },
                )
            }
        } else {
            items(
                items = state.results,
                key = { "${it.expenseId}-${it.installmentId ?: 0}" },
            ) { record ->
                ExpenseResultRow(
                    record = record,
                    category = categoriesById[record.categoryId],
                    member = membersById[record.paidByMemberId],
                    cardName = record.creditCardId?.let { cardsById[it]?.name },
                    onClick = { onEditExpense(record.month, record.expenseId) },
                )
            }
        }
    }
}

@Composable
private fun ResultsSummary(state: ExpenseSearchUiState) {
    SmartCard(contentPadding = PaddingValues(14.dp)) {
        SectionHeader(
            title = "Resultados",
            subtitle = "${state.results.size} movimientos · ${Fmt.ars(state.totalCents)}",
            action = {
                if (state.monthBuckets.size > 1) {
                    TagPill("${state.monthBuckets.size} meses", Sky)
                }
            },
        )
        if (state.monthBuckets.size > 1) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                state.monthBuckets.take(6).forEach { bucket ->
                    TagPill("${Fmt.month(bucket.month)} · ${Fmt.compact(bucket.totalCents)}", Emerald)
                }
            }
        }
    }
}

@Composable
private fun ExpenseResultRow(
    record: ExpenseRecord,
    category: HouseholdCategory?,
    member: HouseholdMember?,
    cardName: String?,
    onClick: () -> Unit,
) {
    val categoryColor = colorFromHex(category?.colorHex ?: "#38BDF8", Sky)
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(categoryColor),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        record.description,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(category?.let { "${it.emoji} ${it.name}" } ?: "Sin categoría")
                            append(" · ")
                            append(member?.name ?: "Integrante eliminado")
                            append(" · ")
                            append(record.paymentMethod.shortLabel)
                            append(" · ")
                            append(Fmt.shortDate(record.date))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Fmt.ars(record.impactCents),
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = PlexMono),
                        maxLines = 1,
                    )
                    if (record.isInstallment) {
                        Text(
                            "${record.installmentLabel} · ${Fmt.month(record.month)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Amber,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (record.isInstallment || cardName != null) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    record.installmentLabel?.let { TagPill(it, Amber) }
                    cardName?.let { TagPill(it, Sky) }
                    if (record.totalCents != record.impactCents) {
                        TagPill("Total ${Fmt.compact(record.totalCents)}", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (record.notes.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    record.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * FilterChip that opens a menu. Passing `null` from the menu clears that filter.
 */
@Composable
private fun <T> MenuFilterChip(
    label: String,
    selectedLabel: String?,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selectedLabel != null,
            onClick = { expanded = true },
            label = {
                Text(
                    selectedLabel ?: label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Todos") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
