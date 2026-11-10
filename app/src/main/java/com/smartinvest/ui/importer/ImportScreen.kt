package com.smartinvest.ui.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.data.importer.ReceiptInbox
import com.smartinvest.domain.importer.SplitHomePayer
import com.smartinvest.domain.repository.SplitHomeImportPreview
import com.smartinvest.domain.repository.SplitHomeImportReport
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.KeyValueRow
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImportScreen(
    onReceiptReady: (String) -> Unit = {},
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stagingReceipt by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri)
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            viewModel.fileError("No pude abrir el archivo seleccionado.")
        } else {
            viewModel.onFilePicked(name, bytes)
        }
    }

    val receiptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        stagingReceipt = true
        scope.launch {
            val staged = withContext(Dispatchers.IO) {
                ReceiptInbox.stage(context, uri, mimeType = context.contentResolver.getType(uri))
            }
            stagingReceipt = false
            if (staged == null) {
                viewModel.fileError("No pude abrir el comprobante seleccionado.")
            } else {
                onReceiptReady(staged.file.absolutePath)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Importar datos", style = MaterialTheme.typography.titleLarge)
        Text(
            "Traé los gastos compartidos que venías registrando en SplitHome o cargá un " +
                "comprobante de banco/billetera.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SmartCard {
            SectionHeader(
                title = "Comprobante de banco o billetera",
                subtitle = "PDF, texto o imagen",
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "También podés compartir el comprobante desde la app del banco o billetera y " +
                    "elegir SmartInvest. Si el archivo es una imagen o un PDF escaneado, vas a " +
                    "poder completar los datos a mano.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    receiptPicker.launch(arrayOf("application/pdf", "text/plain", "image/*"))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !stagingReceipt,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ReceiptLong,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (stagingReceipt) "Abriendo…" else "Elegir comprobante")
            }
        }

        state.error?.let { message ->
            Column {
                InfoBanner(message, color = Rose)
                TextButton(onClick = viewModel::reset) { Text("Empezar de nuevo") }
            }
        }

        SmartCard {
            SectionHeader(
                title = "Reporte de SplitHome",
                subtitle = "Archivo Excel .xlsx",
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Se importan los gastos de la hoja \"Gastos\" y el saldo inicial de " +
                    "\"Fondo Comun\". \"J\" se asigna a Vos, \"M\" a Mamá y \"Fondo\" queda " +
                    "como gasto pagado por el fondo común.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
            ) {
                Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.fileName == null) "Elegir archivo .xlsx" else "Elegir otro archivo")
            }
            state.fileName?.let { name ->
                Spacer(Modifier.height(10.dp))
                TagPill(text = name, color = Sky)
            }
            if (state.isLoading) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (state.isImporting) "Importando…" else "Leyendo el archivo…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.preview?.let { preview ->
            PreviewCard(
                preview = preview,
                duplicateConfirmed = state.duplicateConfirmed,
                onDuplicateChange = viewModel::setDuplicateConfirmed,
                canImport = state.canImport,
                isLoading = state.isLoading,
                onImport = viewModel::importNow,
            )
        }

        state.report?.let { report ->
            ReportCard(report = report, onReset = viewModel::reset)
        }
    }
}

@Composable
private fun PreviewCard(
    preview: SplitHomeImportPreview,
    duplicateConfirmed: Boolean,
    onDuplicateChange: (Boolean) -> Unit,
    canImport: Boolean,
    isLoading: Boolean,
    onImport: () -> Unit,
) {
    val parsed = preview.parsed
    val primary = parsed.expenses.count { it.payer == SplitHomePayer.PRIMARY }
    val secondary = parsed.expenses.count { it.payer == SplitHomePayer.SECONDARY }
    val fund = parsed.expenses.count { it.payer == SplitHomePayer.FUND }

    SmartCard {
        SectionHeader(
            title = "Vista previa",
            subtitle = "Revisá los números antes de confirmar",
        )
        Spacer(Modifier.height(8.dp))
        KeyValueRow("Gastos detectados", "${parsed.expenses.size}")
        KeyValueRow("Total registrado", Fmt.ars(parsed.totalCents), strong = true)
        KeyValueRow("Vos", "$primary gastos")
        KeyValueRow("Mamá", "$secondary gastos")
        KeyValueRow("Fondo común", "$fund gastos")
        KeyValueRow("Saldo inicial del fondo", Fmt.ars(parsed.fundInitialCents))
        KeyValueRow("Compras pagadas con fondo", Fmt.ars(parsed.fundPurchaseCents))
        if (parsed.installmentCount > 0) {
            KeyValueRow(
                "Compras en cuotas",
                "${parsed.installmentCount} · ${parsed.generatedInstallments} cuotas",
            )
        }
        KeyValueRow(
            "Categorías nuevas",
            preview.newCategories.ifEmpty { listOf("ninguna") }.joinToString(", "),
        )

        if (parsed.skippedRows > 0) {
            Spacer(Modifier.height(8.dp))
            InfoBanner("Se ignoraron ${parsed.skippedRows} filas con datos incompletos.", color = Amber)
        }
        if (parsed.warnings.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            InfoBanner(parsed.warnings.take(3).joinToString("\n"), color = Amber)
        }
        parsed.errors.forEach { error ->
            Spacer(Modifier.height(8.dp))
            InfoBanner(error, color = Rose)
        }

        if (preview.existingExpenseCount > 0) {
            Spacer(Modifier.height(10.dp))
            InfoBanner(
                "Ya hay ${preview.existingExpenseCount} gastos cargados. Si ya importaste este " +
                    "archivo, volver a hacerlo duplica los datos.",
                color = Amber,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = duplicateConfirmed, onCheckedChange = onDuplicateChange)
                Text(
                    "Confirmo que quiero importar igualmente",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth(),
            enabled = canImport && !isLoading,
        ) {
            Text("Importar ${parsed.expenses.size} gastos")
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "La importación es una sola operación: si algo falla, no se guarda nada.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReportCard(report: SplitHomeImportReport, onReset: () -> Unit) {
    SmartCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Emerald)
            Spacer(Modifier.width(8.dp))
            SectionHeader(
                title = "Importación completa",
                subtitle = "Los datos ya están en tu hogar",
            )
        }
        Spacer(Modifier.height(8.dp))
        KeyValueRow("Gastos importados", "${report.expensesImported}", strong = true)
        KeyValueRow("Total registrado", Fmt.ars(report.registeredTotalCents))
        if (report.generatedInstallments > 0) {
            KeyValueRow(
                "Cuotas generadas",
                "${report.generatedInstallments} (${Fmt.ars(report.installmentPurchaseTotalCents)})",
            )
        }
        KeyValueRow("Saldo inicial del fondo", Fmt.ars(report.fundInitialCents))
        KeyValueRow("Compras pagadas con fondo", Fmt.ars(report.fundPurchasesCents))
        KeyValueRow(
            "Categorías creadas",
            report.categoriesCreated.ifEmpty { listOf("ninguna") }.joinToString(", "),
        )
        report.warnings.take(3).forEach { warning ->
            Spacer(Modifier.height(6.dp))
            InfoBanner(warning, color = Amber)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Importar otro archivo")
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment ?: "reporte.xlsx"
