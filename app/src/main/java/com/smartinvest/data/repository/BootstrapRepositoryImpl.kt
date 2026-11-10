package com.smartinvest.data.repository

import androidx.room.withTransaction
import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.local.AppDatabase
import com.smartinvest.data.local.dao.CategoryDao
import com.smartinvest.data.local.dao.MemberDao
import com.smartinvest.data.local.dao.RateHistoryDao
import com.smartinvest.data.local.dao.WalletDao
import com.smartinvest.data.local.entity.CategoryEntity
import com.smartinvest.data.local.entity.MemberEntity
import com.smartinvest.data.local.entity.RateHistoryEntity
import com.smartinvest.data.local.entity.WalletEntity
import com.smartinvest.di.IoDispatcher
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.WalletType
import com.smartinvest.domain.repository.BootstrapRepository
import com.smartinvest.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BootstrapRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val memberDao: MemberDao,
    private val categoryDao: CategoryDao,
    private val walletDao: WalletDao,
    private val rateHistoryDao: RateHistoryDao,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BootstrapRepository {

    override suspend fun seedIfNeeded() = withContext(io) {
        if (settings.isInitialized()) return@withContext

        db.withTransaction {
            if (memberDao.count() == 0) {
                memberDao.insert(
                    MemberEntity(
                        name = "Vos",
                        isCurrentUser = true,
                        colorHex = "#22C55E",
                        isActive = true,
                    ),
                )
                memberDao.insert(
                    MemberEntity(
                        name = "Mamá",
                        isCurrentUser = false,
                        colorHex = "#A78BFA",
                        isActive = true,
                    ),
                )
            }

            if (categoryDao.count() == 0) {
                defaultCategories().forEachIndexed { index, (name, emoji, color) ->
                    categoryDao.insert(
                        CategoryEntity(
                            name = name,
                            emoji = emoji,
                            colorHex = color,
                            sortOrder = index,
                        ),
                    )
                }
            }

            if (walletDao.count() == 0) {
                val today = DateUtils.today()
                seededWallets().forEach { wallet ->
                    val id = walletDao.upsert(wallet)
                    if (wallet.tnaPercent > 0) {
                        rateHistoryDao.upsert(
                            RateHistoryEntity(
                                walletId = id,
                                dateEpochDay = today.toEpochDay(),
                                tnaPercent = wallet.tnaPercent,
                                source = wallet.rateSource,
                            ),
                        )
                    }
                }
            }
        }

        settings.markInitialized()
    }

    private fun defaultCategories(): List<Triple<String, String, String>> = listOf(
        Triple("Supermercado", "🛒", "#22C55E"),
        Triple("Servicios", "💡", "#FBBF24"),
        Triple("Expensas", "🏢", "#38BDF8"),
        Triple("Auto", "🚗", "#F472B6"),
        Triple("Salud", "🏥", "#F87171"),
        Triple("Comida", "🍽️", "#FB923C"),
        Triple("Ocio", "🎬", "#A78BFA"),
        Triple("Otros", "📦", "#94A3B8"),
    )

    private fun seededWallets(): List<WalletEntity> = listOf(
        WalletEntity(
            name = "Mercado Pago",
            institution = "Mercado Pago",
            type = WalletType.WALLET,
            currency = CurrencyCode.ARS,
            balanceCents = 0,
            tnaPercent = 31.0,
            remuneratedCapCents = 0,
            isActive = true,
            rateSource = "referencia",
            liquidityPriority = 2,
            notes = "Revisá el tope remunerado vigente.",
        ),
        WalletEntity(
            name = "Ualá",
            institution = "Ualá",
            type = WalletType.WALLET,
            currency = CurrencyCode.ARS,
            balanceCents = 0,
            tnaPercent = 30.0,
            remuneratedCapCents = 0,
            isActive = true,
            rateSource = "referencia",
            liquidityPriority = 2,
            notes = "Revisá el tope remunerado vigente.",
        ),
        WalletEntity(
            name = "FCI eco",
            institution = "Adcap",
            type = WalletType.MONEY_MARKET_FUND,
            currency = CurrencyCode.ARS,
            balanceCents = 0,
            tnaPercent = 32.5,
            remuneratedCapCents = 0,
            isActive = true,
            rateSource = "referencia",
            liquidityPriority = 1,
            notes = "Rescate T+0. Primera fuente para emergencias.",
        ),
        WalletEntity(
            name = "Adcap",
            institution = "Adcap",
            type = WalletType.BROKER,
            currency = CurrencyCode.ARS,
            balanceCents = 0,
            tnaPercent = 0.0,
            remuneratedCapCents = 0,
            isActive = true,
            rateSource = "referencia",
            liquidityPriority = 4,
            notes = "Bróker para LECAP/BONCAP.",
        ),
        WalletEntity(
            name = "Banco Galicia",
            institution = "Galicia",
            type = WalletType.BANK,
            currency = CurrencyCode.ARS,
            balanceCents = 0,
            tnaPercent = 0.0,
            remuneratedCapCents = 0,
            isActive = true,
            rateSource = "referencia",
            liquidityPriority = 3,
            notes = "Cuenta sueldo.",
        ),
    )
}
