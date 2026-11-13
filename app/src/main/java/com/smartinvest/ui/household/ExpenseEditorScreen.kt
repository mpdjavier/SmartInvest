package com.smartinvest.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.domain.engine.CardCycleResolver
import com.smartinvest.domain.engine.InstallmentEngine
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.ui.components.AmountInput
import com.smartinvest.ui.components.ChipRow
import com.smartinvest.ui.components.DateField
import com.smartinvest.ui.components.DropdownField
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.NumberStepper
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.util.Fmt
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseEditorScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ExpenseEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form = state.form
    val data = state.data

    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(form.saved) {
        if (form.saved) onBack()
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = form.date
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            viewModel.setDate(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                        showDatePicker = false
                    },
                ) { Text("Aceptar") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    val amount = MoneyParser.parseToCents(form.amountText) ?: 0L
    val card = data.cards.firstOrNull { it.id == form.cardId }
    val isCredit = form.paymentMethod == ExpensePaymentMethod.CREDIT
    val firstMonth = if (isCredit && card != null) {
        CardCycleResolver.firstInstallmentMonth(form.date, card.closingDay)
    } else {
        YearMonth.from(form.date)
    }
    val schedule = if (isCredit && amount > 0) {
        runCatching { InstallmentEngine.schedule(amount, form.installments, firstMonth) }.getOrNull().orEmpty()
    } else {
        emptyList()
    }
    val monthlyImpact = schedule.firstOrNull()?.amountCents ?: amount
    val lastMonth = schedule.lastOrNull()?.dueMonth

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InfoBanner(
            "Registrá solo gastos compartidos del hogar. Con tarjeta, el mes recibe únicamente la cuota.",
            color = Sky,
        )

        form.error?.let { error ->
            Column {
                InfoBanner(error, color = Rose)
                TextButton(onClick = viewModel::clearError) { Text("Cerrar") }
            }
        }

        OutlinedTextField(
            value = form.description,
            onValueChange = viewModel::setDescription,
            label = { Text("Descripción") },
            placeholder = { Text("Supermercado, expensas, nafta…") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )

        AmountInput(
            value = form.amountText,
            onValueChange = viewModel::setAmount,
            label = if (isCredit) "Monto total de la compra" else "Monto",
            supportingText = if (isCredit) "El total se reparte en cuotas; no impacta entero este mes." else null,
        )

        DateField(
            label = "Fecha del gasto",
            value = Fmt.longDate(form.date),
            onClick = { showDatePicker = true },
        )

        val selectedCategory = data.categories.firstOrNull { it.id == form.categoryId }
            ?: data.categories.firstOrNull()
        if (selectedCategory != null) {
            DropdownField(
                label = "Categoría",
                selected = selectedCategory,
                options = data.categories,
                optionLabel = { "${it.emoji} ${it.name}" },
                onSelect = { viewModel.setCategory(it.id) },
            )
        } else {
            InfoBanner("No hay categorías activas. Creá una desde Ajustes.", color = Amber)
        }

        Column {
            Text(
                "¿Quién pagó?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val selectedMember = data.members.firstOrNull { it.id == form.paidByMemberId }
                ?: data.members.firstOrNull()
            if (selectedMember != null) {
                ChipRow(
                    options = data.members,
                    selected = selectedMember,
                    label = { it.name },
                    onSelect = { viewModel.setMember(it.id) },
                )
            }
        }

        Column {
            Text(
                "Medio de pago",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            ChipRow(
                options = ExpensePaymentMethod.entries,
                selected = form.paymentMethod,
                label = { it.shortLabel },
                onSelect = viewModel::setPaymentMethod,
            )
        }

        if (isCredit) {
            SmartCard {
                SectionHeader(
                    title = "Compra en cuotas",
                    subtitle = "Motor de cuotas y cierre de tarjeta",
                )
                Spacer(Modifier.height(10.dp))
                if (data.cards.isEmpty()) {
                    InfoBanner("Necesitás una tarjeta activa para compras en cuotas.", color = Amber)
                    TextButton(onClick = onOpenSettings) { Text("Abrir Ajustes") }
                } else {
                    val selectedCard = card ?: data.cards.first()
                    DropdownField(
                        label = "Tarjeta",
                        selected = selectedCard,
                        options = data.cards,
                        optionLabel = { "${it.name} · cierre ${it.closingDay} · vence ${it.dueDay}" },
                        onSelect = { viewModel.setCard(it.id) },
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NumberStepper(
                            label = "Cuotas",
                            value = form.installments,
                            onChange = viewModel::setInstallments,
                        )
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "Primera cuota",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                Fmt.month(firstMonth),
                                style = MaterialTheme.typography.titleSmall,
                                color = Amber,
                            )
                        }
                    }
                    if (firstMonth != YearMonth.from(form.date)) {
                        Spacer(Modifier.height(8.dp))
                        InfoBanner(
                            "La compra quedó después del cierre (día ${selectedCard.closingDay}); " +
                                "la primera cuota se imputa en ${Fmt.month(firstMonth)}.",
                            color = Amber,
                        )
                    }
                }
            }
        }

        if (form.id > 0L && form.originalPaymentMethod != null && form.originalPaymentMethod != form.paymentMethod) {
            InfoBanner(
                "Al guardar se revierte el cobro anterior (${form.originalPaymentMethod.shortLabel}) " +
                    "y el gasto pasa a ${form.paymentMethod.shortLabel}.",
                color = Sky,
            )
        }

        if (form.paymentMethod == ExpensePaymentMethod.COMMON_FUND) {
            val effectiveFund = data.fundBalanceCents +
                if (form.originalPaymentMethod == ExpensePaymentMethod.COMMON_FUND) form.originalAmountCents else 0L
            SmartCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                Text("Fondo común", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                KeyValueRow("Saldo disponible", Fmt.ars(effectiveFund))
                KeyValueRow(
                    "Saldo después de la compra",
                    Fmt.ars((effectiveFund - amount).coerceAtLeast(0)),
                    valueColor = if (amount > effectiveFund) Rose else Emerald,
                )
                if (amount > effectiveFund) {
                    Spacer(Modifier.height(8.dp))
                    InfoBanner("El fondo no alcanza para este gasto.", color = Rose)
                }
            }
        }

        if (form.paymentMethod == ExpensePaymentMethod.WALLET_FCI) {
            val selectedWallet = data.walletHoldings.firstOrNull { it.id == form.walletHoldingId }
                ?: data.walletHoldings.firstOrNull()
            val restoredFromOriginal = if (
                form.originalPaymentMethod == ExpensePaymentMethod.WALLET_FCI &&
                form.originalWalletHoldingId == selectedWallet?.id
            ) {
                form.originalWalletDeltaCents
            } else {
                0L
            }
            val effectiveWalletBalance = (selectedWallet?.currentValueCents ?: 0L) + restoredFromOriginal
            SmartCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                Text("FCI billetera", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                if (data.walletHoldings.isEmpty()) {
                    InfoBanner(
                        "No hay un FCI marcado como billetera. Editá un FCI desde Cartera y activá " +
                            "\"Usar como FCI billetera\".",
                        color = Amber,
                    )
                } else {
                    if (data.walletHoldings.size > 1 && selectedWallet != null) {
                        DropdownField(
                            label = "FCI de pago",
                            selected = selectedWallet,
                            options = data.walletHoldings,
                            optionLabel = { "${it.symbol} · ${it.name}" },
                            onSelect = { viewModel.setWalletHolding(it.id) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    KeyValueRow(
                        "Saldo disponible",
                        Fmt.ars(effectiveWalletBalance),
                    )
                    KeyValueRow(
                        "Saldo después del gasto",
                        Fmt.ars((effectiveWalletBalance - amount).coerceAtLeast(0)),
                        valueColor = if (amount > effectiveWalletBalance) Rose else Emerald,
                    )
                    if (amount > effectiveWalletBalance) {
                        Spacer(Modifier.height(8.dp))
                        InfoBanner("El FCI billetera no alcanza para este gasto.", color = Rose)
                    }
                }
            }
        }

        OutlinedTextField(
            value = form.notes,
            onValueChange = viewModel::setNotes,
            label = { Text("Notas (opcional)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            shape = MaterialTheme.shapes.medium,
        )

        SmartCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
            Text("Vista previa del impacto", style = MaterialTheme.typography.titleMedium)
            ThinDivider()
            KeyValueRow(
                if (isCredit) "Cuota mensual" else "Impacto del mes",
                Fmt.ars(monthlyImpact),
                valueColor = if (isCredit) Amber else MaterialTheme.colorScheme.onSurface,
                strong = true,
            )
            if (isCredit) {
                KeyValueRow("Total de la compra", Fmt.ars(amount))
                KeyValueRow("Cuotas", "${form.installments} × ${Fmt.ars(monthlyImpact)}")
                if (lastMonth != null) {
                    KeyValueRow("Última cuota", Fmt.month(lastMonth))
                }
                Text(
                    "Este mes se descuenta solo ${Fmt.ars(monthlyImpact)}, no el total.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
            } else {
                Text(
                    "El gasto se imputa completo a ${Fmt.month(YearMonth.from(form.date))}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = viewModel::save,
            enabled = !form.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (form.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("Guardando…")
            } else {
                Text(if (form.id > 0L) "Guardar cambios" else "Guardar gasto")
            }
        }

        Text(
            "Consejo: cargá la fecha real de compra. El cierre define si la primera cuota cae este mes o el próximo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
