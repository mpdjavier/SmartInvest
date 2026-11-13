package com.smartinvest.domain.model

enum class AlertType(val label: String) {
    RATE_DROP("Baja de tasa"),
    MATURITY("Vencimiento"),
    AO27D_THRESHOLD("Umbral AO27D"),
    REBALANCE("Rebalanceo"),
    CARD_DUE("Vencimiento de tarjeta"),
    PAYMENT_DUE("Agenda de pagos"),
    FUND_LOW("Fondo común"),
    SYSTEM("Sistema"),
}

enum class AlertSeverity { INFO, WARNING, CRITICAL }

data class AppAlert(
    val id: Long,
    val type: AlertType,
    val severity: AlertSeverity,
    val title: String,
    val message: String,
    val dedupeKey: String,
    val createdAt: Long,
    val isRead: Boolean,
)

data class NewAlert(
    val type: AlertType,
    val severity: AlertSeverity,
    val title: String,
    val message: String,
    val dedupeKey: String,
)
