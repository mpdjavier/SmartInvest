package com.smartinvest.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Selectable sections of the ComparaTasas scraping summary.
 * The user ticks which categories they want to display in the summary and in
 * the Inicio shortcut card.
 */
enum class ComparaTasasCategory(
    val label: String,
    val subtitle: String,
    val rawCategories: Set<String>,
) {
    CUENTAS_REMUNERADAS(
        label = "Cuentas remuneradas y billeteras",
        subtitle = "TNA, TEA, tope remunerado y condiciones (Ualá, Naranja X, Fiwind, Brubank, BICA, Reba…)",
        rawCategories = setOf("cuentas-remuneradas"),
    ),
    FONDOS_PESOS(
        label = "FCI Money Market y ahorro en pesos",
        subtitle = "Fondos de billeteras, brókers y bancos (Mercado Pago, Adcap, Personal Pay, Prex, Cocos, IOL…)",
        rawCategories = setOf("fondos"),
    ),
    PLAZOS_FIJOS(
        label = "Plazos fijos bancarios",
        subtitle = "TNA a 30 días tradicional y plazos fijos UVA por banco",
        rawCategories = setOf("plazos-fijos"),
    ),
    INVERSIONES_USD(
        label = "Inversiones y FCI en dólares (USD)",
        subtitle = "Fondos comunes y cuentas remuneradas en dólares",
        rawCategories = setOf("usd"),
    ),
    CRIPTO(
        label = "Criptopesos y criptomonedas",
        subtitle = "Rendimientos APY en criptopesos (wARS) y cripto por plataforma",
        rawCategories = setOf("criptopesos", "criptomonedas"),
    ),
    COMISIONES_BROKERS(
        label = "Comisiones y aranceles de brókers",
        subtitle = "Aranceles de acciones, bonos, letras, CEDEARs, cauciones y opciones",
        rawCategories = setOf("comisiones-brokers"),
    ),
    CREDITOS_PRESTAMOS(
        label = "Créditos hipotecarios UVA y préstamos",
        subtitle = "Tasas TNA de créditos UVA y préstamos personales bancarios",
        rawCategories = setOf("creditos-hipotecarios-uva", "prestamos-personales"),
    ),
    COBRO_REMESAS(
        label = "Comisiones de cobro y remesas",
        subtitle = "Costos de cobro QR/tarjetas y recepción de pagos del exterior",
        rawCategories = setOf("comisiones-cobro", "remesas"),
    );

    companion object {
        val DEFAULT_SELECTED: Set<String> = setOf(
            CUENTAS_REMUNERADAS.name,
            FONDOS_PESOS.name,
            PLAZOS_FIJOS.name,
            INVERSIONES_USD.name,
        )

        fun fromRawCategory(raw: String): ComparaTasasCategory? =
            entries.firstOrNull { raw.lowercase() in it.rawCategories }
    }
}

@Serializable
data class ComparaTasasItem(
    val entityName: String,
    val entityType: String = "",
    val categoryName: String,
    val rawCategory: String = "",
    val productName: String,
    /** Rate or fee expressed as percentage (e.g. 25.0 = 25%). */
    val ratePercent: Double? = null,
    /** Effective annual rate in percent when provided (remunerated accounts). */
    val teaPercent: Double? = null,
    /** Remunerated cap in whole ARS (0 or null = no cap). */
    val capPesos: Long? = null,
    /** Short condition text when available. */
    val conditions: String = "",
    /** Human metric label from ComparaTasas (e.g. "TNA 19,04%", "Comisión 0,5%"). */
    val metricLabel: String = "",
)

@Serializable
data class ComparaTasasCatalog(
    val updatedAt: String = "",
    val fetchedAtMillis: Long = 0L,
    val source: String = "comparatasas.ar",
    val items: List<ComparaTasasItem> = emptyList(),
)

@Serializable
data class ComparaTasasConfig(
    /** Names of [ComparaTasasCategory] ticked by the user in the form. */
    val selectedCategories: Set<String> = ComparaTasasCategory.DEFAULT_SELECTED,
    /** Whether the summary shortcut card is shown on Inicio. */
    val showOnDashboard: Boolean = true,
    /** Cached scraping snapshot so Inicio and the summary screen render immediately. */
    val catalog: ComparaTasasCatalog = ComparaTasasCatalog(),
) {
    val activeCategories: List<ComparaTasasCategory>
        get() = ComparaTasasCategory.entries.filter { it.name in selectedCategories }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }

        fun encode(config: ComparaTasasConfig): String = json.encodeToString(config)

        fun decode(raw: String): ComparaTasasConfig {
            val clean = raw.trim()
            if (clean.isEmpty()) return ComparaTasasConfig()
            return runCatching { json.decodeFromString<ComparaTasasConfig>(clean) }
                .getOrDefault(ComparaTasasConfig())
        }
    }
}
