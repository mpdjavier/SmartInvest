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
        assertTrue(result.history.size >= 30)
        assertEquals(5.473196, result.history.last().price, 0.000001)
    }

    @Test
    fun `merges 3-decimal rava historico json prices over 2-decimal rounded html table`() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="S30N6 Letra del Tesoro | Cotizaciones | Rava Bursátil" />
              </head>
              <body>
                <div class="p2-quote">
                  <div class="p2-price">128,30</div>
                  <div class="p2-time"><span>11/11 12:14</span></div>
                </div>
                <dl class="p2-stats">
                  <div class="p2-stat"><dt>Anterior</dt><dd>128,42</dd></div>
                </dl>
                <table>
                  <thead>
                    <tr><th>Fecha</th><th>Cierre</th></tr>
                  </thead>
                  <tbody>
                    <tr><td>06/11/2026</td><td>128,42</td></tr>
                  </tbody>
                </table>
              </body>
            </html>
        """.trimIndent()
        val json = """
            {"simbolo":"S30N6","datos":[
              {"precio":128.415,"fecha":"2026-11-06T00:00:00.000Z"},
              {"precio":128.303,"fecha":"2026-11-11T00:00:00.000Z"}
            ]}
        """.trimIndent()

        val result = RavaClient.parseHtml("S30N6", html, LocalDate.of(2026, 11, 11), json)

        assertEquals(128.303, result.latestPrice, 0.0001)
        assertEquals(2, result.history.size)
        assertEquals(128.415, result.history.first().price, 0.0001)
        assertEquals(128.303, result.history.last().price, 0.0001)
    }

    @Test
    fun `rava off-hours html for S30N6 ignores TEM percent in meta description and keeps last closed session`() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="S30N6 LECAP ${'$'} 2,30% TEM Vto. 30.11.2026 | Cotizaciones | Rava Bursátil" />
                <meta property="og:description" content="Cotización del bono LECAP ${'$'} 2,30% TEM Vto. 30.11.2026 (S30N6) — precio, paridad, TIR, duration y flujo de fondos." />
              </head>
              <body>
                <header class="p2-hero">
                  <h1 class="p2-h1">
                    <span class="p2-ticker">S30N6</span>
                    <span class="p2-nombre">LECAP ${'$'} 2,30% TEM Vto. 30.11.2026</span>
                  </h1>
                </header>
                <div class="p2-ct-returns">
                  <div class="p2-ret"><span class="p2-ret-label">Precio</span><span class="p2-ret-val">129,05</span></div>
                  <div class="p2-ret"><span class="p2-ret-label">% Día</span><span class="p2-ret-val">-%</span></div>
                </div>
                <div class="tabla-cotizaciones p2-tabla p2-coti-hist">
                  <table>
                    <thead>
                      <tr><th>Fecha</th><th>Apertura</th><th>Máximo</th><th>Mínimo</th><th>Cierre</th><th>Volumen</th></tr>
                    </thead>
                    <tbody>
                      <tr><td>18/11/2026</td><td>129,49</td><td>129,49</td><td>128,88</td><td>129,05</td><td>164.198.651.078</td></tr>
                      <tr><td>17/11/2026</td><td>128,92</td><td>129,35</td><td>128,90</td><td>128,96</td><td>93.460.568.828</td></tr>
                    </tbody>
                  </table>
                </div>
              </body>
            </html>
        """.trimIndent()
        val json = """
            {"simbolo":"S30N6","datos":[
              {"precio":128.962,"volumen":93460568828,"fecha":"2026-11-17T00:00:00.000Z"},
              {"precio":129.08,"volumen":164198651078,"fecha":"2026-11-18T00:00:00.000Z"}
            ]}
        """.trimIndent()

        // Queried on 19/11/2026 before the market opens: must keep 18/11/2026 @ 129.08, never 2.30 or a fake 19/11 point.
        val result = RavaClient.parseHtml("S30N6", html, LocalDate.of(2026, 11, 19), json)

        assertEquals(LocalDate.of(2026, 11, 18), result.latestDate)
        assertEquals(129.08, result.latestPrice, 0.0001)
        assertEquals(2, result.history.size)
        assertEquals(LocalDate.of(2026, 11, 17), result.history[0].date)
        assertEquals(LocalDate.of(2026, 11, 18), result.history[1].date)
    }

    @Test
    fun `cafci money market TNA table converts annualized TNA to direct period returns without negative daily steps`() {
        val html = """
            <html>
              <body>
                <div id="consulta" class="divContent">
                  <h3>Rendimiento Histórico (TNA) — al 18/11/2026</h3>
                  <table class="tablaRendimientos">
                    <tr class="titleTable"><td>Período</td><td>Mercado Fondo - Clase A</td></tr>
                    <tr><td class="flechaTabla">Valor Cuotaparte</td><td>26.484,999</td></tr>
                    <tr><td class="flechaTabla">7 días</td><td>19,5000 %</td></tr>
                    <tr><td class="flechaTabla">1 mes</td><td>19,0000 %</td></tr>
                  </table>
                  <p>TNA = Tasa Nominal Anual sobre días corridos.</p>
                </div>
                <div id="cuotaparte" class="divContent">
                  <h3>Valores al 18/11/2026</h3>
                  <p>Valor por cada cuotaparte: 26,484999</p>
                </div>
              </body>
            </html>
        """.trimIndent()

        val result = CafciClient.parseHtml("798", "1982", html, LocalDate.of(2026, 11, 19))

        assertEquals(LocalDate.of(2026, 11, 18), result.latestDate)
        assertEquals(26.484999, result.latestPrice, 0.000001)
        assertTrue(result.history.size >= 30)
        // Every consecutive day must grow smoothly (~0.05% per day), never dip when 7d TNA > 30d TNA.
        for (i in 1 until result.history.size) {
            val prev = result.history[i - 1].price
            val curr = result.history[i].price
            assertTrue("VCP must grow on step $i ($prev -> $curr)", curr > prev)
            val dailyPct = (curr / prev - 1.0) * 100.0
            assertTrue("Daily return must be < 0.2% ($dailyPct%)", dailyPct < 0.20)
        }
    }

    @Test
    fun `rava parser resets on confirmed cedear split and keeps post split SPY quotes instead of pre split 56000`() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="SPY SPDR S&amp;P 500 | Cotizaciones | Rava Bursátil" />
              </head>
              <body>
                <div class="p2-quote">
                  <div class="p2-price">20.800,00</div>
                  <div class="p2-time"><span>20/11 10:45</span></div>
                </div>
                <div class="p2-ct-returns">
                  <div class="p2-ret"><span class="p2-ret-label">% Día</span><span class="p2-ret-val">-%</span></div>
                </div>
                <table>
                  <thead>
                    <tr><th>Fecha</th><th>Apertura</th><th>Máximo</th><th>Mínimo</th><th>Cierre</th><th>Volumen</th></tr>
                  </thead>
                  <tbody>
                    <tr><td>19/11/2026</td><td>21.060,00</td><td>21.200,00</td><td>20.720,00</td><td>20.890,00</td><td>549.985</td></tr>
                    <tr><td>18/11/2026</td><td>20.670,00</td><td>21.060,00</td><td>20.670,00</td><td>20.930,00</td><td>385.616</td></tr>
                    <tr><td>01/06/2026</td><td>20.000,00</td><td>20.000,00</td><td>18.700,00</td><td>18.880,00</td><td>412.655</td></tr>
                    <tr><td>29/05/2026</td><td>19.550,00</td><td>19.550,00</td><td>18.670,00</td><td>18.780,00</td><td>326.434</td></tr>
                    <tr><td>28/05/2026</td><td>55.800,00</td><td>56.075,00</td><td>55.525,00</td><td>56.000,00</td><td>101.156</td></tr>
                    <tr><td>27/05/2026</td><td>55.900,00</td><td>55.925,00</td><td>55.450,00</td><td>55.900,00</td><td>76.541</td></tr>
                  </tbody>
                </table>
              </body>
            </html>
        """.trimIndent()

        val result = RavaClient.parseHtml(
            symbol = "SPY",
            html = html,
            fallbackDate = LocalDate.of(2026, 11, 20),
        )

        assertEquals(20_890.0, result.latestPrice, 0.001)
        assertEquals(LocalDate.of(2026, 11, 19), result.latestDate)
        assertTrue("Pre-split 56.000 quotes must be discarded", result.history.none { it.price > 30_000.0 })
        assertEquals(LocalDate.of(2026, 5, 29), result.history.first().date)
    }
}
