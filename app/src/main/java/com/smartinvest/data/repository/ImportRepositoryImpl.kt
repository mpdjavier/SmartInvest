package com.smartinvest.data.repository

import androidx.room.withTransaction
import com.smartinvest.data.importer.XlsxReader
import com.smartinvest.data.local.AppDatabase
import com.smartinvest.data.local.dao.CategoryDao
import com.smartinvest.data.local.dao.CreditCardDao
import com.smartinvest.data.local.dao.ExpenseDao
import com.smartinvest.data.local.dao.FundMovementDao
import com.smartinvest.data.local.dao.InstallmentDao
import com.smartinvest.data.local.dao.MemberDao
import com.smartinvest.data.local.entity.CategoryEntity
import com.smartinvest.data.local.entity.CreditCardEntity
import com.smartinvest.data.local.entity.ExpenseEntity
import com.smartinvest.data.local.entity.FundMovementEntity
import com.smartinvest.data.local.entity.InstallmentEntity
import com.smartinvest.data.local.entity.MemberEntity
import com.smartinvest.data.widget.WidgetRefresher
import com.smartinvest.di.IoDispatcher
import com.smartinvest.domain.engine.CardCycleResolver
import com.smartinvest.domain.engine.InstallmentEngine
import com.smartinvest.domain.importer.SplitHomeParseResult
import com.smartinvest.domain.importer.SplitHomeParser
import com.smartinvest.domain.importer.SplitHomePayer
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.FundMovementType
import com.smartinvest.domain.repository.ImportRepository
import com.smartinvest.domain.repository.SplitHomeImportPreview
import com.smartinvest.domain.repository.SplitHomeImportReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val memberDao: MemberDao,
    private val categoryDao: CategoryDao,
    private val cardDao: CreditCardDao,
    private val expenseDao: ExpenseDao,
    private val installmentDao: InstallmentDao,
    private val fundDao: FundMovementDao,
    private val widgetRefresher: WidgetRefresher,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ImportRepository {

    override suspend fun previewSplitHome(bytes: ByteArray): SplitHomeImportPreview = withContext(io) {
        val parsed = parse(bytes)
        val existing = categoryDao.getAll()
        val newCategories = parsed.categories.filter { name ->
            existing.none { SplitHomeParser.normalize(it.name) == SplitHomeParser.normalize(name) }
        }
        SplitHomeImportPreview(
            parsed = parsed,
            existingExpenseCount = expenseDao.count(),
            newCategories = newCategories,
        )
    }

    override suspend fun importSplitHome(bytes: ByteArray): SplitHomeImportReport = withContext(io) {
        val parsed = parse(bytes)
        require(parsed.errors.isEmpty()) { parsed.errors.first() }
        require(parsed.expenses.isNotEmpty()) { "El archivo no tiene gastos para importar" }

        val report = db.withTransaction { insert(parsed) }
        widgetRefresher.refresh()
        report
    }

    private fun parse(bytes: ByteArray): SplitHomeParseResult {
        val sheets = XlsxReader.read(bytes).associate { it.name to it.rows }
        return SplitHomeParser.parse(sheets)
    }

    private suspend fun insert(parsed: SplitHomeParseResult): SplitHomeImportReport {
        val members = memberDao.getAll()
        val primary = members.firstOrNull { it.isCurrentUser }
            ?: createMember(name = "Vos", isCurrentUser = true)
        val secondary = members.firstOrNull {
            !it.isCurrentUser && SplitHomeParser.normalize(it.name).contains("mama")
        } ?: members.firstOrNull { !it.isCurrentUser }
            ?: createMember(name = "Mamá", isCurrentUser = false)

        val categoryIndex = categoryDao.getAll()
            .associateBy { SplitHomeParser.normalize(it.name) }
            .toMutableMap()
        var nextSortOrder = (categoryIndex.values.maxOfOrNull { it.sortOrder } ?: 0) + 1
        val createdCategories = mutableListOf<String>()

        suspend fun categoryId(name: String): Long {
            val key = SplitHomeParser.normalize(name)
            categoryIndex[key]?.let { return it.id }
            val (emoji, color) = categoryPreset(name)
            val id = categoryDao.insert(
                CategoryEntity(
                    name = name,
                    emoji = emoji,
                    colorHex = color,
                    sortOrder = nextSortOrder++,
                ),
            )
            val entity = requireNotNull(categoryDao.getById(id))
            categoryIndex[key] = entity
            createdCategories += name
            return id
        }

        val card = if (parsed.expenses.any { it.isInstallmentPurchase }) {
            cardDao.getAll().firstOrNull { it.name == IMPORTED_CARD_NAME }
                ?: run {
                    val id = cardDao.upsert(
                        CreditCardEntity(
                            name = IMPORTED_CARD_NAME,
                            issuer = "SplitHome",
                            brand = "",
                            closingDay = 31,
                            dueDay = 10,
                            colorHex = "#38BDF8",
                            isActive = true,
                        ),
                    )
                    requireNotNull(cardDao.getById(id))
                }
        } else {
            null
        }

        val now = System.currentTimeMillis()
        val earliest = parsed.expenses.minOf { it.date }

        // The fund's initial balance is a shared contribution (no member), so it
        // does not alter the month's equal split but keeps the fund balance right.
        if (parsed.fundInitialCents > 0) {
            fundDao.insert(
                FundMovementEntity(
                    type = FundMovementType.CONTRIBUTION,
                    amountCents = parsed.fundInitialCents,
                    dateEpochDay = earliest.toEpochDay(),
                    month = YearMonth.from(earliest).toString(),
                    memberId = null,
                    description = "Saldo inicial SplitHome",
                    createdAt = now,
                ),
            )
        }

        var installmentsCreated = 0
        parsed.expenses.forEach { row ->
            val totalCents = row.totalCents
            require(totalCents > 0) { "Monto inválido en \"${row.description}\"" }

            val method = when {
                row.payer == SplitHomePayer.FUND -> ExpensePaymentMethod.COMMON_FUND
                row.isInstallmentPurchase -> ExpensePaymentMethod.CREDIT
                else -> ExpensePaymentMethod.DEBIT
            }
            val paidBy = if (row.payer == SplitHomePayer.SECONDARY) secondary.id else primary.id
            val firstMonth = if (method == ExpensePaymentMethod.CREDIT) {
                val closingDay = requireNotNull(card).closingDay
                CardCycleResolver.firstInstallmentMonth(row.date, closingDay)
            } else {
                YearMonth.from(row.date)
            }

            val expenseId = expenseDao.insert(
                ExpenseEntity(
                    description = row.description,
                    totalAmountCents = totalCents,
                    dateEpochDay = row.date.toEpochDay(),
                    month = firstMonth.toString(),
                    categoryId = categoryId(row.categoryName),
                    paidByMemberId = paidBy,
                    paymentMethod = method,
                    creditCardId = if (method == ExpensePaymentMethod.CREDIT) card?.id else null,
                    installments = if (row.isInstallmentPurchase) row.installments else 1,
                    firstInstallmentMonth = if (method == ExpensePaymentMethod.CREDIT) {
                        firstMonth.toString()
                    } else {
                        null
                    },
                    notes = "Importado de SplitHome",
                    createdAt = now,
                ),
            )

            if (row.isInstallmentPurchase) {
                val schedule = InstallmentEngine.schedule(
                    totalCents = totalCents,
                    installments = row.installments,
                    firstInstallmentMonth = firstMonth,
                )
                installmentDao.insertAll(
                    schedule.map { slice ->
                        InstallmentEntity(
                            expenseId = expenseId,
                            creditCardId = card?.id,
                            number = slice.number,
                            count = slice.count,
                            dueMonth = slice.dueMonth.toString(),
                            amountCents = slice.amountCents,
                        )
                    },
                )
                installmentsCreated += schedule.size
            }

            if (method == ExpensePaymentMethod.COMMON_FUND) {
                fundDao.insert(
                    FundMovementEntity(
                        type = FundMovementType.EXPENSE,
                        amountCents = row.amountCents,
                        dateEpochDay = row.date.toEpochDay(),
                        month = YearMonth.from(row.date).toString(),
                        memberId = null,
                        description = "Compra: ${row.description}",
                        expenseId = expenseId,
                        createdAt = now,
                    ),
                )
            }
        }

        return SplitHomeImportReport(
            expensesImported = parsed.expenses.size,
            generatedInstallments = installmentsCreated,
            fundInitialCents = parsed.fundInitialCents,
            fundPurchasesCents = parsed.fundPurchaseCents,
            registeredTotalCents = parsed.totalCents,
            installmentPurchaseTotalCents = parsed.expenses
                .filter { it.isInstallmentPurchase }
                .sumOf { it.totalCents },
            categoriesCreated = createdCategories.toList(),
            warnings = parsed.warnings,
        )
    }

    private suspend fun createMember(name: String, isCurrentUser: Boolean): MemberEntity {
        val id = memberDao.insert(
            MemberEntity(
                name = name,
                isCurrentUser = isCurrentUser,
                colorHex = if (isCurrentUser) "#22C55E" else "#A78BFA",
                isActive = true,
            ),
        )
        return requireNotNull(memberDao.getById(id))
    }

    private fun categoryPreset(name: String): Pair<String, String> =
        when (SplitHomeParser.normalize(name)) {
            "servicios" -> "🧾" to "#38BDF8"
            "mantenimiento" -> "🔧" to "#F59E0B"
            "comida" -> "🛒" to "#22C55E"
            "limpieza" -> "🧹" to "#A78BFA"
            "sueldos" -> "💼" to "#F87171"
            "equipamiento" -> "🛋️" to "#FBBF24"
            "otros" -> "📦" to "#94A3B8"
            else -> "🏠" to "#38BDF8"
        }

    private companion object {
        const val IMPORTED_CARD_NAME = "SplitHome (importada)"
    }
}
