package com.smartinvest.data.network

import com.smartinvest.domain.model.FxRate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

@Serializable
data class DollarRateDto(
    val moneda: String = "",
    val casa: String = "",
    val nombre: String = "",
    val compra: Double? = null,
    val venta: Double? = null,
    @SerialName("fechaActualizacion") val updatedAt: String? = null,
)

interface DolarApiService {
    @GET("v1/dolares")
    suspend fun getDollarRates(): List<DollarRateDto>
}

fun DollarRateDto.toDomain(): FxRate {
    val stamp = updatedAt?.let { raw ->
        runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
    } ?: System.currentTimeMillis()
    return FxRate(
        house = casa,
        name = nombre.ifBlank { casa },
        buy = compra ?: 0.0,
        sell = venta ?: compra ?: 0.0,
        updatedAt = stamp,
    )
}
