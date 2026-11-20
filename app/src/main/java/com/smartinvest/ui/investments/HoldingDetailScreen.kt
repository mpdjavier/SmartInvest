package com.smartinvest.ui.investments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.engine.BrokerMovementImporter
import com.smartinvest.domain.engine.PortfolioReconciliationEngine
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HoldingMovement
import com.smartinvest.domain.model.HoldingMovementType
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.ui.components.AmountInput
import com.smartinvest.ui.components.ConfirmDialog
import com.smartinvest.ui.components.EmptyState
import com.smartinvest.ui.components.HeroAmountText
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.StatTile
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.PlexMono
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.theme.Violet
import com.smartinvest.ui.util.Fmt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

data class HoldingDetailUiState(
    val holding: Holding? = null,
    val movements: List<HoldingMovement> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val savedTick: Long = 0L,
) {
    val totalIncomeCents: Long
        get() = movements.filter { it.isIncome }.sumOf { it.amountCents }
    val totalExpenseCents: Long
        get() = movements.filterNot { it.isIncome }.sumOf { it.amountCents }
    val netCents: Long get() = totalIncomeCents - totalExpenseCents
}

@HiltViewModel
class HoldingDetailViewModel @Inject constructor(
    private val investments: InvestmentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val holdingId: Long = savedStateHandle.get<String>("holdingId")?.toLongOrNull() ?: 0L

    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val savedTick = MutableStateFlow(0L)

    private val data = combine(
        investments.observeHoldings(),
        investments.observeHoldingMovements(holdingId),
    ) { holdings, movements ->
        holdings.firstOrNull { it.id == holdingId } to movements
    }

    val uiState = combine(data, working, message, error, savedTick) { pair, isWorking, msg, err, tick ->
        HoldingDetailUiState(
            holding = pair.first,
            movements = pair.second,
            isLoading = false,
            isSaving = isWorking,
            message = msg,
            error = err,
            savedTick = tick,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HoldingDetailUiState())

    fun addMovement(type: HoldingMovementType, amountText: String, date: LocalDate, note: String) {
        if (working.value) return
        val amountCents = MoneyParser.parseToCents(amountText)
        if (amountCents == null || amountCents <= 0L) {
            error.value = "Ingresá un monto válido para el ${type.label.lowercase()}"
            return
        }
        viewModelScope.launch {
            working.value = true
            error.value = null
            message.value = null
            runCatching {
                investments.addHoldingMovement(
                    holdingId = holdingId,
                    type = type,
                    amountCents = amountCents,
                    date = date,
                    note = note,
                )
            }.onSuccess {
                savedTick.value = System.currentTimeMillis()
                message.value = "${type.label} registrado · valuación actualizada."
            }.onFailure { err ->
                error.value = err.message ?: "No se pudo registrar el movimiento"
            }
            working.value = false
        }
    }

    fun deleteMovement(id: Long) {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            error.value = null
            message.value = null
            runCatching { investments.deleteHoldingMovement(id) }
                .onSuccess { message.value = "Movimiento eliminado · valuación revertida." }
                .onFailure { err -> error.value = err.message ?: "No se pudo eliminar el movimiento" }
            working.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }

    fun clearError() {
        error.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingDetailScreen(
    onEditHolding: (Long) -> Unit,
    viewModel: HoldingDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val holding = state.holding
    var dialogType by remember { mutableStateOf<HoldingMovementType?>(null) }
    var pendingDelete by remember { mutableStateOf<HoldingMovement?>(null) }

    LaunchedEffect(state.savedTick) {
        if (state.savedTick > 0L) dialogType = null
    }

    val currency = holding?.currency ?: CurrencyCode.ARS
    val money = { cents: Long ->
        if (currency == CurrencyCode.USD) Fmt.usd(cents) else Fmt.ars(cents)
    }
    val signedMoney = { cents: Long ->
        if (currency == CurrencyCode.USD) Fmt.signedUsd(cents) else Fmt.signedArs(cents)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.isLoading) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = Emerald, modifier = Modifier.size(24.dp))
                }
            }
            return@LazyColumn
        }

        if (holding == null) {
            item {
                EmptyState(
                    icon = Icons.AutoMirrored.Rounded.TrendingUp,
                    title = "Activo no encontrado",
                    message = "Puede que se haya eliminado desde otra pantalla.",
                )
            }
            return@LazyColumn
        }

        item {
            SmartCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                PortfolioReconciliationEngine.displayLabel(holding),
                                style = MaterialTheme.typography.titleLarge.copy(fontFamily = PlexMono),
                            )
                            Spacer(Modifier.width(8.dp))
                            TagPill(holding.assetType.label, assetColor(holding.assetType))
                            if (holding.isWallet) {
                                Spacer(Modifier.width(6.dp))
                                TagPill("Billetera T+0", Emerald)
                            }
                        }
                        Text(
                            holding.name.ifBlank { holding.assetType.label },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onEditHolding(holding.id) }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Editar activo", tint = Emerald)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (holding.isWallet) "SALDO DISPONIBLE" else "VALUACIÓN ACTUAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HeroAmountText(money(holding.currentValueCents))
                Text(
                    holding.currency.label + " · " + if (holding.isTracked) holding.source.label else "Manual",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (holding.isWallet) {
                    Text(
                        "Cuenta transaccional de liquidez diaria. Los pagos y egresos descuentan el saldo " +
                            "sin generar costo de inversión ni ganancia/pérdida no realizada.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            label = "Costo",
                            value = money(holding.costCents),
                            accent = Sky,
                            modifier = Modifier.weight(1f),
                        )
                        val gain = holding.currentValueCents - holding.costCents
                        StatTile(
                            label = "Ganancia no realizada",
                            value = signedMoney(gain),
                            accent = if (gain >= 0) Emerald else Rose,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (holding.quantity > 0.0) {
                    ThinDivider()
                    KeyValueRow(
                        "Cantidad / cuotapartes",
                        String.format(java.util.Locale.forLanguageTag("es-AR"), "%.2f", holding.quantity),
                    )
                }
                holding.maturityDate?.let { maturity ->
                    KeyValueRow("Vencimiento", Fmt.longDate(maturity), valueColor = Amber)
                }
                val cleanHumanNotes = PortfolioReconciliationEngine
                    .withManualValuationLock(holding.notes, locked = false)
                    .lineSequence()
                    .filter { line -> BrokerMovementImporter.parseMovementNotes(line).isEmpty() }
                    .joinToString("\n")
                    .trim()
                if (cleanHumanNotes.isNotBlank()) {
                    ThinDivider()
                    Text(
                        cleanHumanNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.message?.let { msg ->
            item {
                Column {
                    InfoBanner(msg, color = Emerald)
                    TextButton(onClick = viewModel::clearMessage) { Text("Cerrar") }
                }
            }
        }

        state.error?.let { err ->
            item {
                Column {
                    InfoBanner(err, color = Rose)
                    TextButton(onClick = viewModel::clearError) { Text("Cerrar") }
                }
            }
        }

        item {
            SmartCard {
                SectionHeader(
                    title = "Movimientos del activo",
                    subtitle = "Ingresos y egresos manuales · ${state.movements.size} registros",
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { dialogType = HoldingMovementType.INGRESO },
                        enabled = !state.isSaving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.TrendingUp, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ingreso")
                    }
                    OutlinedButton(
                        onClick = { dialogType = HoldingMovementType.EGRESO },
                        enabled = !state.isSaving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.TrendingDown, null, modifier = Modifier.size(18.dp), tint = Rose)
                        Spacer(Modifier.width(6.dp))
                        Text("Egreso", color = Rose)
                    }
                }
                ThinDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = "Ingresos",
                        value = money(state.totalIncomeCents),
                        accent = Emerald,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Egresos",
                        value = money(state.totalExpenseCents),
                        accent = Rose,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Neto",
                        value = signedMoney(state.netCents),
                        accent = if (state.netCents >= 0) Emerald else Rose,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (state.movements.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Add,
                    title = "Sin movimientos registrados",
                    message = "Agregá un ingreso o egreso para llevar el histórico de este activo. " +
                        "La valuación, el costo y la cantidad se actualizan solos.",
                )
            }
        } else {
            item {
                SectionHeader(
                    title = "Histórico",
                    subtitle = "Más reciente primero · tocá el tacho para revertir un movimiento",
                )
            }
            items(state.movements, key = { it.id }) { movement ->
                MovementRow(
                    movement = movement,
                    signedMoney = signedMoney,
                    onDelete = { pendingDelete = movement },
                )
            }
        }
    }

    dialogType?.let { type ->
        MovementEditorDialog(
            type = type,
            currency = currency,
            isSaving = state.isSaving,
            error = state.error,
            onDismiss = {
                dialogType = null
                viewModel.clearError()
            },
            onConfirm = { amount, date, note ->
                viewModel.addMovement(type, amount, date, note)
            },
        )
    }

    pendingDelete?.let { movement ->
        ConfirmDialog(
            title = "Revertir movimiento",
            message = "Se eliminará el ${movement.type.label.lowercase()} de " +
                "${money(movement.amountCents)} del ${Fmt.longDate(movement.date)} y se ajustará la valuación.",
            confirmLabel = "Revertir",
            onConfirm = {
                viewModel.deleteMovement(movement.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MovementEditorDialog(
    type: HoldingMovementType,
    currency: CurrencyCode,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (amountText: String, date: LocalDate, note: String) -> Unit,
) {
    var amountText by remember(type) { mutableStateOf("") }
    var note by remember(type) { mutableStateOf("") }
    var date by remember(type) { mutableStateOf(DateUtils.today()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val isIncome = type == HoldingMovementType.INGRESO

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let {
                            date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isIncome) "Registrar ingreso" else "Registrar egreso") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (isIncome) {
                        "Sumá dinero al activo. Aumenta la valuación y el costo registrado."
                    } else {
                        "Retirá dinero del activo. Baja la valuación y ajusta el costo en forma proporcional."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AmountInput(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = "Monto en ${currency.label}",
                    prefix = if (currency == CurrencyCode.USD) "US$" else "$",
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota (opcional)") },
                    placeholder = { Text("Aporte mensual, rescate, etc.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Fecha: ${Fmt.longDate(date)}")
                }
                if (error != null) {
                    InfoBanner(error, color = Rose)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(amountText, date, note) },
                enabled = !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Emerald,
                    )
                } else {
                    Text("Guardar", color = if (isIncome) Emerald else Rose)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancelar") }
        },
    )
}

@Composable
private fun MovementRow(
    movement: HoldingMovement,
    signedMoney: (Long) -> String,
    onDelete: () -> Unit,
) {
    val color = if (movement.isIncome) Emerald else Rose
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagPill(movement.type.label, color)
                if (movement.expenseId != null) {
                    Spacer(Modifier.width(6.dp))
                    TagPill("Hogar", Sky)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    Fmt.longDate(movement.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (movement.note.isNotBlank()) {
                Text(
                    movement.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            signedMoney(if (movement.isIncome) movement.amountCents else -movement.amountCents),
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = PlexMono),
            color = color,
            maxLines = 1,
            softWrap = false,
        )
        if (movement.expenseId == null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "Revertir movimiento",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun assetColor(type: AssetType): Color = when (type) {
    AssetType.LECAP, AssetType.BONCAP -> Emerald
    AssetType.BOND, AssetType.DOLAR_MEP -> Sky
    AssetType.FCI -> Violet
    AssetType.CEDEAR, AssetType.CRYPTO -> Amber
    AssetType.OTHER -> Sky
}
