package com.smartinvest.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

/**
 * ArgentinaDatos exposes free JSON series that we use as an alternative source
 * when the live quotes fail or when a historical reading is needed:
 *
 * - Country risk (daily series) → level and 30-day change.
 * - MEP, wholesale and blue dollar series → current gap, 14-day gap and blue gap.
 * - 30-day fixed-term rates (BCRA) → short peso TEM for the carry cushion.
 */
@Serializable
data class ArgentinaDolarPointDto(
    val casa: String = "",
    val compra: Double? = null,
    val venta: Double? = null,
    val fecha: String = "",
)

@Serializable
data class ArgentinaCountryRiskPointDto(
    val valor: Int = 0,
    val fecha: String = "",
)

@Serializable
data class ArgentinaFixedTermDto(
    val entidad: String = "",
    @SerialName("tnaClientes") val tnaClientes: Double? = null,
    @SerialName("tnaNoClientes") val tnaNoClientes: Double? = null,
)

interface ArgentinaDatosService {
    @GET("v1/finanzas/indices/riesgo-pais")
    suspend fun countryRiskHistory(): List<ArgentinaCountryRiskPointDto>

    @GET("v1/cotizaciones/dolares/bolsa")
    suspend fun mepHistory(): List<ArgentinaDolarPointDto>

    @GET("v1/cotizaciones/dolares/mayorista")
    suspend fun wholesaleHistory(): List<ArgentinaDolarPointDto>

    @GET("v1/cotizaciones/dolares/blue")
    suspend fun blueHistory(): List<ArgentinaDolarPointDto>

    @GET("v1/cotizaciones/dolares/oficial")
    suspend fun officialHistory(): List<ArgentinaDolarPointDto>

    @GET("v1/finanzas/tasas/plazoFijo")
    suspend fun fixedTermRates(): List<ArgentinaFixedTermDto>
}
