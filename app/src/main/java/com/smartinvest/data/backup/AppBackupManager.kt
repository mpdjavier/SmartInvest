package com.smartinvest.data.backup

import androidx.room.withTransaction
import com.smartinvest.data.local.AppDatabase
import com.smartinvest.data.local.dao.CategoryDao
import com.smartinvest.data.local.dao.CreditCardDao
import com.smartinvest.data.local.dao.ExpenseDao
import com.smartinvest.data.local.dao.FundMovementDao
import com.smartinvest.data.local.dao.FxRateDao
import com.smartinvest.data.local.dao.HoldingDao
import com.smartinvest.data.local.dao.InstallmentDao
import com.smartinvest.data.local.dao.InstrumentQuoteDao
import com.smartinvest.data.local.dao.MemberDao
import com.smartinvest.data.local.dao.SalaryPlanDao
import com.smartinvest.data.local.dao.SettlementDao
import com.smartinvest.data.local.dao.SnapshotDao
import com.smartinvest.data.local.dao.WalletDao
import com.smartinvest.data.local.entity.CategoryEntity
import com.smartinvest.data.local.entity.CreditCardEntity
import com.smartinvest.data.local.entity.ExpenseEntity
import com.smartinvest.data.local.entity.FundMovementEntity
import com.smartinvest.data.local.entity.FxRateEntity
import com.smartinvest.data.local.entity.HoldingEntity
import com.smartinvest.data.local.entity.InstallmentEntity
import com.smartinvest.data.local.entity.InstrumentQuoteEntity
import com.smartinvest.data.local.entity.MemberEntity
import com.smartinvest.data.local.entity.PortfolioSnapshotEntity
import com.smartinvest.data.local.entity.SalaryPlanEntity
import com.smartinvest.data.local.entity.SettlementEntity
import com.smartinvest.data.local.entity.WalletEntity
import com.smartinvest.di.IoDispatcher
import com.smartinvest.domain.engine.BrokerImportSummary
import com.smartinvest.domain.engine.BrokerMovementImporter
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.FundMovementType
import com.smartinvest.domain.model.MarketSource
import com.smartinvest.domain.model.QuoteUnit
import com.smartinvest.domain.model.WalletType
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupSettingsDto(
    val salaryFciPercent: Int = 45,
    val salaryLecapPercent: Int = 15,
    val salaryMepPercent: Int = 25,
    val salaryBondPercent: Int = 15,
    val salaryLiquidityCents: Long = 0L,
    val ao27dThresholdUsdCents: Long = 2_000_000L,
    val rebalanceTargetPesoPercent: Int = 60,
    val redTargetPesoPercent: Int = 50,
    val rebalanceTolerancePp: Double = 5.0,
    val maturityAlertDays: Int = 30,
    val rateDropThresholdPp: Double = 0.5,
    val fallbackMepRate: Double = 1_000.0,
    val monthlyDevaluationPercent: Double = 2.0,
    val pesoReferenceTnaPercent: Double = 0.0,
    val countryRiskPoints: Int = 0,
    val countryRisk30dChange: Int = 0,
    val previousFxGapPercent: Double = -1.0,
    val electoralCalendarEnabled: Boolean = true,
    val liquidityGuardrailEnabled: Boolean = true,
    val liquidityGuardrailMaxMonths: Int = 6,
    val previousCarryBandName: String = "",
    val carryExcludedPatterns: String = "Mercado Pago, viaje",
    val notificationsEnabled: Boolean = true,
    val hideAmounts: Boolean = false,
)

@Serializable
data class BackupMemberDto(
    val id: Long,
    val name: String,
    val isCurrentUser: Boolean = false,
    val colorHex: String = "#22C55E",
    val isActive: Boolean = true,
)

@Serializable
data class BackupCategoryDto(
    val id: Long,
    val name: String,
    val emoji: String = "🏠",
    val colorHex: String = "#38BDF8",
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
)

@Serializable
data class BackupCardDto(
    val id: Long,
    val name: String,
    val issuer: String = "",
    val brand: String = "VISA",
    val closingDay: Int,
    val dueDay: Int,
    val colorHex: String = "#F59E0B",
    val isActive: Boolean = true,
)

@Serializable
data class BackupExpenseDto(
    val id: Long,
    val description: String,
    val totalAmountCents: Long,
    val dateEpochDay: Long,
    val month: String,
    val categoryId: Long,
    val paidByMemberId: Long,
    val paymentMethod: String,
    val creditCardId: Long? = null,
    val installments: Int = 1,
    val firstInstallmentMonth: String? = null,
    val notes: String = "",
    val walletHoldingId: Long? = null,
    val walletQuantityDelta: Double = 0.0,
    val walletValueDeltaCents: Long = 0,
    val createdAt: Long,
)

@Serializable
data class BackupInstallmentDto(
    val id: Long,
    val expenseId: Long,
    val creditCardId: Long? = null,
    val number: Int,
    val count: Int,
    val dueMonth: String,
    val amountCents: Long,
    val isPaid: Boolean = false,
    val paidAt: Long? = null,
)

@Serializable
data class BackupFundMovementDto(
    val id: Long,
    val type: String,
    val amountCents: Long,
    val dateEpochDay: Long,
    val month: String,
    val memberId: Long? = null,
    val description: String,
    val expenseId: Long? = null,
    val createdAt: Long,
)

@Serializable
data class BackupSettlementDto(
    val id: Long,
    val month: String,
    val fromMemberId: Long = 0,
    val fromName: String = "",
    val toMemberId: Long = 0,
    val toName: String = "",
    val amountCents: Long = 0,
    val dateEpochDay: Long,
    val note: String = "",
    val createdAt: Long,
)

@Serializable
data class BackupWalletDto(
    val id: Long,
    val name: String,
    val institution: String,
    val type: String,
    val currency: String = "ARS",
    val balanceCents: Long = 0,
    val tnaPercent: Double = 0.0,
    val remuneratedCapCents: Long = 0,
    val isActive: Boolean = true,
    val rateSource: String = "manual",
    val rateUpdatedAt: Long? = null,
    val liquidityPriority: Int = 2,
    val notes: String = "",
    val linkedHoldingId: Long? = null,
)

@Serializable
data class BackupHoldingDto(
    val id: Long,
    val symbol: String,
    val name: String,
    val assetType: String,
    val currency: String = "ARS",
    val currentValueCents: Long,
    val costCents: Long = 0,
    val tnaPercent: Double? = null,
    val maturityEpochDay: Long? = null,
    val notes: String = "",
    val marketKey: String = "",
    val source: String = "MANUAL",
    val quantity: Double = 0.0,
    val quoteUnit: String = "PER_UNIT",
    val isWallet: Boolean = false,
    val settlementDays: Int = 1,
)

@Serializable
data class BackupQuoteDto(
    val id: Long = 0,
    val marketKey: String,
    val symbol: String,
    val source: String,
    val price: Double,
    val currency: String,
    val quoteUnit: String,
    val dateEpochDay: Long,
    val fetchedAt: Long,
)

@Serializable
data class BackupFxRateDto(
    val house: String,
    val name: String,
    val buy: Double,
    val sell: Double,
    val updatedAt: Long,
)

@Serializable
data class BackupSalaryPlanDto(
    val id: Long = 0,
    val month: String,
    val netSalaryCents: Long,
    val cushionCents: Long,
    val fciCents: Long,
    val lecapCents: Long,
    val mepCents: Long,
    val bondCents: Long = 0,
    val liquidityCents: Long = 0,
    val createdAt: Long,
)

@Serializable
data class BackupSnapshotDto(
    val id: Long = 0,
    val dateEpochDay: Long,
    val totalArsCents: Long,
    val totalUsdCents: Long,
    val arsAssetsCents: Long,
    val usdAssetsArsCents: Long,
    val dailyInterestCents: Long,
    val createdAt: Long,
)

@Serializable
data class AppBackupPayload(
    val version: Int = 1,
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val settings: BackupSettingsDto = BackupSettingsDto(),
    val members: List<BackupMemberDto> = emptyList(),
    val categories: List<BackupCategoryDto> = emptyList(),
    val creditCards: List<BackupCardDto> = emptyList(),
    val expenses: List<BackupExpenseDto> = emptyList(),
    val installments: List<BackupInstallmentDto> = emptyList(),
    val fundMovements: List<BackupFundMovementDto> = emptyList(),
    val settlements: List<BackupSettlementDto> = emptyList(),
    val wallets: List<BackupWalletDto> = emptyList(),
    val holdings: List<BackupHoldingDto> = emptyList(),
    val instrumentQuotes: List<BackupQuoteDto> = emptyList(),
    val fxRates: List<BackupFxRateDto> = emptyList(),
    val salaryPlans: List<BackupSalaryPlanDto> = emptyList(),
    val snapshots: List<BackupSnapshotDto> = emptyList(),
)

@Singleton
class AppBackupManager @Inject constructor(
    private val db: AppDatabase,
    private val memberDao: MemberDao,
    private val categoryDao: CategoryDao,
    private val creditCardDao: CreditCardDao,
    private val expenseDao: ExpenseDao,
    private val installmentDao: InstallmentDao,
    private val fundMovementDao: FundMovementDao,
    private val settlementDao: SettlementDao,
    private val walletDao: WalletDao,
    private val holdingDao: HoldingDao,
    private val instrumentQuoteDao: InstrumentQuoteDao,
    private val snapshotDao: SnapshotDao,
    private val fxRateDao: FxRateDao,
    private val salaryPlanDao: SalaryPlanDao,
    private val settingsRepository: SettingsRepository,
    private val investmentRepository: InvestmentRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun serialize(payload: AppBackupPayload): String = json.encodeToString(payload)
        fun deserialize(raw: String): AppBackupPayload = json.decodeFromString(raw.trimStart('\uFEFF').trim())
    }

    suspend fun exportBackupJson(): String = withContext(io) {
        val s = settingsRepository.currentSettings()
        val payload = AppBackupPayload(
            version = 1,
            exportedAtMillis = System.currentTimeMillis(),
            settings = BackupSettingsDto(
                salaryFciPercent = s.salaryFciPercent,
                salaryLecapPercent = s.salaryLecapPercent,
                salaryMepPercent = s.salaryMepPercent,
                salaryBondPercent = s.salaryBondPercent,
                salaryLiquidityCents = s.salaryLiquidityCents,
                ao27dThresholdUsdCents = s.ao27dThresholdUsdCents,
                rebalanceTargetPesoPercent = s.rebalanceTargetPesoPercent,
                redTargetPesoPercent = s.redTargetPesoPercent,
                rebalanceTolerancePp = s.rebalanceTolerancePp,
                maturityAlertDays = s.maturityAlertDays,
                rateDropThresholdPp = s.rateDropThresholdPp,
                fallbackMepRate = s.fallbackMepRate,
                monthlyDevaluationPercent = s.monthlyDevaluationPercent,
                pesoReferenceTnaPercent = s.pesoReferenceTnaPercent,
                countryRiskPoints = s.countryRiskPoints,
                countryRisk30dChange = s.countryRisk30dChange,
                previousFxGapPercent = s.previousFxGapPercent,
                electoralCalendarEnabled = s.electoralCalendarEnabled,
                liquidityGuardrailEnabled = s.liquidityGuardrailEnabled,
                liquidityGuardrailMaxMonths = s.liquidityGuardrailMaxMonths,
                previousCarryBandName = s.previousCarryBandName,
                carryExcludedPatterns = s.carryExcludedPatterns,
                notificationsEnabled = s.notificationsEnabled,
                hideAmounts = s.hideAmounts,
            ),
            members = memberDao.getAll().map {
                BackupMemberDto(it.id, it.name, it.isCurrentUser, it.colorHex, it.isActive)
            },
            categories = categoryDao.getAll().map {
                BackupCategoryDto(it.id, it.name, it.emoji, it.colorHex, it.isArchived, it.sortOrder)
            },
            creditCards = creditCardDao.getAll().map {
                BackupCardDto(it.id, it.name, it.issuer, it.brand, it.closingDay, it.dueDay, it.colorHex, it.isActive)
            },
            expenses = expenseDao.getAll().map {
                BackupExpenseDto(
                    id = it.id,
                    description = it.description,
                    totalAmountCents = it.totalAmountCents,
                    dateEpochDay = it.dateEpochDay,
                    month = it.month,
                    categoryId = it.categoryId,
                    paidByMemberId = it.paidByMemberId,
                    paymentMethod = it.paymentMethod.name,
                    creditCardId = it.creditCardId,
                    installments = it.installments,
                    firstInstallmentMonth = it.firstInstallmentMonth,
                    notes = it.notes,
                    walletHoldingId = it.walletHoldingId,
                    walletQuantityDelta = it.walletQuantityDelta,
                    walletValueDeltaCents = it.walletValueDeltaCents,
                    createdAt = it.createdAt,
                )
            },
            installments = installmentDao.getAll().map {
                BackupInstallmentDto(
                    id = it.id,
                    expenseId = it.expenseId,
                    creditCardId = it.creditCardId,
                    number = it.number,
                    count = it.count,
                    dueMonth = it.dueMonth,
                    amountCents = it.amountCents,
                    isPaid = it.isPaid,
                    paidAt = it.paidAt,
                )
            },
            fundMovements = fundMovementDao.getAll().map {
                BackupFundMovementDto(
                    id = it.id,
                    type = it.type.name,
                    amountCents = it.amountCents,
                    dateEpochDay = it.dateEpochDay,
                    month = it.month,
                    memberId = it.memberId,
                    description = it.description,
                    expenseId = it.expenseId,
                    createdAt = it.createdAt,
                )
            },
            settlements = settlementDao.getAll().map {
                BackupSettlementDto(
                    id = it.id,
                    month = it.month,
                    fromMemberId = it.fromMemberId,
                    fromName = it.fromName,
                    toMemberId = it.toMemberId,
                    toName = it.toName,
                    amountCents = it.amountCents,
                    dateEpochDay = it.dateEpochDay,
                    note = it.note,
                    createdAt = it.createdAt,
                )
            },
            wallets = walletDao.getAll().map {
                BackupWalletDto(
                    id = it.id,
                    name = it.name,
                    institution = it.institution,
                    type = it.type.name,
                    currency = it.currency.name,
                    balanceCents = it.balanceCents,
                    tnaPercent = it.tnaPercent,
                    remuneratedCapCents = it.remuneratedCapCents,
                    isActive = it.isActive,
                    rateSource = it.rateSource,
                    rateUpdatedAt = it.rateUpdatedAt,
                    liquidityPriority = it.liquidityPriority,
                    notes = it.notes,
                    linkedHoldingId = it.linkedHoldingId,
                )
            },
            holdings = holdingDao.getAll().map {
                BackupHoldingDto(
                    id = it.id,
                    symbol = it.symbol,
                    name = it.name,
                    assetType = it.assetType.name,
                    currency = it.currency.name,
                    currentValueCents = it.currentValueCents,
                    costCents = it.costCents,
                    tnaPercent = it.tnaPercent,
                    maturityEpochDay = it.maturityEpochDay,
                    notes = it.notes,
                    marketKey = it.marketKey,
                    source = it.source.name,
                    quantity = it.quantity,
                    quoteUnit = it.quoteUnit.name,
                    isWallet = it.isWallet,
                    settlementDays = it.settlementDays,
                )
            },
            instrumentQuotes = instrumentQuoteDao.getAll().map {
                BackupQuoteDto(
                    id = it.id,
                    marketKey = it.marketKey,
                    symbol = it.symbol,
                    source = it.source.name,
                    price = it.price,
                    currency = it.currency.name,
                    quoteUnit = it.quoteUnit.name,
                    dateEpochDay = it.dateEpochDay,
                    fetchedAt = it.fetchedAt,
                )
            },
            fxRates = fxRateDao.getAll().map {
                BackupFxRateDto(it.house, it.name, it.buy, it.sell, it.updatedAt)
            },
            salaryPlans = salaryPlanDao.getAll().map {
                BackupSalaryPlanDto(
                    id = it.id,
                    month = it.month,
                    netSalaryCents = it.netSalaryCents,
                    cushionCents = it.cushionCents,
                    fciCents = it.fciCents,
                    lecapCents = it.lecapCents,
                    mepCents = it.mepCents,
                    bondCents = it.bondCents,
                    liquidityCents = it.liquidityCents,
                    createdAt = it.createdAt,
                )
            },
            snapshots = snapshotDao.getAll().map {
                BackupSnapshotDto(
                    id = it.id,
                    dateEpochDay = it.dateEpochDay,
                    totalArsCents = it.totalArsCents,
                    totalUsdCents = it.totalUsdCents,
                    arsAssetsCents = it.arsAssetsCents,
                    usdAssetsArsCents = it.usdAssetsArsCents,
                    dailyInterestCents = it.dailyInterestCents,
                    createdAt = it.createdAt,
                )
            },
        )
        serialize(payload)
    }

    suspend fun importBackupJson(rawJson: String): String = withContext(io) {
        val payload = deserialize(rawJson)
        val s = payload.settings

        settingsRepository.update(
            AppSettings(
                salaryFciPercent = s.salaryFciPercent,
                salaryLecapPercent = s.salaryLecapPercent,
                salaryMepPercent = s.salaryMepPercent,
                salaryBondPercent = s.salaryBondPercent,
                salaryLiquidityCents = s.salaryLiquidityCents,
                ao27dThresholdUsdCents = s.ao27dThresholdUsdCents,
                rebalanceTargetPesoPercent = s.rebalanceTargetPesoPercent,
                redTargetPesoPercent = s.redTargetPesoPercent,
                rebalanceTolerancePp = s.rebalanceTolerancePp,
                maturityAlertDays = s.maturityAlertDays,
                rateDropThresholdPp = s.rateDropThresholdPp,
                fallbackMepRate = s.fallbackMepRate,
                monthlyDevaluationPercent = s.monthlyDevaluationPercent,
                pesoReferenceTnaPercent = s.pesoReferenceTnaPercent,
                countryRiskPoints = s.countryRiskPoints,
                countryRisk30dChange = s.countryRisk30dChange,
                previousFxGapPercent = s.previousFxGapPercent,
                electoralCalendarEnabled = s.electoralCalendarEnabled,
                liquidityGuardrailEnabled = s.liquidityGuardrailEnabled,
                liquidityGuardrailMaxMonths = s.liquidityGuardrailMaxMonths,
                previousCarryBandName = s.previousCarryBandName,
                carryExcludedPatterns = s.carryExcludedPatterns,
                notificationsEnabled = s.notificationsEnabled,
                hideAmounts = s.hideAmounts,
            ),
        )

        db.withTransaction {
            if (payload.members.isNotEmpty()) {
                memberDao.upsertAll(
                    payload.members.map {
                        MemberEntity(it.id, it.name, it.isCurrentUser, it.colorHex, it.isActive)
                    },
                )
            }
            if (payload.categories.isNotEmpty()) {
                categoryDao.upsertAll(
                    payload.categories.map {
                        CategoryEntity(it.id, it.name, it.emoji, it.colorHex, it.isArchived, it.sortOrder)
                    },
                )
            }
            if (payload.creditCards.isNotEmpty()) {
                creditCardDao.upsertAll(
                    payload.creditCards.map {
                        CreditCardEntity(it.id, it.name, it.issuer, it.brand, it.closingDay, it.dueDay, it.colorHex, it.isActive)
                    },
                )
            }
            if (payload.wallets.isNotEmpty()) {
                walletDao.upsertAll(
                    payload.wallets.map {
                        WalletEntity(
                            id = it.id,
                            name = it.name,
                            institution = it.institution,
                            type = runCatching { WalletType.valueOf(it.type) }.getOrDefault(WalletType.WALLET),
                            currency = runCatching { CurrencyCode.valueOf(it.currency) }.getOrDefault(CurrencyCode.ARS),
                            balanceCents = it.balanceCents,
                            tnaPercent = it.tnaPercent,
                            remuneratedCapCents = it.remuneratedCapCents,
                            isActive = it.isActive,
                            rateSource = it.rateSource,
                            rateUpdatedAt = it.rateUpdatedAt,
                            liquidityPriority = it.liquidityPriority,
                            notes = it.notes,
                            linkedHoldingId = it.linkedHoldingId,
                        )
                    },
                )
            }
            if (payload.holdings.isNotEmpty()) {
                holdingDao.upsertAll(
                    payload.holdings.map {
                        HoldingEntity(
                            id = it.id,
                            symbol = it.symbol,
                            name = it.name,
                            assetType = runCatching { AssetType.valueOf(it.assetType) }.getOrDefault(AssetType.OTHER),
                            currency = runCatching { CurrencyCode.valueOf(it.currency) }.getOrDefault(CurrencyCode.ARS),
                            currentValueCents = it.currentValueCents,
                            costCents = it.costCents,
                            tnaPercent = it.tnaPercent,
                            maturityEpochDay = it.maturityEpochDay,
                            notes = it.notes,
                            marketKey = it.marketKey,
                            source = runCatching { MarketSource.valueOf(it.source) }.getOrDefault(MarketSource.MANUAL),
                            quantity = it.quantity,
                            quoteUnit = runCatching { QuoteUnit.valueOf(it.quoteUnit) }.getOrDefault(QuoteUnit.PER_UNIT),
                            isWallet = it.isWallet,
                            settlementDays = it.settlementDays,
                        )
                    },
                )
            }
            if (payload.expenses.isNotEmpty()) {
                expenseDao.upsertAll(
                    payload.expenses.map {
                        ExpenseEntity(
                            id = it.id,
                            description = it.description,
                            totalAmountCents = it.totalAmountCents,
                            dateEpochDay = it.dateEpochDay,
                            month = it.month,
                            categoryId = it.categoryId,
                            paidByMemberId = it.paidByMemberId,
                            paymentMethod = runCatching { ExpensePaymentMethod.valueOf(it.paymentMethod) }
                                .getOrDefault(ExpensePaymentMethod.WALLET_FCI),
                            creditCardId = it.creditCardId,
                            installments = it.installments,
                            firstInstallmentMonth = it.firstInstallmentMonth,
                            notes = it.notes,
                            walletHoldingId = it.walletHoldingId,
                            walletQuantityDelta = it.walletQuantityDelta,
                            walletValueDeltaCents = it.walletValueDeltaCents,
                            createdAt = it.createdAt,
                        )
                    },
                )
            }
            if (payload.installments.isNotEmpty()) {
                installmentDao.upsertAll(
                    payload.installments.map {
                        InstallmentEntity(
                            id = it.id,
                            expenseId = it.expenseId,
                            creditCardId = it.creditCardId,
                            number = it.number,
                            count = it.count,
                            dueMonth = it.dueMonth,
                            amountCents = it.amountCents,
                            isPaid = it.isPaid,
                            paidAt = it.paidAt,
                        )
                    },
                )
            }
            if (payload.fundMovements.isNotEmpty()) {
                fundMovementDao.upsertAll(
                    payload.fundMovements.map {
                        FundMovementEntity(
                            id = it.id,
                            type = runCatching { FundMovementType.valueOf(it.type) }
                                .getOrDefault(FundMovementType.CONTRIBUTION),
                            amountCents = it.amountCents,
                            dateEpochDay = it.dateEpochDay,
                            month = it.month,
                            memberId = it.memberId,
                            description = it.description,
                            expenseId = it.expenseId,
                            createdAt = it.createdAt,
                        )
                    },
                )
            }
            if (payload.settlements.isNotEmpty()) {
                settlementDao.upsertAll(
                    payload.settlements.map {
                        SettlementEntity(
                            id = it.id,
                            month = it.month,
                            fromMemberId = it.fromMemberId,
                            fromName = it.fromName,
                            toMemberId = it.toMemberId,
                            toName = it.toName,
                            amountCents = it.amountCents,
                            dateEpochDay = it.dateEpochDay,
                            note = it.note,
                            createdAt = it.createdAt,
                        )
                    },
                )
            }
            if (payload.instrumentQuotes.isNotEmpty()) {
                instrumentQuoteDao.upsertAll(
                    payload.instrumentQuotes.map {
                        InstrumentQuoteEntity(
                            id = it.id,
                            marketKey = it.marketKey,
                            symbol = it.symbol,
                            source = runCatching { MarketSource.valueOf(it.source) }.getOrDefault(MarketSource.MANUAL),
                            price = it.price,
                            currency = runCatching { CurrencyCode.valueOf(it.currency) }.getOrDefault(CurrencyCode.ARS),
                            quoteUnit = runCatching { QuoteUnit.valueOf(it.quoteUnit) }.getOrDefault(QuoteUnit.PER_UNIT),
                            dateEpochDay = it.dateEpochDay,
                            fetchedAt = it.fetchedAt,
                        )
                    },
                )
            }
            if (payload.fxRates.isNotEmpty()) {
                fxRateDao.upsertAll(
                    payload.fxRates.map {
                        FxRateEntity(it.house, it.name, it.buy, it.sell, it.updatedAt)
                    },
                )
            }
            if (payload.salaryPlans.isNotEmpty()) {
                salaryPlanDao.upsertAll(
                    payload.salaryPlans.map {
                        SalaryPlanEntity(
                            id = it.id,
                            month = it.month,
                            netSalaryCents = it.netSalaryCents,
                            cushionCents = it.cushionCents,
                            fciCents = it.fciCents,
                            lecapCents = it.lecapCents,
                            mepCents = it.mepCents,
                            bondCents = it.bondCents,
                            liquidityCents = it.liquidityCents,
                            createdAt = it.createdAt,
                        )
                    },
                )
            }
            if (payload.snapshots.isNotEmpty()) {
                snapshotDao.upsertAll(
                    payload.snapshots.map {
                        PortfolioSnapshotEntity(
                            id = it.id,
                            dateEpochDay = it.dateEpochDay,
                            totalArsCents = it.totalArsCents,
                            totalUsdCents = it.totalUsdCents,
                            arsAssetsCents = it.arsAssetsCents,
                            usdAssetsArsCents = it.usdAssetsArsCents,
                            dailyInterestCents = it.dailyInterestCents,
                            createdAt = it.createdAt,
                        )
                    },
                )
            }
        }

        runCatching { investmentRepository.refreshSnapshot() }
        "Respaldo restaurado: ${payload.holdings.size} tenencias, ${payload.expenses.size} gastos y configuración."
    }

    /**
     * Applies all exported movements from Eco Valores (`Adcap Balanceado III`) and IEB+
     * (`SPY`, `S30N6`, `T15E7`, `AO27`) directly to the portfolio, updating existing holdings
     * or creating missing ones, and syncing market quotes.
     */
    suspend fun syncExportedBrokerHoldings(): String = withContext(io) {
        val existing = holdingDao.getAll()
        var updatedCount = 0

        db.withTransaction {
            // 1. Adcap Balanceado III (Eco Valores)
            val adcapSummary = BrokerMovementImporter.summarize(BrokerMovementImporter.ecoValoresAdcapBal3Movements)
            val existingAdcap = existing.firstOrNull {
                it.marketKey.contains("1402:4070") || it.symbol.contains("ADCAP", ignoreCase = true) || it.symbol.contains("BAL", ignoreCase = true)
            }
            val adcapNotes = mergeNotes(existingAdcap?.notes.orEmpty(), adcapSummary.toNotesBlock(CurrencyCode.ARS))
            holdingDao.upsert(
                (existingAdcap ?: HoldingEntity(
                    symbol = "ADCAP-BAL3",
                    name = "Adcap Balanceado III - Clase A",
                    assetType = AssetType.FCI,
                    currency = CurrencyCode.ARS,
                    currentValueCents = 72_031_053_75L,
                    costCents = adcapSummary.netInvestedCents,
                    marketKey = "cafci:1402:4070",
                    source = MarketSource.CAFCI,
                    quoteUnit = QuoteUnit.PER_UNIT,
                    settlementDays = 1,
                )).copy(
                    costCents = adcapSummary.netInvestedCents,
                    notes = adcapNotes,
                    settlementDays = 1,
                ),
            )
            updatedCount++

            // 2. SPY (IEB+ · 672 CEDEARs)
            val spySummary = BrokerMovementImporter.summarize(BrokerMovementImporter.iebSpyMovements)
            val existingSpy = existing.firstOrNull {
                it.symbol.equals("SPY", ignoreCase = true) || it.marketKey.equals("rava:SPY", ignoreCase = true)
            }
            val spyNotes = mergeNotes(existingSpy?.notes.orEmpty(), spySummary.toNotesBlock(CurrencyCode.ARS))
            holdingDao.upsert(
                (existingSpy ?: HoldingEntity(
                    symbol = "SPY",
                    name = "SPDR S&P 500 CEDEAR",
                    assetType = AssetType.CEDEAR,
                    currency = CurrencyCode.ARS,
                    currentValueCents = 14_185_920_00L,
                    costCents = spySummary.netInvestedCents,
                    marketKey = "rava:SPY",
                    source = MarketSource.RAVA,
                    quantity = spySummary.netQuantity,
                    quoteUnit = QuoteUnit.PER_UNIT,
                )).copy(
                    costCents = spySummary.netInvestedCents,
                    quantity = spySummary.netQuantity,
                    quoteUnit = QuoteUnit.PER_UNIT,
                    marketKey = "rava:SPY",
                    source = MarketSource.RAVA,
                    notes = spyNotes,
                ),
            )
            updatedCount++

            // 3. S30N6 (IEB+ · 2.964.509 VN)
            val s30Summary = BrokerMovementImporter.summarize(BrokerMovementImporter.iebS30n6Movements)
            val s30Candidates = existing.filter {
                it.symbol.equals("S30N6", ignoreCase = true) || it.marketKey.equals("rava:S30N6", ignoreCase = true)
            }
            val existingS30Ieb = s30Candidates.firstOrNull {
                it.notes.contains("IEB", ignoreCase = true) || it.name.contains("IEB", ignoreCase = true)
            } ?: s30Candidates.firstOrNull {
                !it.notes.contains("Galicia", ignoreCase = true) && !it.name.contains("Galicia", ignoreCase = true)
            }
            val s30BaseNotes = existingS30Ieb?.notes.orEmpty().ifBlank { "IEB" }
            val s30Notes = mergeNotes(s30BaseNotes, s30Summary.toNotesBlock(CurrencyCode.ARS))
            holdingDao.upsert(
                (existingS30Ieb ?: HoldingEntity(
                    symbol = "S30N6",
                    name = "Letra del Tesoro S30N6",
                    assetType = AssetType.LECAP,
                    currency = CurrencyCode.ARS,
                    currentValueCents = 3_803_761_50L,
                    costCents = s30Summary.netInvestedCents,
                    maturityEpochDay = LocalDate.of(2026, 11, 30).toEpochDay(),
                    marketKey = "rava:S30N6",
                    source = MarketSource.RAVA,
                    quantity = s30Summary.netQuantity,
                    quoteUnit = QuoteUnit.PER_100_NOMINAL,
                )).copy(
                    costCents = s30Summary.netInvestedCents,
                    quantity = s30Summary.netQuantity,
                    quoteUnit = QuoteUnit.PER_100_NOMINAL,
                    marketKey = "rava:S30N6",
                    source = MarketSource.RAVA,
                    maturityEpochDay = existingS30Ieb?.maturityEpochDay ?: LocalDate.of(2026, 11, 30).toEpochDay(),
                    notes = s30Notes,
                ),
            )
            updatedCount++

            // 4. T15E7 (IEB+ · 504.061 VN)
            val t15Summary = BrokerMovementImporter.summarize(BrokerMovementImporter.iebT15e7Movements)
            val existingT15 = existing.firstOrNull {
                it.symbol.equals("T15E7", ignoreCase = true) || it.marketKey.equals("rava:T15E7", ignoreCase = true)
            }
            val t15BaseNotes = existingT15?.notes.orEmpty().ifBlank { "IEB" }
            val t15Notes = mergeNotes(t15BaseNotes, t15Summary.toNotesBlock(CurrencyCode.ARS))
            holdingDao.upsert(
                (existingT15 ?: HoldingEntity(
                    symbol = "T15E7",
                    name = "Letra del Tesoro T15E7",
                    assetType = AssetType.LECAP,
                    currency = CurrencyCode.ARS,
                    currentValueCents = t15Summary.netInvestedCents,
                    costCents = t15Summary.netInvestedCents,
                    maturityEpochDay = LocalDate.of(2027, 1, 15).toEpochDay(),
                    marketKey = "rava:T15E7",
                    source = MarketSource.RAVA,
                    quantity = t15Summary.netQuantity,
                    quoteUnit = QuoteUnit.PER_100_NOMINAL,
                )).copy(
                    costCents = t15Summary.netInvestedCents,
                    quantity = t15Summary.netQuantity,
                    quoteUnit = QuoteUnit.PER_100_NOMINAL,
                    marketKey = "rava:T15E7",
                    source = MarketSource.RAVA,
                    maturityEpochDay = existingT15?.maturityEpochDay ?: LocalDate.of(2027, 1, 15).toEpochDay(),
                    notes = t15Notes,
                ),
            )
            updatedCount++

            // 5. AO27 / AO27D (+481 VN bought in IEB+ on 11/11/2026)
            val existingAo27 = existing.firstOrNull {
                it.symbol.startsWith("AO27", ignoreCase = true) || it.marketKey.startsWith("rava:AO27", ignoreCase = true)
            }
            if (existingAo27 != null) {
                val ao27MovCurrency = existingAo27.currency
                val ao27Movs = if (ao27MovCurrency == CurrencyCode.USD) {
                    // 481 VN @ US$ 102.75 c/100 VN = US$ 494.23
                    listOf(
                        BrokerMovementImporter.iebAo27Movements.first().copy(
                            signedAmountCents = 494_23L,
                        ),
                    )
                } else {
                    BrokerMovementImporter.iebAo27Movements
                }
                val ao27Summary = BrokerImportSummary(
                    movements = ao27Movs,
                    totalSubscribedCents = ao27Movs.first().signedAmountCents,
                    totalRedeemedCents = 0L,
                    netInvestedCents = ao27Movs.first().signedAmountCents,
                    netQuantity = 481.0,
                )
                val ao27Notes = mergeNotes(existingAo27.notes, ao27Summary.toNotesBlock(ao27MovCurrency))
                holdingDao.upsert(existingAo27.copy(notes = ao27Notes))
                updatedCount++
            }
        }

        runCatching { investmentRepository.syncMarketQuotes() }
        runCatching { investmentRepository.refreshSnapshot() }
        "Sincronizados $updatedCount activos de Eco Valores e IEB+ (ADCAP-BAL3, SPY, S30N6, T15E7 y AO27)."
    }

    private fun mergeNotes(existingNotes: String, movementBlock: String): String {
        val nonMovLines = existingNotes.lineSequence()
            .filter { line -> BrokerMovementImporter.parseMovementNotes(line).isEmpty() }
            .joinToString("\n")
            .trim()
        return if (nonMovLines.isBlank()) movementBlock else "$nonMovLines\n$movementBlock"
    }
}
