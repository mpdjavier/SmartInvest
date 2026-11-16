package com.smartinvest.domain.repository

import com.smartinvest.domain.model.FundCatalogEntry
import com.smartinvest.domain.model.FundData
import com.smartinvest.domain.engine.FundStabilityEngine
import kotlinx.coroutines.flow.Flow

/**
 * Read-only access to the public FCI catalog plus the user watchlist persisted in
 * [com.smartinvest.domain.model.AppSettings].
 */
interface FundEvaluationRepository {

    fun observeWatchlist(): Flow<List<String>>

    /** Seeds the curated Eco Valores watchlist only when the user never configured one. */
    suspend fun ensureSeededWatchlist(): List<String>

    suspend fun addToWatchlist(name: String)

    suspend fun removeFromWatchlist(name: String)

    suspend fun resetWatchlist(): List<String>

    suspend fun catalog(force: Boolean = false): List<FundCatalogEntry>

    /**
     * Loads catalog metadata and daily VCP history for each name. A fund whose
     * history cannot be fetched is returned with an empty history so the caller
     * can report it without losing the rest of the ranking.
     */
    suspend fun loadFunds(names: List<String>, force: Boolean = false): List<FundData>

    suspend fun loadFund(name: String, force: Boolean = false): FundData?

    companion object {
        /**
         * Curated starting point: the Adcap Balanceado III reference plus one
         * representative class per manager offered by Eco Valores. Users can add
         * any other class from the full 2.300+ catalog with the search box.
         */
        val DEFAULT_WATCHLIST: List<String> = listOf(
            FundStabilityEngine.BENCHMARK_NAME,
            "Adcap Acciones - Clase A",
            "Adcap Ahorro Pesos Fondo de Dinero - Clase A",
            "Axis Estrategia 31 - Clase B",
            "Balanz Acciones - Clase A",
            "Balanz Capital Money Market - Clase A",
            "Balanz Capital Ahorro - Clase A",
            "CMA Proteccion - Clase B",
            "Consultatio Zero - Clase A",
            "Consultatio Renta Nacional - Clase B",
            "Delta Ahorro - Clase A",
            "Delta Gestion I - Clase B",
            "Galileo Ahorro - Clase B",
            "Galileo Multimercado II - Clase B",
            "Dracma Renta Ahorro - Clase B",
            "IAM Ahorro Pesos - Clase B",
            "Compass Renta Mixta - Clase B",
            "MEGAQM Pesos - Clase A",
            "Zofingen Pesos Ya - Clase B",
        )
    }
}
