package com.smartinvest.domain.repository

import com.smartinvest.domain.importer.SplitHomeParseResult

data class SplitHomeImportPreview(
    val parsed: SplitHomeParseResult,
    val existingExpenseCount: Int,
    val newCategories: List<String>,
)

data class SplitHomeImportReport(
    val expensesImported: Int,
    val generatedInstallments: Int,
    val fundInitialCents: Long,
    val fundPurchasesCents: Long,
    val registeredTotalCents: Long,
    val installmentPurchaseTotalCents: Long,
    val categoriesCreated: List<String>,
    val warnings: List<String>,
)

/** Imports data from a previous app so the household history is not lost. */
interface ImportRepository {

    suspend fun previewSplitHome(bytes: ByteArray): SplitHomeImportPreview

    suspend fun importSplitHome(bytes: ByteArray): SplitHomeImportReport
}
