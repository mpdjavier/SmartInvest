package com.smartinvest.data.network

import com.smartinvest.data.local.entity.WalletEntity
import com.smartinvest.domain.model.ComparaTasasCategory
import com.smartinvest.domain.model.ComparaTasasConfig
import com.smartinvest.domain.model.WalletType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparatasasScraperTest {

    @Test
    fun `parseEntitiesJson extracts remunerated accounts funds and fixed terms as percent`() {
        val sampleJson = """
            {
              "updatedAt": "2026-11-19T12:00:00.000Z",
              "entities": [
                {
                  "slug": "mercado-pago",
                  "name": "Mercado Pago",
                  "type": "billetera",
                  "products": [
                    {
                      "category": "fondos",
                      "product": "Mercado Fondo - Clase A",
                      "value": 0.1904,
                      "metric": "TNA 19,04%"
                    },
                    {
                      "category": "comisiones-cobro",
                      "product": "QR Dinero en cuenta",
                      "value": 0.008,
                      "metric": "Arancel 0,8%"
                    }
                  ]
                },
                {
                  "slug": "uala",
                  "name": "Ualá",
                  "type": "billetera",
                  "products": [
                    {
                      "category": "cuentas-remuneradas",
                      "product": "Ualá",
                      "value": 0.19,
                      "metric": "TNA 19%"
                    },
                    {
                      "category": "cuentas-remuneradas",
                      "product": "Ualá Plus 2",
                      "value": 0.24,
                      "metric": "TNA 24%"
                    },
                    {
                      "category": "fondos",
                      "product": "Ualintec Ahorro Pesos - Clase A",
                      "value": 0.1968,
                      "metric": "TNA 19,68%"
                    }
                  ]
                },
                {
                  "slug": "adcap",
                  "name": "Adcap",
                  "type": "broker",
                  "products": [
                    {
                      "category": "fondos",
                      "product": "Adcap Ahorro Pesos Fondo de Dinero - Clase A",
                      "value": 0.1969,
                      "metric": "TNA 19,69%"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val rates = ComparatasasScraper.parseEntitiesJson(sampleJson)

        val mp = WalletEntity(name = "Mercado Pago", institution = "Mercado Pago", type = WalletType.WALLET)
        val uala = WalletEntity(name = "Ualá", institution = "Ualá", type = WalletType.WALLET)
        val fciEco = WalletEntity(name = "FCI eco", institution = "Adcap", type = WalletType.MONEY_MARKET_FUND)

        assertEquals(19.04, RateMatcher.find(mp, rates) ?: 0.0, 0.01)
        assertEquals(19.0, RateMatcher.find(uala, rates) ?: 0.0, 0.01)
        assertEquals(19.69, RateMatcher.find(fciEco, rates) ?: 0.0, 0.01)
    }

    @Test
    fun `parseArgentinaDatosAccountsJson maps fraction TNA to percent`() {
        val raw = """
            [
              {"fondo": "NARANJA X", "tna": 0.2, "tea": 0.2213, "tope": 1000000, "fecha": "2026-11-19"},
              {"fondo": "UALA", "tna": 0.19, "tea": 0.2092, "tope": 1000000, "fecha": "2026-11-19"}
            ]
        """.trimIndent()

        val rates = ComparatasasScraper.parseArgentinaDatosAccountsJson(raw)
        val nx = WalletEntity(name = "Naranja X", institution = "Naranja X", type = WalletType.WALLET)
        val matched = RateMatcher.find(nx, rates)
        assertNotNull(matched)
        assertEquals(20.0, matched!!, 0.01)
    }

    @Test
    fun `parseFullCatalog builds categorized items enriched with TEA cap and conditions`() {
        val entitiesRaw = """
            {
              "updatedAt": "2026-11-19T12:31:50.020Z",
              "entities": [
                {
                  "slug": "naranja-x",
                  "name": "Naranja X",
                  "type": "billetera",
                  "products": [
                    {"category": "cuentas-remuneradas", "product": "Naranja X", "value": 0.2, "metric": "TNA 20%"}
                  ]
                },
                {
                  "slug": "adcap",
                  "name": "Adcap",
                  "type": "broker",
                  "products": [
                    {"category": "fondos", "product": "Adcap Ahorro Pesos - Clase A", "value": 0.1969, "metric": "TNA 19,69%"},
                    {"category": "usd", "product": "Adcap Ahorro Dólares - Clase C", "value": 0.0171, "metric": "TNA 1,71%"},
                    {"category": "comisiones-brokers", "product": "Bonos", "value": 0.02, "metric": "Comisión 2%"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val accountsRaw = """
            [
              {"fondo": "NARANJA X", "tna": 0.2, "tea": 0.2213, "tope": 1000000, "fecha": "2026-11-19", "condicionesCorto": "Remunera hasta $1.000.000"}
            ]
        """.trimIndent()

        val catalog = ComparatasasScraper.parseFullCatalog(entitiesRaw, accountsRaw)

        assertEquals(4, catalog.items.size)
        val nx = catalog.items.first { it.categoryName == ComparaTasasCategory.CUENTAS_REMUNERADAS.name }
        assertEquals(20.0, nx.ratePercent ?: 0.0, 0.01)
        assertEquals(22.13, nx.teaPercent ?: 0.0, 0.01)
        assertEquals(1_000_000L, nx.capPesos)
        assertEquals("Remunera hasta $1.000.000", nx.conditions)

        val config = ComparaTasasConfig(
            selectedCategories = setOf(
                ComparaTasasCategory.CUENTAS_REMUNERADAS.name,
                ComparaTasasCategory.COMISIONES_BROKERS.name,
            ),
            showOnDashboard = true,
            catalog = catalog,
        )
        val decoded = ComparaTasasConfig.decode(ComparaTasasConfig.encode(config))
        assertEquals(2, decoded.activeCategories.size)
        assertTrue(decoded.showOnDashboard)
        assertEquals(4, decoded.catalog.items.size)
    }
}
