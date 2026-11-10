package com.smartinvest.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.data.importer.ReceiptKind
import com.smartinvest.ui.components.AmountInput
import com.smartinvest.ui.components.ChipRow
import com.smartinvest.ui.components.DateField
import com.smartinvest.ui.components.DropdownField
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.util.Fmt
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptImportScreen(
    onBack: () -> Unit,
    viewModel: ReceiptImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form = state.form
    var showDatePicker by remember { mutableStateOf(false) }
    var showRawText by remember { mutableStateOf(false) }

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

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Emerald)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Comprobante importado", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Revisá los datos detectados. Podés corregir todo antes de guardar el gasto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.extractionFailed) {
            item {
                InfoBanner(
                    "No se pudo leer texto del archivo. Puede ser un PDF escaneado o una imagen. " +
                        "Completá los datos a mano para registrar el gasto.",
                    color = Rose,
                )
            }
        }

        state.parsed?.let { receipt ->
            item {
                SmartCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                receipt.institutionLabel ?: "Comprobante",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                receipt.kind.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TagPill(
                            text = when (receipt.kind) {
                                ReceiptKind.TRANSFER -> "Transferencia"
                                ReceiptKind.EXPENSE -> "Pago"
                                ReceiptKind.UNKNOWN -> "Revisar"
                            },
                            color = when (receipt.kind) {
                                ReceiptKind.TRANSFER -> Sky
                                ReceiptKind.EXPENSE -> Emerald
                                ReceiptKind.UNKNOWN -> Amber
                            },
                        )
                    }
                    ThinDivider()
                    Text(
                        buildString {
                            append("Importe detectado: ")
                            append(receipt.amountCents?.let { Fmt.ars(it) } ?: "no detectado")
                            append(" · Fecha: ")
                            append(receipt.date?.let { Fmt.longDate(it) } ?: "no detectada")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (receipt.reference != null) {
                        Text(
                            "Referencia: ${receipt.reference}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showRawText = !showRawText }) {
                        Text(if (showRawText) "Ocultar texto detectado" else "Ver texto detectado")
                    }
                    if (showRawText) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                receipt.rawText.take(4_000),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            receipt.warnings.forEach { warning ->
                item {
                    val isOwnTransferWarning = warning.contains("cuentas propias", ignoreCase = true)
                    InfoBanner(
                        warning,
                        color = if (isOwnTransferWarning) Amber else Sky,
                    )
                }
            }
        }

        item {
            SmartCard {
                SectionHeader(
                    title = "Datos del gasto",
                    subtitle = "Se guarda en el mes de la fecha elegida",
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = form.description,
                    onValueChange = viewModel::setDescription,
                    label = { Text("Descripción") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                AmountInput(
                    value = form.amountText,
                    onValueChange = viewModel::setAmount,
                    label = "Monto",
                )
                Spacer(Modifier.height(10.dp))
                DateField(
                    label = "Fecha del gasto",
                    value = Fmt.longDate(form.date),
                    onClick = { showDatePicker = true },
                )
                Spacer(Modifier.height(10.dp))
                val selectedCategory = state.categories.firstOrNull { it.id == form.categoryId }
                    ?: state.categories.firstOrNull()
                if (selectedCategory != null) {
                    DropdownField(
                        label = "Categoría",
                        selected = selectedCategory,
                        options = state.categories,
                        optionLabel = { "${it.emoji} ${it.name}" },
                        onSelect = { viewModel.setCategory(it.id) },
                    )
                } else {
                    InfoBanner("No hay categorías activas. Creá una desde Ajustes.", color = Amber)
                }
                Spacer(Modifier.height(10.dp))
                val selectedMember = state.members.firstOrNull { it.id == form.memberId }
                    ?: state.members.firstOrNull()
                if (selectedMember != null) {
                    Text(
                        "¿Quién pagó?",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = state.members,
                        selected = selectedMember,
                        label = { it.name },
                        onSelect = { viewModel.setMember(it.id) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = form.notes,
                    onValueChange = viewModel::setNotes,
                    label = { Text("Notas") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        }

        item {
            SmartCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Es una transferencia entre cuentas propias",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Si está activo, la app no registra un gasto del hogar: solo moverías plata entre tus cuentas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = form.isOwnTransfer,
                        onCheckedChange = viewModel::setOwnTransfer,
                    )
                }
            }
        }

        form.error?.let { error ->
            item { InfoBanner(error, color = Rose) }
        }

        item {
            Button(
                onClick = viewModel::save,
                enabled = !form.isSaving && !form.isOwnTransfer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (form.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Guardando…")
                } else {
                    Text("Registrar gasto")
                }
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Cancelar")
            }
        }
    }
}
