package com.smartinvest.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.domain.model.FundMovementType
import com.smartinvest.ui.components.AmountInput
import com.smartinvest.ui.components.ChipRow
import com.smartinvest.ui.components.DateField
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.util.Fmt
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundMovementScreen(
    onBack: () -> Unit,
    viewModel: FundMovementViewModel = hiltViewModel(),
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
            initialSelectedDateMillis = form.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let {
                            viewModel.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        showDatePicker = false
                    },
                ) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    val amount = MoneyParser.parseToCents(form.amountText) ?: 0L
    val resultingBalance = if (form.type == FundMovementType.CONTRIBUTION) {
        data.fundBalanceCents + amount
    } else {
        data.fundBalanceCents - amount
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InfoBanner(
            "El fondo común fondea gastos del hogar o del vehículo sin tocar el sueldo corriente. " +
                "Los aportes cuentan para la liquidación; las extracciones reducen tu aporte.",
            color = Sky,
        )

        form.error?.let { error ->
            Column {
                InfoBanner(error, color = Rose)
                TextButton(onClick = viewModel::clearError) { Text("Cerrar") }
            }
        }

        Column {
            Text(
                "Tipo de movimiento",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            ChipRow(
                options = listOf(FundMovementType.CONTRIBUTION, FundMovementType.WITHDRAWAL),
                selected = form.type,
                label = { it.label },
                onSelect = viewModel::setType,
            )
        }

        AmountInput(
            value = form.amountText,
            onValueChange = viewModel::setAmount,
            label = if (form.type == FundMovementType.CONTRIBUTION) "Monto del aporte" else "Monto de la extracción",
        )

        DateField(
            label = "Fecha",
            value = Fmt.longDate(form.date),
            onClick = { showDatePicker = true },
        )

        Column {
            Text(
                "Integrante",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val selectedMember = data.members.firstOrNull { it.id == form.memberId } ?: data.members.firstOrNull()
            if (selectedMember != null) {
                ChipRow(
                    options = data.members,
                    selected = selectedMember,
                    label = { it.name },
                    onSelect = { viewModel.setMember(it.id) },
                )
            }
        }

        OutlinedTextField(
            value = form.description,
            onValueChange = viewModel::setDescription,
            label = { Text("Descripción (opcional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )

        SmartCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
            Text("Impacto en el fondo", style = MaterialTheme.typography.titleMedium)
            ThinDivider()
            KeyValueRow("Saldo actual", Fmt.ars(data.fundBalanceCents))
            KeyValueRow(
                "Saldo resultante",
                Fmt.ars(resultingBalance),
                valueColor = if (resultingBalance < 0) Rose else Emerald,
                strong = true,
            )
            Text(
                "El fondo es un pozo compartido: las compras pagadas con fondo no se imputan a un integrante.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                Text(if (form.type == FundMovementType.CONTRIBUTION) "Guardar aporte" else "Guardar extracción")
            }
        }
    }
}
