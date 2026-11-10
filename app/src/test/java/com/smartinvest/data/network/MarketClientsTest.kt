package com.smartinvest.data.network

import com.smartinvest.domain.model.MarketSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MarketClientsTest {

    @Test
    fun `parses market keys and full urls for rava and cafci`() {
        assertEquals("rava:S30N6", MarketKeyParser.normalize("rava:s30n6"))
        assertEquals("rava:AO27D", MarketKeyParser.normalize("https://www.rava.com/perfil/AO27D"))
        assertEquals("cafci:1402:4070", MarketKeyParser.normalize("https://estadisticas.cafci.org.ar/fondos/1402?clase=4070"))
        assertEquals("cafci:798:1982", MarketKeyParser.normalize("https://estadisticas.cafci.org.ar/fondos/798?clase=1982"))
        assertEquals("cafci:1522:4557", MarketKeyParser.normalize("https://estadisticas.cafci.org.ar/fondos/1522?clase=4557"))
        assertEquals(MarketSource.RAVA, MarketKeyParser.sourceFor("rava:SPY"))
        assertEquals(MarketSource.CAFCI, MarketKeyParser.sourceFor("cafci:1402:4070"))
    }

    @Test
    fun `parses rava html with historical closes and meta price`() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="AO27D Bonar 2027 U${'$'}S | Cotizaciones | Rava Bursátil" />
                <meta property="og:description" content="${'$'}102,45 (+0,24%). Cotización del bono Bonar 2027 U${'$'}S (AO27D)." />
              </head>
              <body>
                <div class="tabla-cotizaciones p2-tabla p2-coti-hist">
                  <table>
                    <thead>
                      <tr><th>Fecha</th><th>Apertura</th><th>Máximo</th><th>Mínimo</th><th>Cierre</th><th>Volumen</th></tr>
                    </thead>
                    <tbody>
                      <tr><td>06/11/2026</td><td>102,50</td><td>102,80</td><td>101,30</td><td>102,45</td><td>845.636</td></tr>
                      <tr><td>05/11/2026</td><td>102,35</td><td>103,15</td><td>102,00</td><td>102,20</td><td>7.947.427</td></tr>
                    </tbody>
                  </table>
                </div>
              </body>
            </html>
        """.trimIndent()

        val result = RavaClient.parseHtml("AO27D", html, LocalDate.of(2026, 11, 6))

        assertEquals("rava:AO27D", result.marketKey)
        assertEquals("Bonar 2027 U\$S", result.name)
        assertEquals(102.45, result.latestPrice, 0.0001)
        assertEquals(2, result.history.size)
        assertEquals(LocalDate.of(2026, 11, 5), result.history.first().date)
        assertEquals(102.20, result.history.first().price, 0.0001)
        assertEquals(LocalDate.of(2026, 11, 6), result.history.last().date)
        assertEquals(102.45, result.history.last().price, 0.0001)
    }

    @Test
    fun `parses current rava hero quote with previous close and no history table`() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="AO27D Bonar 2027 U${'$'}S | Cotizaciones | Rava Bursátil" />
                <meta property="og:description" content="${'$'}102,45 (+0,24%). Cotización del bono Bonar 2027 U${'$'}S (AO27D)." />
              </head>
              <body>
                <div class="p2-quote">
                  <div class="p2-price">102,45</div>
                  <div class="p2-time"><span>06/11 18:45</span></div>
                </div>
                <dl class="p2-stats">
                  <div class="p2-stat"><dt>Anterior</dt><dd>102,20</dd></div>
                  <div class="p2-stat"><dt>Apertura</dt><dd>102,50</dd></div>
                  <div class="p2-stat"><dt>Máximo</dt><dd>102,80</dd></div>
                </dl>
              </body>
            </html>
        """.trimIndent()

        val result = RavaClient.parseHtml("AO27D", html, LocalDate.of(2026, 11, 10))

        assertEquals(102.45, result.latestPrice, 0.0001)
        assertEquals(LocalDate.of(2026, 11, 6), result.latestDate)
        assertEquals(2, result.history.size)
        assertEquals(LocalDate.of(2026, 11, 5), result.history.first().date)
        assertEquals(102.20, result.history.first().price, 0.0001)
        assertEquals(102.45, result.history.last().price, 0.0001)
    }

    @Test
    fun `parses cafci html for adcap balanceado iii with cuotaparte and historical returns`() {
        val html = """
            <html>
              <body>
                <table class="tablaRendimientos">
                  <tr class="titleTable"><td>Período</td><td>Adcap Balanceado III - Clase A</td></tr>
                  <tr><td class="flechaTabla">Valor Cuotaparte</td><td>5.473,196</td></tr>
                  <tr><td class="flechaTabla">7 días</td><td>0,7027 %</td></tr>
                  <tr><td class="flechaTabla">1 mes</td><td>3,1811 %</td></tr>
                </table>
                <div id="cuotaparte" class="divContent">
                  <h3>Valores al 05/11/2026</h3>
                  <p>Valor por cada cuotaparte: 5,473196</p>
                  <p>(Valor por mil: 5.473,196)</p>
                </div>
              </body>
            </html>
        """.trimIndent()

        val result = CafciClient.parseHtml("1402", "4070", html, LocalDate.of(2026, 11, 5))

        assertEquals("cafci:1402:4070", result.marketKey)
        assertEquals("Adcap Balanceado III - Clase A", result.name)
        assertEquals(5.473196, result.latestPrice, 0.000001)
        assertEquals(LocalDate.of(2026, 11, 5), result.latestDate)
        assertTrue(result.history.size >= 3)
        assertEquals(5.473196, result.history.last().price, 0.000001)
    }
}
