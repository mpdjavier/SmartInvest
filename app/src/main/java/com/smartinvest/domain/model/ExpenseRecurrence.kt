package com.smartinvest.domain.model

/**
 * How often a recurring household expense repeats.
 *
 * The interval is expressed in months so the payment calendar can project the
 * next occurrences from the first registered payment. A monthly expense is the
 * default and keeps the previous behaviour.
 */
enum class ExpenseRecurrence(
    val label: String,
    val shortLabel: String,
    val intervalMonths: Int,
) {
    MONTHLY("Mensual", "Cada mes", 1),
    BIMONTHLY("Bimestral", "Cada 2 meses", 2),
    QUARTERLY("Trimestral", "Cada 3 meses", 3),
    SEMIANNUAL("Semestral", "Cada 6 meses", 6),
    ANNUAL("Anual", "Cada 12 meses", 12),
    ;

    companion object {
        fun fromMonths(months: Int): ExpenseRecurrence =
            entries.firstOrNull { it.intervalMonths == months } ?: MONTHLY
    }
}
