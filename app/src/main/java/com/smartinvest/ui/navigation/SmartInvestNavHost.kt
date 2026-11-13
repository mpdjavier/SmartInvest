package com.smartinvest.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartinvest.ui.alerts.AlertsScreen
import com.smartinvest.ui.analytics.AnalyticsScreen
import com.smartinvest.ui.dashboard.DashboardScreen
import com.smartinvest.ui.household.ExpenseEditorScreen
import com.smartinvest.ui.household.FundMovementScreen
import com.smartinvest.ui.household.HouseholdScreen
import com.smartinvest.ui.household.PaymentAgendaScreen
import com.smartinvest.ui.household.ReceiptImportScreen
import com.smartinvest.ui.importer.ImportScreen
import com.smartinvest.ui.investments.DailyInterestScreen
import com.smartinvest.ui.investments.BreakEvenScreen
import com.smartinvest.ui.investments.HoldingEditorScreen
import com.smartinvest.ui.investments.InstrumentDetailScreen
import com.smartinvest.ui.investments.InvestmentsScreen
import com.smartinvest.ui.planning.EmergencyScreen
import com.smartinvest.ui.planning.SalaryScreen
import com.smartinvest.ui.rebalancing.RebalancingScreen
import com.smartinvest.ui.settings.SettingsScreen
import java.time.YearMonth

object Routes {
    const val DASHBOARD = "dashboard"
    const val HOUSEHOLD = "household"
    const val INVESTMENTS = "investments"
    const val SETTINGS = "settings"

    const val EXPENSE_EDITOR = "expense_editor/{month}?expenseId={expenseId}"
    const val FUND_EDITOR = "fund_editor/{month}"
    const val HOLDING_EDITOR = "holding_editor/{holdingId}"
    const val ANALYTICS = "analytics"
    const val SALARY = "salary"
    const val EMERGENCY = "emergency"
    const val ALERTS = "alerts"
    const val IMPORT = "import"
    const val DAILY_INTEREST = "daily_interest"
    const val INSTRUMENT_DETAIL = "instrument_detail/{holdingId}"
    const val REBALANCING = "rebalancing"
    const val BREAK_EVEN = "break_even"
    const val RECEIPT_IMPORT = "receipt_import/{receiptPath}"
    const val PAYMENT_AGENDA = "payment_agenda"

    fun expenseEditor(month: YearMonth, expenseId: Long = 0L): String =
        "expense_editor/$month?expenseId=$expenseId"
    fun fundEditor(month: YearMonth): String = "fund_editor/$month"
    fun holdingEditor(holdingId: Long = 0L): String = "holding_editor/$holdingId"
    fun instrumentDetail(holdingId: Long = 0L): String = "instrument_detail/$holdingId"
    fun receiptImport(path: String): String = "receipt_import/${Uri.encode(path)}"
}

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "Inicio", Icons.Rounded.Home),
    Tab(Routes.HOUSEHOLD, "Hogar", Icons.Rounded.ShoppingBasket),
    Tab(Routes.INVESTMENTS, "Cartera", Icons.Rounded.AccountBalanceWallet),
    Tab(Routes.SETTINGS, "Ajustes", Icons.Rounded.Settings),
)

private val secondaryTitles = mapOf(
    Routes.EXPENSE_EDITOR to "Nuevo gasto",
    Routes.FUND_EDITOR to "Fondo común",
    Routes.HOLDING_EDITOR to "Activo",
    Routes.ANALYTICS to "Analítica",
    Routes.SALARY to "Repartir sueldo",
    Routes.EMERGENCY to "Rescate de emergencia",
    Routes.ALERTS to "Alertas",
    Routes.IMPORT to "Importar datos",
    Routes.DAILY_INTEREST to "Intereses día a día",
    Routes.INSTRUMENT_DETAIL to "Evolución de instrumentos",
    Routes.REBALANCING to "Asistente macro",
    Routes.BREAK_EVEN to "Dólar break-even",
    Routes.RECEIPT_IMPORT to "Importar comprobante",
    Routes.PAYMENT_AGENDA to "Agenda de pagos",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartInvestNavHost(
    sharedReceiptPath: String? = null,
    onSharedReceiptHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    // Keeps the bootstrap/sync triggers alive while the app is open.
    androidx.hilt.navigation.compose.hiltViewModel<com.smartinvest.ui.app.AppViewModel>()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val isTopLevel = tabs.any { it.route == route }

    LaunchedEffect(sharedReceiptPath) {
        val path = sharedReceiptPath ?: return@LaunchedEffect
        navController.navigate(Routes.receiptImport(path))
        onSharedReceiptHandled()
    }

    fun navigateTopLevel(target: String) {
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { navigateTopLevel(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        topBar = {
            if (!isTopLevel) {
                val title = secondaryTitles.entries
                    .firstOrNull { (pattern, _) -> route?.startsWith(pattern.substringBefore("{")) == true }
                    ?.value
                    ?: "SmartInvest"
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onNewExpense = { navController.navigate(Routes.expenseEditor(YearMonth.now())) },
                    onFundMovement = { navController.navigate(Routes.fundEditor(YearMonth.now())) },
                    onOpenHousehold = { navigateTopLevel(Routes.HOUSEHOLD) },
                    onOpenInvestments = { navigateTopLevel(Routes.INVESTMENTS) },
                    onOpenAlerts = { navController.navigate(Routes.ALERTS) },
                    onOpenSalary = { navController.navigate(Routes.SALARY) },
                    onOpenEmergency = { navController.navigate(Routes.EMERGENCY) },
                    onOpenAnalytics = { navController.navigate(Routes.ANALYTICS) },
                )
            }

            composable(Routes.HOUSEHOLD) {
                HouseholdScreen(
                    onNewExpense = { month -> navController.navigate(Routes.expenseEditor(month)) },
                    onEditExpense = { month, expenseId ->
                        navController.navigate(Routes.expenseEditor(month, expenseId))
                    },
                    onFundMovement = { month -> navController.navigate(Routes.fundEditor(month)) },
                    onOpenSettings = { navigateTopLevel(Routes.SETTINGS) },
                    onOpenPaymentAgenda = { navController.navigate(Routes.PAYMENT_AGENDA) },
                )
            }

            composable(Routes.PAYMENT_AGENDA) {
                PaymentAgendaScreen()
            }

            composable(Routes.INVESTMENTS) {
                InvestmentsScreen(
                    onAddHolding = { navController.navigate(Routes.holdingEditor()) },
                    onEditHolding = { id -> navController.navigate(Routes.holdingEditor(id)) },
                    onOpenAnalytics = { navController.navigate(Routes.ANALYTICS) },
                    onOpenSalary = { navController.navigate(Routes.SALARY) },
                    onOpenEmergency = { navController.navigate(Routes.EMERGENCY) },
                    onOpenDailyInterest = { navController.navigate(Routes.DAILY_INTEREST) },
                    onOpenInstrumentDetail = { holdingId -> navController.navigate(Routes.instrumentDetail(holdingId)) },
                    onOpenRebalancing = { navController.navigate(Routes.REBALANCING) },
                    onOpenBreakEven = { navController.navigate(Routes.BREAK_EVEN) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenImport = { navController.navigate(Routes.IMPORT) })
            }

            composable(
                route = Routes.EXPENSE_EDITOR,
                arguments = listOf(
                    navArgument("month") { type = NavType.StringType },
                    navArgument("expenseId") {
                        type = NavType.StringType
                        defaultValue = "0"
                    },
                ),
            ) {
                ExpenseEditorScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }

            composable(
                route = Routes.FUND_EDITOR,
                arguments = listOf(navArgument("month") { type = NavType.StringType }),
            ) {
                FundMovementScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Routes.HOLDING_EDITOR,
                arguments = listOf(navArgument("holdingId") { type = NavType.StringType }),
            ) {
                HoldingEditorScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.ANALYTICS) {
                AnalyticsScreen(
                    onOpenDailyInterest = { navController.navigate(Routes.DAILY_INTEREST) },
                    onOpenInstrumentDetail = { navController.navigate(Routes.instrumentDetail(0L)) },
                )
            }

            composable(Routes.DAILY_INTEREST) {
                DailyInterestScreen()
            }

            composable(Routes.BREAK_EVEN) {
                BreakEvenScreen()
            }

            composable(Routes.REBALANCING) {
                RebalancingScreen(onOpenSettings = { navController.navigate(Routes.SETTINGS) })
            }

            composable(
                route = Routes.INSTRUMENT_DETAIL,
                arguments = listOf(navArgument("holdingId") { type = NavType.StringType }),
            ) {
                InstrumentDetailScreen(
                    onEditHolding = { id -> navController.navigate(Routes.holdingEditor(id)) },
                    onAddHolding = { navController.navigate(Routes.holdingEditor()) },
                )
            }

            composable(Routes.SALARY) {
                SalaryScreen(onOpenSettings = { navController.navigate(Routes.SETTINGS) })
            }

            composable(Routes.EMERGENCY) {
                EmergencyScreen()
            }

            composable(Routes.ALERTS) {
                AlertsScreen()
            }

            composable(Routes.IMPORT) {
                ImportScreen(
                    onReceiptReady = { path -> navController.navigate(Routes.receiptImport(path)) },
                )
            }

            composable(
                route = Routes.RECEIPT_IMPORT,
                arguments = listOf(navArgument("receiptPath") { type = NavType.StringType }),
            ) {
                ReceiptImportScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
