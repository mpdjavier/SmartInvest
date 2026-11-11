package com.smartinvest.ui.household

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.FundMovement
import com.smartinvest.domain.model.FundMovementType
import com.smartinvest.domain.model.HouseholdSummary
import com.smartinvest.domain.model.MemberSettlement
import com.smartinvest.domain.model.MonthlyExpenseItem
import com.smartinvest.domain.model.RegisteredSettlement
import com.smartinvest.domain.model.SettlementTransfer
import com.smartinvest.ui.components.AmountInput
import com.smartinvest.ui.components.ConfirmDialog
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.LinearBar
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
import com.smartinvest.ui.util.colorFromHex
import java.time.YearMonth

@Composable
fun HouseholdScreen(
    onNewExpense: (YearMonth) -> Unit,
    onFundMovement: (YearMonth) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HouseholdViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingExpense by remember { mutableStateOf<MonthlyExpenseItem?>(null) }
    var pendingFundMovement by remember { mutableStateOf<FundMovement?>(null) }
    var settlementDialogTransfer by remember { mutableStateOf<SettlementTransfer?>(null) }
    var showSettlementDialog by remember { mutableStateOf(false) }

    val categories = state.categories.associateBy { it.id }
    val members = state.members.associateBy { it.id }
    val cards = state.cards.associateBy { it.id }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
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

        state.message?.let { message ->
            item {
                Column {
                    InfoBanner(message, color = Sky)
                    TextButton(onClick = viewModel::clearMessage) { Text("Cerrar") }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onNewExpense(state.month) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Nuevo gasto")
                }
                OutlinedButton(
                    onClick = { onFundMovement(state.month) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Savings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Fondo")
                }
            }
        }

        state.summary?.let { summary ->
            item {
                SettlementCard(
                    summary = summary,
                    onOpenSettleDialog = { transfer ->
                        settlementDialogTransfer = transfer
                        showSettlementDialog = true
                    },
                    onReopenSettlement = viewModel::reopenSettlement,
                    onAdvanceMonth = viewModel::nextMonth,
                )
            }
        }

        item {
            SectionHeader(
                title = "Gastos del mes",
                subtitle = "${state.expenses.size} movimientos imputados",
            )
        }

        if (state.expenses.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                    title = "Sin gastos compartidos",
                    message = "Registrá supermercado, servicios, auto o cualquier gasto del hogar. " +
                        "Las compras con tarjeta se imputan por cuota.",
                )
            }
        } else {
            items(state.expenses, key = { it.expenseId }) { expense ->
                ExpenseCard(
                    expense = expense,
                    categoryName = categories[expense.categoryId]?.let { "${it.emoji} ${it.name}" } ?: "Categoría",
                    categoryColor = colorFromHex(categories[expense.categoryId]?.colorHex ?: "#38BDF8", Sky),
                    payerName = members[expense.paidByMemberId]?.name ?: "Integrante",
                    cardName = cards[expense.creditCardId]?.name,
                    onTogglePaid = { paid ->
                        expense.installmentId?.let { viewModel.setInstallmentPaid(it, paid) }
                    },
                    onDelete = { pendingExpense = expense },
                )
            }
        }

        item {
            ThinDivider()
        }

        item {
            SectionHeader(
                title = "Fondo común",
                subtitle = "Saldo ${Fmt.ars(state.fundBalanceCents)}",
                actionLabel = "Movimiento",
                onAction = { onFundMovement(state.month) },
            )
        }

        if (state.fundMovements.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Savings,
                    title = "Fondo sin movimientos",
                    message = "Cargá aportes o extracciones. Las compras pagadas con el fondo se descuentan automáticamente.",
                )
            }
        } else {
            items(state.fundMovements, key = { it.id }) { movement ->
                FundMovementCard(
                    movement = movement,
                    memberName = movement.memberId?.let { members[it]?.name } ?: "Fondo común",
                    onDelete = { pendingFundMovement = movement },
                )
            }
        }

        item {
            CardsCard(cards = state.cards, onOpenSettings = onOpenSettings)
        }
    }

    pendingExpense?.let { expense ->
        ConfirmDialog(
            title = "Eliminar gasto",
            message = if (expense.isInstallment) {
                "Se eliminará la compra completa y todas sus cuotas. Esta acción no se puede deshacer."
            } else {
                "Se eliminará \"${expense.description}\". Si se pagó con el fondo, el saldo se repone."
            },
            onConfirm = {
                viewModel.deleteExpense(expense.expenseId)
                pendingExpense = null
            },
            onDismiss = { pendingExpense = null },
        )
    }

    pendingFundMovement?.let { movement ->
        ConfirmDialog(
            title = "Eliminar movimiento",
            message = "Se eliminará \"${movement.description}\" del fondo común.",
            onConfirm = {
                viewModel.deleteFundMovement(movement.id)
                pendingFundMovement = null
            },
            onDismiss = { pendingFundMovement = null },
        )
    }

    if (showSettlementDialog) {
        SettlementDialog(
            month = state.month,
            transfer = settlementDialogTransfer,
            onConfirm = { amountText, note, advance ->
                viewModel.registerSettlement(
                    transfer = settlementDialogTransfer,
                    amountText = amountText,
                    note = note,
                    advanceToNextMonth = advance,
                )
                showSettlementDialog = false
            },
            onDismiss = { showSettlementDialog = false },
        )
    }
}

@Composable
private fun SettlementCard(
    summary: HouseholdSummary,
    onOpenSettleDialog: (SettlementTransfer?) -> Unit,
    onReopenSettlement: (Long) -> Unit,
    onAdvanceMonth: () -> Unit,
) {
    SmartCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Liquidación del mes", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Contribuciones netas ${Fmt.ars(summary.totalNetContributionsCents)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val (pillText, pillColor) = when {
                // A registered close does not hide a still-pending balance (partial payment).
                summary.mainTransfer != null -> "Saldo pendiente" to Amber
                summary.isMonthClosed -> "Mes saldado" to Emerald
                summary.isBalanced -> "Equilibrado" to Emerald
                else -> "A transferir" to Amber
            }
            TagPill(pillText, pillColor)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Gasto total",
                value = Fmt.compact(summary.totalSpendCents),
                caption = "Directo ${Fmt.ars(summary.directSpendCents)}",
                accent = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Cuotas del mes",
                value = Fmt.compact(summary.installmentSpendCents),
                caption = "${summary.installmentCount} cuotas · ${Fmt.ars(summary.pendingInstallmentsCents)} pend.",
                accent = Amber,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Cubierto por fondo",
                value = Fmt.compact(summary.fundSpendCents),
                caption = "No se imputa a nadie",
                accent = Sky,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Objetivo iguales",
                value = Fmt.compact(summary.fairShareCents),
                caption = "por integrante",
                accent = Emerald,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        val transfer = summary.mainTransfer
        when {
            // Pending transfers win over the registered close: a partial payment must
            // still show what is left to transfer.
            transfer != null -> {
                val alreadyPaid = summary.settlements
                    .filter { !it.isClosingMarker && it.amountCents > 0L }
                    .sumOf { it.amountCents }
                val prefix = if (alreadyPaid > 0L) {
                    "Ya se saldaron ${Fmt.ars(alreadyPaid)}. Queda pendiente: "
                } else {
                    ""
                }
                InfoBanner(
                    "${prefix}${transfer.fromName} debe transferir ${Fmt.ars(transfer.amountCents)} a " +
                        "${transfer.toName} para empatar a partes iguales.",
                    color = Amber,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onOpenSettleDialog(transfer) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Saldar deuda (${Fmt.ars(transfer.amountCents)})")
                }
                if (summary.settlements.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Pagos registrados este mes",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    summary.settlements.forEach { record ->
                        RegisteredSettlementRow(record = record, onReopen = { onReopenSettlement(record.id) })
                    }
                }
            }
            summary.isMonthClosed -> {
                val closedDate = summary.settlementDate?.let(Fmt::shortDate) ?: "hoy"
                InfoBanner(
                    "Cuentas saldadas manualmente el $closedDate. Podés arrancar de nuevo en el mes siguiente.",
                    color = Emerald,
                )
                Spacer(Modifier.height(10.dp))
                summary.settlements.forEach { record ->
                    RegisteredSettlementRow(record = record, onReopen = { onReopenSettlement(record.id) })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onAdvanceMonth,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Arrancar mes siguiente")
                }
            }
            summary.hasActivity -> {
                InfoBanner("Cuentas equilibradas. No hay transferencias pendientes.", color = Emerald)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { onOpenSettleDialog(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cerrar mes manualmente")
                }
            }
            else -> InfoBanner("Sin movimientos en el mes. El fondo común sigue disponible.", color = Sky)
        }
        if (summary.members.isNotEmpty()) {
            ThinDivider()
            Text(
                "Aportes netos por integrante",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val maxNet = summary.members.maxOfOrNull { it.netContributionCents }?.coerceAtLeast(1) ?: 1
            summary.members.forEach { member ->
                SettlementMemberRow(member, maxNet)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Fondo: aportes ${Fmt.ars(summary.fundContributionsCents)} · " +
                    "extracciones ${Fmt.ars(summary.fundWithdrawalsCents)} · saldo ${Fmt.ars(summary.fundBalanceCents)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RegisteredSettlementRow(
    record: RegisteredSettlement,
    onReopen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Emerald, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (record.isClosingMarker) {
                    record.label
                } else {
                    "${record.label} · ${Fmt.ars(record.amountCents)}"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            val detail = buildString {
                append(Fmt.longDate(record.date))
                if (record.note.isNotBlank()) {
                    append(" · ")
                    append(record.note)
                }
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onReopen) {
            Text("Reabrir", color = Rose)
        }
    }
}

@Composable
private fun SettlementDialog(
    month: YearMonth,
    transfer: SettlementTransfer?,
    onConfirm: (amountText: String, note: String, advanceToNextMonth: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultAmount = remember(transfer) {
        transfer?.let {
            String.format(java.util.Locale.forLanguageTag("es-AR"), "%.2f", it.amountCents / 100.0)
        }.orEmpty()
    }
    var amountText by remember(transfer) { mutableStateOf(defaultAmount) }
    var note by remember(transfer) { mutableStateOf("") }
    var advance by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (transfer != null) "Saldar cuentas de ${Fmt.month(month)}" else "Cerrar ${Fmt.month(month)}")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (transfer != null) {
                    Text(
                        "${transfer.fromName} le paga a ${transfer.toName} para dejar el período en cero.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AmountInput(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = "Monto transferido",
                    )
                } else {
                    Text(
                        "El mes ya está equilibrado. Al cerrarlo queda constancia de que hicieron cuentas.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota (opcional)") },
                    placeholder = { Text("Transferencia Mercado Pago / efectivo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = advance, onCheckedChange = { advance = it })
                    Text(
                        "Ir al mes siguiente al confirmar",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(amountText, note, advance) }) {
                Text("Confirmar cierre")
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
private fun SettlementMemberRow(member: MemberSettlement, maxNet: Long) {
    val color = colorFromHex(member.colorHex, Emerald)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(7.dp))
        Text(member.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Fmt.ars(member.netContributionCents),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Text(
                when {
                    member.differenceCents < 0 -> "Debe transferir ${Fmt.ars(-member.differenceCents)}"
                    member.differenceCents > 0 -> "Debe recibir ${Fmt.ars(member.differenceCents)}"
                    member.settledAdjustmentCents != 0L -> "Saldo saldado"
                    else -> "En equilibrio"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    member.differenceCents < 0 -> Rose
                    member.differenceCents > 0 -> Emerald
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    Spacer(Modifier.height(5.dp))
    LinearBar(
        fraction = (member.netContributionCents.toFloat() / maxNet.toFloat()).coerceIn(0f, 1f),
        color = color,
        height = 5,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpenseCard(
    expense: MonthlyExpenseItem,
    categoryName: String,
    categoryColor: Color,
    payerName: String,
    cardName: String?,
    onTogglePaid: (Boolean) -> Unit,
    onDelete: () -> Unit,
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
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(categoryColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(categoryName.take(2), style = MaterialTheme.typography.titleMedium, color = categoryColor)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        expense.description,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$payerName · ${expense.paymentMethod.shortLabel} · ${Fmt.shortDate(expense.date)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Fmt.ars(expense.monthlyCents),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (expense.isInstallment) {
                        Text(
                            "Total ${Fmt.ars(expense.totalCents)}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "Eliminar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val tags = buildList<Pair<String, Color>> {
                if (expense.isInstallment) {
                    add((expense.installmentLabel ?: "Cuota") to Amber)
                }
                if (expense.paymentMethod == ExpensePaymentMethod.COMMON_FUND) {
                    add("Fondo común" to Sky)
                }
                if (cardName != null) {
                    add(cardName to Amber)
                }
                add(categoryName to categoryColor)
            }
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tags.forEach { (label, color) -> TagPill(label, color) }
            }
            if (expense.isInstallment) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = expense.installmentPaid == true,
                        onCheckedChange = onTogglePaid,
                    )
                    Text(
                        if (expense.installmentPaid == true) "Cuota pagada" else "Marcar cuota pagada",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (expense.installmentPaid == true) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Emerald, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FundMovementCard(
    movement: FundMovement,
    memberName: String,
    onDelete: () -> Unit,
) {
    val (icon, color, sign) = when (movement.type) {
        FundMovementType.CONTRIBUTION -> Triple(Icons.Rounded.Add, Emerald, "+")
        FundMovementType.WITHDRAWAL -> Triple(Icons.Rounded.SwapHoriz, Rose, "−")
        FundMovementType.EXPENSE -> Triple(Icons.Rounded.ShoppingCart, Amber, "−")
    }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    movement.description,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${movement.type.label} · $memberName · ${Fmt.shortDate(movement.date)}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "$sign ${Fmt.ars(movement.amountCents)}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                color = color,
            )
            if (movement.expenseId == null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "Eliminar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CardsCard(cards: List<CreditCard>, onOpenSettings: () -> Unit) {
    SmartCard {
        SectionHeader(
            title = "Tarjetas y cierres",
            subtitle = "Configuración manual de cierre y vencimiento",
            actionLabel = "Gestionar",
            onAction = onOpenSettings,
        )
        Spacer(Modifier.height(8.dp))
        if (cards.isEmpty()) {
            Text(
                "Todavía no cargaste tarjetas. Cargalas en Ajustes para calcular la primera cuota según el cierre.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            cards.forEach { card ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(colorFromHex(card.colorHex, Amber).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.CreditCard,
                            contentDescription = null,
                            tint = colorFromHex(card.colorHex, Amber),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(card.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${card.issuer.ifBlank { card.brand }} · ${card.cycleLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TagPill(if (card.isActive) "Activa" else "Inactiva", if (card.isActive) Emerald else Rose)
                }
            }
        }
    }
}
