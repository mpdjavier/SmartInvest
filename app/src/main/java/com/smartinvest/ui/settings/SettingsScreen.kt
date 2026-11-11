package com.smartinvest.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.ui.components.ChipRow
import com.smartinvest.ui.components.InfoBanner
import com.smartinvest.ui.components.SectionHeader
import com.smartinvest.ui.components.SmartCard
import com.smartinvest.ui.components.TagPill
import com.smartinvest.ui.components.ThinDivider
import com.smartinvest.ui.theme.Amber
import com.smartinvest.ui.theme.Emerald
import com.smartinvest.ui.theme.Rose
import com.smartinvest.ui.theme.Sky
import com.smartinvest.ui.util.colorFromHex
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun SettingsScreen(
    onOpenImport: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("Ajustes", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Hogar, tarjetas, reglas de alertas y reparto del sueldo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

        // Members
        item {
            SmartCard {
                SectionHeader(
                    title = "Integrantes del hogar",
                    subtitle = "Quién paga y cómo se reparte",
                    actionLabel = "Agregar",
                    onAction = { viewModel.startMember() },
                )
                Spacer(Modifier.height(6.dp))
                state.members.forEach { member ->
                    MemberRow(
                        member = member,
                        onEdit = { viewModel.startMember(member.id) },
                        onToggle = { viewModel.toggleMemberActive(member) },
                    )
                }
            }
        }

        // Categories
        item {
            SmartCard {
                SectionHeader(
                    title = "Categorías",
                    subtitle = "Administrables y archivables",
                    actionLabel = "Nueva",
                    onAction = { viewModel.startCategory() },
                )
                Spacer(Modifier.height(6.dp))
                state.categories.forEach { category ->
                    CategoryRow(
                        category = category,
                        onEdit = { viewModel.startCategory(category.id) },
                        onArchive = { viewModel.toggleCategoryArchived(category) },
                    )
                }
            }
        }

        // Cards
        item {
            SmartCard {
                SectionHeader(
                    title = "Tarjetas de crédito",
                    subtitle = "Cierre y vencimiento manuales",
                    actionLabel = "Agregar",
                    onAction = { viewModel.startCard() },
                )
                Spacer(Modifier.height(6.dp))
                if (state.cards.isEmpty()) {
                    Text(
                        "Sin tarjetas. Cargá una para imputar compras en cuotas por el valor de la cuota.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.cards.forEach { card ->
                        CardRow(
                            card = card,
                            onEdit = { viewModel.startCard(card.id) },
                            onToggle = { viewModel.toggleCardActive(card) },
                        )
                    }
                }
            }
        }

        // Salary split
        item {
            val settings = state.settings
            SmartCard {
                SectionHeader(
                    title = "Reparto del sueldo",
                    subtitle = "Liquidez 24/7 + tramo pesos + tramo dólares",
                )
                Spacer(Modifier.height(8.dp))
                NumericSettingField(
                    label = "Liquidez 24/7 mensual (0 = usar gasto del hogar)",
                    value = if (settings.salaryLiquidityCents > 0) {
                        (settings.salaryLiquidityCents / 100).toString()
                    } else {
                        ""
                    },
                    suffix = "$",
                    onValueChange = { text ->
                        val pesos = text.filter { it.isDigit() }.toLongOrNull() ?: 0L
                        viewModel.updateSettings { it.copy(salaryLiquidityCents = pesos * 100) }
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Va al FCI marcado como billetera antes de invertir el resto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Tramo en pesos",
                    style = MaterialTheme.typography.labelMedium,
                    color = Emerald,
                )
                PercentRow(
                    label = "Carry principal (FCI T+1)",
                    value = settings.salaryFciPercent,
                    onChange = { value -> viewModel.updateSettings { it.copy(salaryFciPercent = value) } },
                )
                PercentRow(
                    label = "Tasa fija (LECAP / BONCAP)",
                    value = settings.salaryLecapPercent,
                    onChange = { value -> viewModel.updateSettings { it.copy(salaryLecapPercent = value) } },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Tramo en dólares",
                    style = MaterialTheme.typography.labelMedium,
                    color = Sky,
                )
                PercentRow(
                    label = "Cobertura MEP",
                    value = settings.salaryMepPercent,
                    onChange = { value -> viewModel.updateSettings { it.copy(salaryMepPercent = value) } },
                )
                PercentRow(
                    label = "Resguardo / piso (AO27D)",
                    value = settings.salaryBondPercent,
                    onChange = { value -> viewModel.updateSettings { it.copy(salaryBondPercent = value) } },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ejemplo 60/40: pesos 45/15 y dólares 25/15. Los pesos y dólares se reparten " +
                        "según el objetivo ajustado por riesgo del semáforo; estos porcentajes " +
                        "solo definen la mezcla dentro de cada tramo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Rebalance
        item {
            val settings = state.settings
            SmartCard {
                SectionHeader(
                    title = "Rebalanceo elástico",
                    subtitle = "Banda de pesos vs. dólares",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Objetivo de pesos",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = listOf(50, 60, 70),
                    selected = settings.rebalanceTargetPesoPercent,
                    label = { "$it%" },
                    onSelect = { value -> viewModel.updateSettings { it.copy(rebalanceTargetPesoPercent = value) } },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Tolerancia de la banda",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = listOf(3.0, 5.0, 8.0, 10.0),
                    selected = settings.rebalanceTolerancePp,
                    label = { "±${it.roundToLong()} pp" },
                    onSelect = { value -> viewModel.updateSettings { it.copy(rebalanceTolerancePp = value) } },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Objetivo en estado ROJO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = listOf(40, 50, 60),
                    selected = settings.redTargetPesoPercent,
                    label = { "$it% pesos" },
                    onSelect = { value -> viewModel.updateSettings { it.copy(redTargetPesoPercent = value) } },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "El ROJO deja de ser ambiguo: el flujo y los vencimientos apuntan a este " +
                        "objetivo defensivo, no solo a volver a la base 60/40.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Guardarraíl de liquidez", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "El sobrepeso en pesos solo en instrumentos ≤6 meses o T+0/24 h",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.liquidityGuardrailEnabled,
                        onCheckedChange = { value ->
                            viewModel.updateSettings { it.copy(liquidityGuardrailEnabled = value) }
                        },
                    )
                }
                if (settings.liquidityGuardrailEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Plazo máximo del sobrepeso",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipRow(
                        options = listOf(3, 6, 12),
                        selected = settings.liquidityGuardrailMaxMonths,
                        label = { "$it meses" },
                        onSelect = { value ->
                            viewModel.updateSettings { it.copy(liquidityGuardrailMaxMonths = value) }
                        },
                    )
                }
            }
        }

        // Macro environment
        item {
            val settings = state.settings
            SmartCard {
                SectionHeader(
                    title = "Entorno macro",
                    subtitle = "Insumos del asistente de carry y rebalanceo",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Devaluación oficial mensual esperada (crawling peg)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = listOf(1.0, 2.0, 3.0, 4.0, 5.0),
                    selected = settings.monthlyDevaluationPercent,
                    label = { "${it.roundToLong()}% mensual" },
                    onSelect = { value -> viewModel.updateSettings { it.copy(monthlyDevaluationPercent = value) } },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "TNA de referencia en pesos",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = listOf(0.0, 30.0, 40.0, 50.0, 60.0),
                    selected = settings.pesoReferenceTnaPercent,
                    label = { if (it <= 0.0) "Automática" else "${it.roundToLong()}% TNA" },
                    onSelect = { value -> viewModel.updateSettings { it.copy(pesoReferenceTnaPercent = value) } },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (settings.pesoReferenceTnaPercent > 0.0) {
                        "Se usa la TNA manual para comparar contra la devaluación."
                    } else {
                        "Automática: promedio ponderado por saldo de billeteras ARS y " +
                            "tenencias en pesos (LECAP, BONCAP, FCI, bonos)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Riesgo país",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                NumericSettingField(
                    label = "Nivel actual (puntos)",
                    value = if (settings.countryRiskPoints > 0) settings.countryRiskPoints.toString() else "",
                    suffix = "pts",
                    onValueChange = { text ->
                        viewModel.updateSettings { it.copy(countryRiskPoints = text.toIntOrNull() ?: 0) }
                    },
                )
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = listOf(700, 800, 873, 900),
                    selected = settings.countryRiskPoints,
                    label = { "$it pts" },
                    onSelect = { value -> viewModel.updateSettings { it.copy(countryRiskPoints = value) } },
                )
                Spacer(Modifier.height(8.dp))
                NumericSettingField(
                    label = "Variación en 30 días (puntos)",
                    value = if (settings.countryRisk30dChange != 0) {
                        settings.countryRisk30dChange.toString()
                    } else {
                        ""
                    },
                    suffix = "pts",
                    onValueChange = { text ->
                        viewModel.updateSettings { it.copy(countryRisk30dChange = text.toIntOrNull() ?: 0) }
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Nivel > 800 o suba > 150 pts en 30 días pone techo AMARILLO: no se puede " +
                        "estar en VERDE aunque la brecha esté planchada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Brecha MEP de hace 30 días",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                NumericSettingField(
                    label = "Brecha previa (dejá vacío si no hay dato)",
                    value = settings.previousFxGapPercent
                        .takeIf { it >= 0.0 }
                        ?.let { String.format(Locale.US, "%.1f", it) }
                        ?: "",
                    suffix = "%",
                    onValueChange = { text ->
                        viewModel.updateSettings {
                            it.copy(
                                previousFxGapPercent = text.replace(',', '.').toDoubleOrNull() ?: -1.0,
                            )
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Se usa para medir la velocidad, no solo el nivel: una brecha que se duplica " +
                        "en dos semanas es señal defensiva aunque siga siendo baja.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Alcance del semáforo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = settings.carryExcludedPatterns,
                    onValueChange = { value ->
                        viewModel.updateSettings { it.copy(carryExcludedPatterns = value) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Excluir del índice (separados por coma)") },
                    singleLine = true,
                    supportingText = {
                        Text("Por defecto quedan fuera Mercado Pago y la cubeta de viajes.")
                    },
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Calendario electoral", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Techo VERDE: 70% hasta feb/2027, 65% mar–jun, 60% jul–nov",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.electoralCalendarEnabled,
                        onCheckedChange = { value ->
                            viewModel.updateSettings { it.copy(electoralCalendarEnabled = value) }
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                InfoBanner(
                    "El índice mide solo el escenario: tasa real (40%), brecha nivel (25%) + " +
                        "velocidad (10%) y riesgo país (25%). El desvío de cartera no suma " +
                        "puntos: se usa para dimensionar el flujo.",
                    color = Sky,
                )
            }
        }

        // Alert rules
        item {
            val settings = state.settings
            SmartCard {
                SectionHeader(
                    title = "Reglas de alertas",
                    subtitle = "Umbrales que evalúa WorkManager",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Vencimiento de letras (días de anticipación)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = listOf(15, 30, 45, 60),
                    selected = settings.maturityAlertDays,
                    label = { "$it días" },
                    onSelect = { value -> viewModel.updateSettings { it.copy(maturityAlertDays = value) } },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Caída de TNA que dispara alerta",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = listOf(0.25, 0.5, 1.0, 2.0),
                    selected = settings.rateDropThresholdPp,
                    label = { "${it} pp" },
                    onSelect = { value -> viewModel.updateSettings { it.copy(rateDropThresholdPp = value) } },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Umbral crítico AO27D (USD 20.000 por bonificación)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = listOf(10_000L, 20_000L, 30_000L, 50_000L),
                    selected = (settings.ao27dThresholdUsdCents / 100).coerceIn(10_000, 50_000),
                    label = { "USD ${it / 1000}k" },
                    onSelect = { value ->
                        viewModel.updateSettings { it.copy(ao27dThresholdUsdCents = value * 100) }
                    },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "MEP de respaldo si DolarAPI falla",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipRow(
                    options = listOf(900.0, 1_000.0, 1_200.0, 1_500.0),
                    selected = settings.fallbackMepRate,
                    label = { "$${it.roundToLong()}" },
                    onSelect = { value -> viewModel.updateSettings { it.copy(fallbackMepRate = value) } },
                )
            }
        }

        item {
            SmartCard {
                SectionHeader(
                    title = "Importar datos",
                    subtitle = "Historial de otra app",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "¿Venís de SplitHome? Importá el reporte .xlsx para arrancar con los gastos, " +
                        "las categorías y el fondo común ya cargados.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onOpenImport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Rounded.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Importar desde SplitHome")
                }
            }
        }

        item {
            SmartCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ocultar cifras", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Muestra asteriscos en lugar de montos para usar la app en público",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.settings.hideAmounts,
                        onCheckedChange = { value ->
                            viewModel.updateSettings { it.copy(hideAmounts = value) }
                        },
                    )
                }
            }
        }

        item {
            SmartCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notificaciones push", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Alertas de tasas, vencimientos y rebalanceo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.settings.notificationsEnabled,
                        onCheckedChange = viewModel::setNotificationsEnabled,
                    )
                }
            }
        }

        item {
            ThinDivider()
        }

        item {
            Text(
                "SmartInvest · Datos locales con Room y DataStore. Las tasas se sincronizan desde " +
                    "Comparatasas y las cotizaciones desde DolarAPI. Sin asesoramiento financiero.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Dialogs
    state.memberDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMemberDraft,
            title = { Text(if (draft.id > 0) "Renombrar integrante" else "Nuevo integrante") },
            text = {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = viewModel::updateMemberDraft,
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::saveMemberDraft) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = viewModel::dismissMemberDraft) { Text("Cancelar") } },
        )
    }

    state.categoryDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCategoryDraft,
            title = { Text(if (draft.isEditing) "Editar categoría" else "Nueva categoría") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = draft.emoji,
                            onValueChange = { value ->
                                if (value.length <= 3) viewModel.updateCategoryDraft { it.copy(emoji = value) }
                            },
                            label = { Text("Emoji") },
                            singleLine = true,
                            modifier = Modifier.width(88.dp),
                        )
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = { value -> viewModel.updateCategoryDraft { it.copy(name = value) } },
                            label = { Text("Nombre") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "Color",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipRow(
                        options = listOf("#22C55E", "#38BDF8", "#FBBF24", "#F472B6", "#A78BFA", "#FB923C"),
                        selected = draft.colorHex,
                        label = { it },
                        onSelect = { value -> viewModel.updateCategoryDraft { it.copy(colorHex = value) } },
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::saveCategoryDraft) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = viewModel::dismissCategoryDraft) { Text("Cancelar") } },
        )
    }

    state.cardDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCardDraft,
            title = { Text(if (draft.isEditing) "Editar tarjeta" else "Nueva tarjeta") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { value -> viewModel.updateCardDraft { it.copy(name = value) } },
                        label = { Text("Nombre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.issuer,
                        onValueChange = { value -> viewModel.updateCardDraft { it.copy(issuer = value) } },
                        label = { Text("Emisor") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.brand,
                        onValueChange = { value -> viewModel.updateCardDraft { it.copy(brand = value) } },
                        label = { Text("Marca") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = draft.closingDay.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { day ->
                                    viewModel.updateCardDraft { it.copy(closingDay = day.coerceIn(1, 31)) }
                                }
                            },
                            label = { Text("Cierre") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = draft.dueDay.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { day ->
                                    viewModel.updateCardDraft { it.copy(dueDay = day.coerceIn(1, 31)) }
                                }
                            },
                            label = { Text("Vencimiento") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tarjeta activa", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = draft.isActive,
                            onCheckedChange = { value -> viewModel.updateCardDraft { it.copy(isActive = value) } },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::saveCardDraft) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = viewModel::dismissCardDraft) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun MemberRow(
    member: HouseholdMember,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Person,
            null,
            tint = colorFromHex(member.colorHex, Sky),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(member.name, style = MaterialTheme.typography.titleSmall)
            Text(
                if (member.isCurrentUser) "Vos" else "Integrante",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TagPill(if (member.isActive) "Activo" else "Inactivo", if (member.isActive) Emerald else Rose)
        IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Edit, "Editar", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
        }
        Switch(checked = member.isActive, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun CategoryRow(
    category: HouseholdCategory,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category.emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            category.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (category.isArchived) TagPill("Archivada", Rose)
        IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Edit, "Editar", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
        }
        IconButton(onClick = onArchive, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Rounded.Archive,
                if (category.isArchived) "Reactivar" else "Archivar",
                tint = if (category.isArchived) Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun CardRow(
    card: CreditCard,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.CreditCard,
            null,
            tint = colorFromHex(card.colorHex, Amber),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${card.issuer.ifBlank { card.brand }} · ${card.cycleLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Edit, "Editar", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
        }
        Switch(checked = card.isActive, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun PercentRow(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange((value - 5).coerceAtLeast(0)) }, modifier = Modifier.size(32.dp)) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "$value%",
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        IconButton(onClick = { onChange((value + 5).coerceAtMost(100)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Add, "Sumar", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun NumericSettingField(
    label: String,
    value: String,
    suffix: String,
    onValueChange: (String) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = draft,
        onValueChange = { input ->
            if (input.isEmpty() || input.all { it.isDigit() || it == '-' || it == '.' || it == ',' }) {
                draft = input
                onValueChange(input)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = MaterialTheme.shapes.medium,
    )
}
