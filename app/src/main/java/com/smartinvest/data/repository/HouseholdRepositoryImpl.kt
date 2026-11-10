package com.smartinvest.data.repository

import androidx.room.withTransaction
import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.local.AppDatabase
import com.smartinvest.data.local.dao.CategoryDao
import com.smartinvest.data.local.dao.CreditCardDao
import com.smartinvest.data.local.dao.ExpenseDao
import com.smartinvest.data.local.dao.FundMovementDao
import com.smartinvest.data.local.dao.InstallmentDao
import com.smartinvest.data.local.dao.MemberDao
import com.smartinvest.data.local.dao.SettlementDao
import com.smartinvest.data.local.entity.CategoryEntity
import com.smartinvest.data.local.entity.ExpenseEntity
import com.smartinvest.data.local.entity.FundMovementEntity
import com.smartinvest.data.local.entity.InstallmentEntity
import com.smartinvest.data.local.entity.MemberEntity
import com.smartinvest.data.local.entity.SettlementEntity
import com.smartinvest.data.mapper.toDomain
import com.smartinvest.data.mapper.toEntity
import com.smartinvest.data.mapper.toMonthlyItem
import com.smartinvest.data.widget.WidgetRefresher
import com.smartinvest.di.IoDispatcher
import com.smartinvest.domain.engine.CardCycleResolver
import com.smartinvest.domain.engine.InstallmentEngine
import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.FundMovement
import com.smartinvest.domain.model.FundMovementType
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.model.MonthlyExpenseItem
import com.smartinvest.domain.model.RegisteredSettlement
import com.smartinvest.domain.repository.HouseholdRepository
import com.smartinvest.domain.repository.NewExpense
import com.smartinvest.domain.repository.NewFundMovement
import com.smartinvest.domain.repository.NewSettlement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HouseholdRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val memberDao: MemberDao,
    private val categoryDao: CategoryDao,
    private val cardDao: CreditCardDao,
    private val expenseDao: ExpenseDao,
    private val installmentDao: InstallmentDao,
    private val fundDao: FundMovementDao,
    private val settlementDao: SettlementDao,
    private val widgetRefresher: WidgetRefresher,
    @IoDispatcher private val io: CoroutineDispatcher,
) : HouseholdRepository {

    override fun observeMembers(): Flow<List<HouseholdMember>> =
        memberDao.observeAll()
            .mapEach { it.toDomain() }
            .flowOn(io)

    override fun observeCategories(includeArchived: Boolean): Flow<List<HouseholdCategory>> {
        val source = if (includeArchived) categoryDao.observeAll() else categoryDao.observeActive()
        return source.mapEach { it.toDomain() }.flowOn(io)
    }

    override fun observeCards(includeInactive: Boolean): Flow<List<CreditCard>> {
        val source = if (includeInactive) cardDao.observeAll() else cardDao.observeActive()
        return source.mapEach { it.toDomain() }.flowOn(io)
    }

    override fun observeMonthlyExpenses(month: YearMonth): Flow<List<MonthlyExpenseItem>> {
        val cash = expenseDao.observeCashForMonth(month.toString())
        val installments = expenseDao.observeInstallmentRows(month.toString())
        return combine(cash, installments) { cashRows, installmentRows ->
            val items = cashRows.map { it.toMonthlyItem(month) } +
                installmentRows.map { it.toMonthlyItem() }
            items.sortedWith(compareByDescending<MonthlyExpenseItem> { it.date }.thenByDescending { it.expenseId })
        }.flowOn(io)
    }

    override fun observeFundMovements(month: YearMonth): Flow<List<FundMovement>> =
        fundDao.observeForMonth(month.toString())
            .mapEach { it.toDomain() }
            .flowOn(io)

    override fun observeSettlements(month: YearMonth): Flow<List<RegisteredSettlement>> =
        settlementDao.observeForMonth(month.toString())
            .mapEach { it.toDomain() }
            .flowOn(io)

    override fun observeFundBalance(): Flow<Long> = fundDao.observeBalance().flowOn(io)

    override suspend fun currentFundBalance(): Long = withContext(io) { fundDao.currentBalance() }

    override suspend fun addMember(name: String): Long = withContext(io) {
        val clean = name.trim()
        require(clean.isNotBlank()) { "El nombre no puede estar vacío" }
        val palette = listOf("#F59E0B", "#A78BFA", "#38BDF8", "#F472B6", "#4ADE80")
        val index = memberDao.count() % palette.size
        memberDao.insert(
            MemberEntity(
                name = clean,
                isCurrentUser = false,
                colorHex = palette[index],
                isActive = true,
            ),
        )
    }

    override suspend fun renameMember(id: Long, name: String) = withContext(io) {
        val clean = name.trim()
        require(clean.isNotBlank()) { "El nombre no puede estar vacío" }
        val member = memberDao.getById(id) ?: error("Integrante inexistente")
        memberDao.update(member.copy(name = clean))
    }

    override suspend fun setMemberActive(id: Long, active: Boolean) = withContext(io) {
        val member = memberDao.getById(id) ?: error("Integrante inexistente")
        memberDao.update(member.copy(isActive = active))
    }

    override suspend fun addCategory(name: String, emoji: String, colorHex: String): Long = withContext(io) {
        val clean = name.trim()
        require(clean.isNotBlank()) { "La categoría necesita un nombre" }
        categoryDao.insert(
            CategoryEntity(
                name = clean.take(40),
                emoji = emoji.ifBlank { "🏠" },
                colorHex = colorHex,
                sortOrder = categoryDao.count(),
            ),
        )
    }

    override suspend fun updateCategory(id: Long, name: String, emoji: String, colorHex: String) =
        withContext(io) {
            val clean = name.trim()
            require(clean.isNotBlank()) { "La categoría necesita un nombre" }
            val category = categoryDao.getById(id) ?: error("Categoría inexistente")
            categoryDao.update(category.copy(name = clean.take(40), emoji = emoji, colorHex = colorHex))
        }

    override suspend fun setCategoryArchived(id: Long, archived: Boolean) = withContext(io) {
        categoryDao.setArchived(id, archived)
    }

    override suspend fun upsertCard(card: CreditCard): Long = withContext(io) {
        require(card.name.isNotBlank()) { "La tarjeta necesita un nombre" }
        require(card.closingDay in 1..31) { "El día de cierre debe estar entre 1 y 31" }
        require(card.dueDay in 1..31) { "El día de vencimiento debe estar entre 1 y 31" }
        cardDao.upsert(card.toEntity())
    }

    override suspend fun setCardActive(id: Long, active: Boolean) = withContext(io) {
        cardDao.setActive(id, active)
    }

    override suspend fun addExpense(input: NewExpense): Long {
        val expenseId = withContext(io) {
            require(input.totalCents > 0) { "El monto debe ser mayor a cero" }
            require(input.description.isNotBlank()) { "Agregá una descripción" }
            require(input.installments in InstallmentEngine.MIN_INSTALLMENTS..InstallmentEngine.MAX_INSTALLMENTS) {
                "Las cuotas deben estar entre 1 y ${InstallmentEngine.MAX_INSTALLMENTS}"
            }
            val member = memberDao.getById(input.paidByMemberId) ?: error("Integrante inexistente")
            val category = categoryDao.getById(input.categoryId) ?: error("Categoría inexistente")
            require(!category.isArchived) { "La categoría está archivada" }

            db.withTransaction {
                when (input.paymentMethod) {
                    ExpensePaymentMethod.CREDIT -> {
                        val cardId = input.creditCardId ?: error("Elegí una tarjeta de crédito")
                        val card = cardDao.getById(cardId) ?: error("Tarjeta inexistente")
                        require(card.isActive) { "La tarjeta está desactivada" }
                        val firstMonth = CardCycleResolver.firstInstallmentMonth(input.date, card.closingDay)
                        val schedule = InstallmentEngine.schedule(
                            totalCents = input.totalCents,
                            installments = input.installments,
                            firstInstallmentMonth = firstMonth,
                        )
                        val id = expenseDao.insert(
                            ExpenseEntity(
                                description = input.description.trim(),
                                totalAmountCents = input.totalCents,
                                dateEpochDay = input.date.toEpochDay(),
                                month = firstMonth.toString(),
                                categoryId = category.id,
                                paidByMemberId = member.id,
                                paymentMethod = input.paymentMethod,
                                creditCardId = card.id,
                                installments = input.installments,
                                firstInstallmentMonth = firstMonth.toString(),
                                notes = input.notes.trim(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        installmentDao.insertAll(
                            schedule.map { slice ->
                                InstallmentEntity(
                                    expenseId = id,
                                    creditCardId = card.id,
                                    number = slice.number,
                                    count = slice.count,
                                    dueMonth = slice.dueMonth.toString(),
                                    amountCents = slice.amountCents,
                                )
                            },
                        )
                        id
                    }

                    ExpensePaymentMethod.COMMON_FUND -> {
                        val balance = fundDao.currentBalance()
                        require(balance >= input.totalCents) {
                            "El fondo común no alcanza. Disponible: ${com.smartinvest.core.money.MoneyFormatter.ars(balance)}"
                        }
                        val month = YearMonth.from(input.date)
                        val id = expenseDao.insert(
                            ExpenseEntity(
                                description = input.description.trim(),
                                totalAmountCents = input.totalCents,
                                dateEpochDay = input.date.toEpochDay(),
                                month = month.toString(),
                                categoryId = category.id,
                                paidByMemberId = member.id,
                                paymentMethod = input.paymentMethod,
                                installments = 1,
                                notes = input.notes.trim(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        fundDao.insert(
                            FundMovementEntity(
                                type = FundMovementType.EXPENSE,
                                amountCents = input.totalCents,
                                dateEpochDay = input.date.toEpochDay(),
                                month = month.toString(),
                                memberId = null,
                                description = "Compra: ${input.description.trim()}",
                                expenseId = id,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        id
                    }

                    else -> {
                        val month = YearMonth.from(input.date)
                        expenseDao.insert(
                            ExpenseEntity(
                                description = input.description.trim(),
                                totalAmountCents = input.totalCents,
                                dateEpochDay = input.date.toEpochDay(),
                                month = month.toString(),
                                categoryId = category.id,
                                paidByMemberId = member.id,
                                paymentMethod = input.paymentMethod,
                                installments = 1,
                                notes = input.notes.trim(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }
        }
        widgetRefresher.refresh()
        return expenseId
    }

    override suspend fun deleteExpense(expenseId: Long) {
        withContext(io) {
            db.withTransaction {
                expenseDao.getById(expenseId) ?: error("Gasto inexistente")
                fundDao.deleteForExpense(expenseId)
                installmentDao.deleteForExpense(expenseId)
                expenseDao.deleteById(expenseId)
            }
        }
        widgetRefresher.refresh()
    }

    override suspend fun setInstallmentPaid(installmentId: Long, paid: Boolean) {
        withContext(io) {
            installmentDao.setPaid(
                id = installmentId,
                paid = paid,
                paidAt = if (paid) System.currentTimeMillis() else null,
            )
        }
        widgetRefresher.refresh()
    }

    override suspend fun addFundMovement(input: NewFundMovement): Long {
        val id = withContext(io) {
            require(input.amountCents > 0) { "El monto debe ser mayor a cero" }
            require(input.type != FundMovementType.EXPENSE) {
                "Las compras con fondo se registran desde un gasto"
            }
            val member = input.memberId?.let { memberDao.getById(it) ?: error("Integrante inexistente") }
                ?: error("Elegí quién realiza el movimiento")
            if (input.type == FundMovementType.WITHDRAWAL) {
                val balance = fundDao.currentBalance()
                require(balance >= input.amountCents) {
                    "El fondo común no alcanza. Disponible: ${com.smartinvest.core.money.MoneyFormatter.ars(balance)}"
                }
            }
            val month = YearMonth.from(input.date)
            fundDao.insert(
                FundMovementEntity(
                    type = input.type,
                    amountCents = input.amountCents,
                    dateEpochDay = input.date.toEpochDay(),
                    month = month.toString(),
                    memberId = member.id,
                    description = input.description.trim().ifBlank {
                        if (input.type == FundMovementType.CONTRIBUTION) "Aporte al fondo" else "Extracción del fondo"
                    },
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        widgetRefresher.refresh()
        return id
    }

    override suspend fun deleteFundMovement(id: Long) {
        withContext(io) {
            val movement = fundDao.getById(id) ?: error("Movimiento inexistente")
            require(movement.expenseId == null) {
                "Este movimiento pertenece a una compra. Eliminá el gasto asociado."
            }
            fundDao.deleteById(id)
        }
        widgetRefresher.refresh()
    }

    override suspend fun registerSettlement(input: NewSettlement): Long {
        val id = withContext(io) {
            require(input.amountCents >= 0) { "El monto no puede ser negativo" }
            settlementDao.insert(
                SettlementEntity(
                    month = input.month.toString(),
                    fromMemberId = input.fromMemberId,
                    fromName = input.fromName.trim(),
                    toMemberId = input.toMemberId,
                    toName = input.toName.trim(),
                    amountCents = input.amountCents,
                    dateEpochDay = input.date.toEpochDay(),
                    note = input.note.trim(),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        widgetRefresher.refresh()
        return id
    }

    override suspend fun deleteSettlement(id: Long) {
        withContext(io) {
            settlementDao.deleteById(id)
        }
        widgetRefresher.refresh()
    }

    private fun <T, R> Flow<List<T>>.mapEach(transform: (T) -> R): Flow<List<R>> =
        map { list -> list.map(transform) }
}
