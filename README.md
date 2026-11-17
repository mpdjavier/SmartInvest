# SmartInvest

Aplicación Android nativa para **patrimonio personal + gastos compartidos del hogar**, pensada para Argentina: cartera en pesos y dólares, FCI (uno marcado como billetera para gastos), LECAP/BONCAP, cuotas de tarjeta, fondo común, reparto de sueldo, alertas y widget de pantalla de inicio.

> **Estado:** código fuente completo y **130 tests unitarios** pasando con JDK 17 y Android SDK 35. Ver [Build y ejecución](#build-y-ejecución).

---

## Índice

1. [Funcionalidades](#funcionalidades)
2. [Arquitectura](#arquitectura)
3. [Estructura del proyecto](#estructura-del-proyecto)
4. [Reglas de negocio y fórmulas](#reglas-de-negocio-y-fórmulas)
5. [Persistencia](#persistencia)
6. [Widget](#widget)
7. [Stack técnico](#stack-técnico)
8. [Build y ejecución](#build-y-ejecución)
9. [Tests](#tests)
10. [Limitaciones y próximos pasos](#limitaciones-y-próximos-pasos)

---

## Funcionalidades

### Hogar

- Integrantes dinámicos (por defecto **Vos** y **Mamá**), con color e indicador de usuario actual.
- Categorías editables (supermercado, servicios, expensas, auto, salud, comida, ocio, otros).
- Gastos en efectivo, transferencia, tarjeta de crédito, **fondo común** o **FCI billetera** (descuenta el saldo del FCI marcado como billetera). El medio predeterminado es el FCI billetera si está configurado; si no, efectivo.
- **Sugerencias de descripción**: al escribir los primeros caracteres, propone descripciones de gastos anteriores para cargar compras recurrentes con un toque.
- **Cuotas**: una compra en N cuotas impacta cada mes solo por el valor de la cuota, nunca por el total.
- Tarjetas con **cierre y vencimiento manuales**; la primera cuota se imputa según la fecha de compra y el día de cierre.
- Fondo común: aportes, extracciones y compras pagadas con el fondo, con saldo en tiempo real.
- **Liquidación de fin de mes** a partes iguales: muestra cuánto pagó cada uno, el objetivo y quién debe transferirle a quién.
- **Saldado manual del mes**: permite registrar manualmente el pago entre integrantes (con fecha, monto, nota y opción de avanzar al mes siguiente para arrancar de nuevo), o reabrir el mes si hace falta corregir algo.
- Navegación mensual y edición/eliminación de movimientos.
- **Importar comprobantes compartidos**: al compartir un PDF o texto desde el banco/billetera, SmartInvest lo recibe, extrae importe, fecha, origen, destino y servicio, y abre una pantalla de revisión editable antes de crear el gasto. Si detecta una **transferencia entre cuentas propias**, lo advierte y no la registra como gasto del hogar salvo confirmación explícita.

### Búsqueda y reportes de gastos

- **Búsqueda libre** (`expense_search`) sobre todos los gastos: descripción, notas, categoría, integrante, tarjeta y medio de pago. Ignora mayúsculas y acentos, así que «nafta» encuentra «Nafta» y «super» encuentra «Súper» o la categoría «Supermercado».
- **Filtros combinables** por período (este mes, últimos 3 meses, este año o todo el historial), categoría, medio de pago e integrante, con contador de resultados y total del filtro.
- Las compras en cuotas aparecen **una fila por cuota** con su mes imputado, y se pueden abrir para editarlas.
- **Reportes** (`expense_report`) con el mismo criterio de imputación mensual: total, promedio por mes, ticket promedio, gastos directos vs. cuotas, mayor gasto, evolución mensual y desglose por categoría, medio de pago e integrante.
- **Exportación CSV** con fecha, mes imputado, descripción, categoría, integrante, medio de pago, tarjeta, cuota, total de compra, monto del mes y notas. Usa `;` como separador y coma decimal para abrir directo en Excel o Google Sheets.
- **Resumen compartible** en texto para WhatsApp o e-mail, generado desde el mismo motor que la pantalla.
- Accesos directos desde **Inicio → Acciones rápidas** y desde **Hogar**.

### Cartera

- **FCI billetera**: cualquier FCI puede marcarse como cuenta de gastos 24/7. Solo uno queda activo a la vez; si está configurado, los gastos lo usan por defecto y descuentan su valor cargado (al eliminar el gasto se repone). Si no hay FCI billetera, el medio predeterminado es **efectivo** (ya no se ofrece débito).
- **Plazo de rescate por FCI**: T+0 para money market y **T+1 para Adcap Balanceado III** (rescata a 24 h), configurable con chips en el editor.
- Activos: LECAP, BONCAP, bonos, FCI, CEDEAR, dólar MEP, cripto y otros, en ARS o USD.
- **Seguimiento en vivo de instrumentos y FCI**:
  - **Rava** (`rava:S30N6`, `rava:AO27D`, `rava:SPY` o cualquier ticker/URL de `rava.com/perfil/...`): precio actual y curva histórica de cierres diarios. Si la página no publica la tabla histórica, se lee la cotización del encabezado junto con el cierre **Anterior**, así el activo ya aporta variación diaria desde la primera sincronización.
  - **CAFCI** (`cafci:1402:4070` Adcap Balanceado III, `cafci:798:1982` Mercado Fondo ARS, `cafci:1522:4557` Mercado Fondo USD o cualquier URL de `estadisticas.cafci.org.ar/fondos/...`): valor de cuotaparte (VCP) y evolución histórica.
  - Presets de 1 toque en el editor de activos + recálculo automático de valuación según cantidad y unidad (`Por unidad / cuotaparte` o `Por 100 nominales`).
- **Moneda nativa y rendimiento por activo**: una tenencia cargada en USD (por ejemplo `AO27D`) se muestra en **USD**, con la equivalencia en pesos como dato secundario, y cada fila indica cuánto aporta al **interés diario**.
- **Desglose de intereses día a día** (`daily_interest`): muestra jornada por jornada cuánto generó cada FCI, letra, bono o cuenta y la fórmula exacta usada (`Base × TNA ÷ 365` con tope remunerado, o `Cantidad × (Precio cierre − Precio anterior)` con datos reales de mercado). **El interés diario del panel suma todos los activos con seguimiento automático** (Rava y CAFCI), no solo los FCI: cada tenencia aporta la variación entre sus dos últimas cotizaciones y, si no hay par de precios, se usa su TNA.
- **Capitalización diaria**: los intereses de cada jornada se suman a la base del día siguiente. En los FCI, cuando CAFCI todavía no publicó la cuotaparte, el hueco se completa aplicando el factor diario del último intervalo sobre el precio del día anterior, así el valor del FCI y su interés crecen en lugar de repetir siempre el mismo monto base.
- **Conciliación de intereses**: tarjeta “¿No coinciden los números?” que compara la proyección diaria (TNA actual × días) contra el acumulado real, separa la parte explicada por capitalización y por variación de mercado, y muestra el remanente sin explicar (topes y redondeos).
- Valoración unificada en pesos usando **dólar MEP** (DolarAPI) con fallback configurable.
- Historial de tasas y snapshots diarios del patrimonio.
- Analítica 7 días / 30 días / YTD, ganancia diaria y mensual, serie para gráfico.
- **Rebalanceo elástico 60/40** con banda de tolerancia configurable: **tanto los CEDEARs (`SPY`) como los bonos soberanos (`AO27` y `AO27D`) y FCI en dólares (`MP-USD`) se computan como exposición en dólares aunque estén valuados en pesos**, ya que mantienen su valor en moneda dura.
- **Ajuste manual y control de saldos** (`portfolio_reconciliation`): permite fijar la cifra exacta disponible hoy en cada activo en **ARS o USD** (con conversión inmediata al MEP y bloqueo manual `[ajuste manual: dd/MM/yyyy]` para que la sincronización de Rava/CAFCI no pise el saldo real), pegar una lista de saldos (`TICKER (CUENTA) MONTO ARS/USD`) o restaurar la cartera del 16/11/2026 en 1 toque (saldos **y** cantidades de AO27D, ADCAP-BAL3, MP-ARS y MP-USD, para que los 9 activos se actualicen solos), y comparar **Valor real fijado vs. Valor calculado por cotizaciones**, precio implícito por unidad/nominal y evolución real frente al costo neto invertido.
- **Asistente macro de carry + rebalanceo orgánico** (`rebalancing`): semáforo de riesgo 0–100 (tasa real en pesos, brecha oficial/MEP con nivel y velocidad, y riesgo país), histéresis y techo AMARILLO por riesgo país, techo VERDE por calendario electoral, guardarraíl de liquidez para el sobrepeso en pesos, vencimientos como flujo orgánico, meta de pesos ajustada por riesgo y reparto exacto del **próximo ingreso** entre pesos y cobertura en dólares. **Nunca vende activos**: solo canaliza flujo nuevo.
- **Evaluador FCI con estabilidad configurable** (`fund_evaluator`): compara el universo Eco Valores o **las 2.300+ clases del catálogo completo**, con la rentabilidad y la estabilidad como parámetros independientes. Podés ordenar por rentabilidad, estabilidad, máxima caída o volatilidad, elegir el horizonte de rentabilidad (7d/30d/12m) y poner un **piso de estabilidad opcional** (slider 0–100; por ejemplo ≥ 70) para descartar fondos con saltos. En el seguimiento las métricas salen de la serie diaria en ventanas de 30/90/180/365 días; en el catálogo completo se estiman con volatilidad, caída y días positivos a 90 días. Referencia **Adcap Balanceado III**, detalle y simulador en la misma fila, y buscador para seguir cualquier clase. Datos de CAFCI/CNV vía compararfondos.com.ar.
- Planificador de sueldo y simulador de rescate de emergencia.

### Importación desde SplitHome

- Se accede desde **Ajustes → Importar datos**.
- Lee directamente el reporte **`.xlsx`** exportado por SplitHome (sin pasos intermedios ni dependencias externas).
- Importa la hoja **Gastos** (fecha, descripción, monto, pagador y categoría) y el **saldo inicial** de la hoja **Fondo Comun**.
- **J = Vos**, **M = Mamá** y **Fondo = gasto pagado por el fondo común**.
- Las categorías faltantes se crean automáticamente con emoji y color.
- Las filas con `(Cuota 1/N)` se convierten en una compra de **N cuotas** por el total (`cuota × N`), imputando la primera cuota al mes de compra.
- Las compras con fondo generan su movimiento de fondo y quedan fuera del reparto por integrante.
- **Vista previa** con totales, conteos y categorías nuevas antes de confirmar, y **reporte** al finalizar.
- Verificación de duplicados: si ya hay gastos cargados, pide una confirmación explícita.
- La importación corre en **una sola transacción**: si falla, no se guarda nada.

### Importación de comprobantes (compartir)

- Compartí un **PDF o texto** desde la app del banco/billetera y elegí **SmartInvest** en el menú de compartir (`ACTION_SEND`).
- También se puede elegir el archivo desde **Importar datos → Elegir comprobante** (`application/pdf`, `text/plain` o `image/*`).
- El archivo se copia al caché de la app y se extrae el texto con **PDFBox Android**; los PDF escaneados como imagen se informan y se pueden completar a mano.
- El parser tolerante reconoce:
  - **Importe** (`Importe`, `Monto`, `Total` o el mayor importe con `$`), incluso cuando la etiqueta (`Por un importe de`) y el valor (`$ 15.105,73`) están en **líneas separadas**. Descarta números de cuenta, CBU/CVU y de transacción (tiradas de 9+ dígitos sin decimales) para no confundirlos con el monto.
  - **Fecha** en formatos `dd/mm/aaaa`, `dd/mes/aaaa`, `dd de mes de aaaa` e ISO.
  - **Origen y destino** por nombre de banco/billetera (Mercado Pago, Galicia, Santander, BBVA, Nación, Provincia, Ualá, etc.).
  - **Concepto/servicio**: líneas `Pago de …` (por ejemplo `Pago de SM Tucumán CISI`), además de `Empresa`, `Servicio`, `Prestador`, `Comercio`, `Razón social` y `Beneficiario`.
  - **Referencia** (`Operación`, `Código`, `Nro.`), incluido el formato en dos líneas (`Nro° Trans` y el número debajo).
  - **Resúmenes de tarjeta de crédito** (por ejemplo `MASTERCARD BLACK` de Banco Galicia): detecta automáticamente la **tarjeta**, la **fecha de vencimiento** (`20-Nov-26`), la **fecha de próximo cierre** (`17-Dic-26`) y el **monto a pagar** (`TOTAL A PAGAR 247.282,15`, ignorando límites de compra y pago mínimo). Al confirmar, actualiza los días de cierre/vencimiento de la tarjeta, agenda el vencimiento y el próximo cierre en la **Agenda de pagos** y genera las **alertas de vencimiento y próximo cierre** (con opción de registrar también el pago si ya fue abonado).
- Pantalla de **revisión editable** con descripción, monto, fecha, categoría, quién pagó y notas.
- Si el comprobante parece una **transferencia entre cuentas propias**, la app lo advierte y desactiva el guardado como gasto hasta que el usuario lo confirme.
- Ruta interna: `receipt_import/{receiptPath}`.

### Alertas y automatización

- Baja de tasa por billetera (comparación con la lectura anterior).
- Vencimiento de LECAP/BONCAP/bonos.
- **AO27D por debajo de USD 20.000** (umbral configurable).
- Cartera fuera de la banda de rebalanceo.
- Vencimiento próximo de tarjetas.
- Fondo común en negativo.
- Notificaciones locales, historial de alertas y marcado de leídas.
- Trabajos periódicos con **WorkManager**: tasas, snapshots, alertas y widget.

### Widget

- Patrimonio total en ARS y USD.
- Interés diario estimado.
- Cotización MEP usada (y aviso si es estimada).
- Total y cantidad de cuotas del mes.
- Estado del hogar (quién transfiere a quién o cuentas equilibradas).
- Botón de actualización manual.
- Se actualiza al abrir la app y mediante WorkManager; respeta el modo de privacidad (asteriscos) igual que la app.

### Privacidad de cifras

- **Ajustes → Ocultar cifras** reemplaza todos los montos por `$ ****` / `US$ ****` en Inicio, Hogar, Cartera, analítica y widget. El mismo interruptor está disponible como **icono de ojo** en la barra superior de Inicio.
- **Ayudas contextuales**: los valores que pueden prestarse a confusión (ganancia no realizada, interés diario, proyección mensual) tienen un icono **?** que despliega una explicación breve.
- El estado es global y se guarda en DataStore (`hide_amounts`), así que se mantiene al reiniciar la app.
- Porcentajes, fechas, cantidades y precios de mercado siguen visibles para no perder contexto.
- El detalle de las fórmulas del interés diario se oculta cuando el modo está activo.

### Estética de terminal financiero

- Interfaz inspirada en [BondTerminal](https://bondterminal.com/): **IBM Plex Mono** para marca, etiquetas y cifras, e **IBM Plex Sans** para textos largos.
- Paleta verde-negro: fondo `#030A07`, paneles `#0D1318` / `#111820`, texto `#E6EBF2`, acento verde neón `#00FF6A`, ámbar `#FFB000` y rojo `#FF5C5C`.
- Tarjetas planas con borde de 1 px, esquinas compactas (2–6 dp), encabezados en mayúsculas con regla ámbar y píldoras rectangulares.
- Barra superior con wordmark `SMARTINVEST` y barra inferior con etiquetas monoespaciadas; el widget, las notificaciones y el ícono usan la misma paleta.
- Fuentes IBM Plex empaquetadas en `res/font` bajo licencia SIL OFL (`assets/licenses/IBM_Plex_OFL.txt`).

---

## Arquitectura

Arquitectura por capas con dependencias unidireccionales:

```
UI (Compose + ViewModels)
        │
        ▼
Domain (model · engine · repository interfaces · use cases)
        ▲
        │
Data (Room · DataStore · Retrofit · Jsoup · repositorios)
```

- **`core`**: utilidades puras de dinero y fechas. El dinero se representa siempre en **centavos enteros (`Long`)** para evitar errores de coma flotante.
- **`domain/model`**: modelos inmutables de hogar, cartera, planificación y alertas.
- **`domain/engine`**: motores de cálculo puros y testeables (cuotas, liquidación, valoración, analítica, rebalanceo, sueldo, rescate).
- **`domain/repository`**: contratos de acceso a datos.
- **`domain/usecase`**: composición de flujos para las pantallas (`ObserveHouseholdSummaryUseCase`, `ObservePortfolioOverviewUseCase`).
- **`data/local`**: Room (entidades, DAOs, convertidores, mappers) y DataStore para ajustes.
- **`data/network`**: DolarAPI (Retrofit + kotlinx.serialization) y scraper best-effort de Comparatasas (Jsoup).
- **`data/repository`**: implementaciones, sincronización de tasas, bootstrap y alertas.
- **`data/worker`** / **`work`**: workers de Hilt + programación periódica.
- **`ui`**: tema oscuro, componentes reutilizables, pantallas Compose y ViewModels Hilt.
- **`di`**: módulos Hilt (base de datos, repositorios, red, dispatchers).
- **`widget`** / **`notification`**: RemoteViews del widget y canal de notificaciones.

### Navegación

Cuatro pestañas principales: **Inicio**, **Hogar**, **Cartera**, **Ajustes**.

Pantallas secundarias (con barra superior y flecha de volver):

| Ruta | Pantalla |
| --- | --- |
| `expense_editor/{month}?expenseId={expenseId}` | Alta y edición de gastos (con reversión/imputación automática de medios de pago) |
| `fund_editor/{month}` | Movimiento del fondo común |
| `payment_agenda` | Agenda mensual de pagos de servicios (hereda fecha del pago anterior, alertas y pago directo) |
| `expense_search` | Búsqueda de gastos por texto, período, categoría, medio de pago e integrante |
| `expense_report` | Reporte de gastos del período con exportación CSV y resumen compartible |
| `receipt_import/{receiptPath}` | Importación de comprobantes PDF/texto (quita «Pago de», categoría Servicios y rescate en FCI billetera) |
| `holding_editor/{holdingId}` | Activo (alta/edición, valuación manual fija en ARS/USD, sumar/retirar aportes e importador `.xlsx`/`.xls`/`.csv` de brókers) |
| `portfolio_reconciliation` | Ajuste manual de saldos en ARS y USD, carga de lista por texto y verificación de cálculos/evolución |
| `fund_evaluator` | Evaluador FCI con rentabilidad y estabilidad configurables: seguimiento o catálogo completo, orden por rentabilidad/estabilidad/caída/volatilidad, piso de estabilidad, referencia Adcap Balanceado III, simulador y buscador |
| `analytics` | Analítica de cartera |
| `salary` | Repartir sueldo |
| `emergency` | Rescate de emergencia |
| `alerts` | Alertas |
| `import` | Importar datos de SplitHome |
| `daily_interest` | Intereses día a día, capitalización y base de cálculo |
| `instrument_detail/{holdingId}` | Evolución histórica de instrumentos (Rava) y FCI (CAFCI) |
| `rebalancing` | Asistente macro: carry, semáforo y reparto del próximo ingreso |

---

## Estructura del proyecto

```
app/src/main/java/com/smartinvest/
├── MainActivity.kt
├── SmartInvestApp.kt
├── core/
│   ├── money/            # MoneyMath, MoneyParser, MoneyFormatter
│   └── time/             # DateUtils (zona America/Argentina/Buenos_Aires)
├── data/
│   ├── local/            # AppDatabase, entidades, DAOs, convertidores, mappers
│   ├── importer/         # XlsxReader (lector .xlsx sin dependencias)
│   ├── network/          # DolarAPI, ComparatasasScraper, RateMatcher
│   ├── repository/       # implementaciones + RateSyncService + bootstrap
│   ├── widget/           # WidgetRefresher
│   └── worker/           # RateSync, Snapshot, AlertScan, WidgetUpdate
├── di/                   # AppModule, DatabaseModule, RepositoryModule
├── domain/
│   ├── engine/           # InstallmentEngine, SettlementCalculator, ...
│   ├── importer/         # SplitHomeParser (hojas Gastos y Fondo Comun)
│   ├── model/            # Household, Portfolio, Planning, Alerts
│   ├── repository/       # interfaces
│   └── usecase/          # flujos compuestos
├── notification/         # canal y notificaciones
├── ui/
│   ├── alerts/           # pantalla + ViewModel
│   ├── analytics/
│   ├── app/              # AppViewModel (bootstrap/sync)
│   ├── components/       # tarjetas, inputs, chips, gráficos
│   ├── dashboard/
│   ├── household/
│   ├── importer/         # pantalla + ViewModel de importación
│   ├── investments/
│   ├── navigation/       # Routes + NavHost
│   ├── planning/         # sueldo, emergencia, LiquiditySources
│   ├── rebalancing/      # asistente macro y rebalanceo orgánico
│   ├── settings/
│   ├── theme/
│   └── util/             # Fmt, colorFromHex
├── widget/               # provider, renderer, data loader
└── work/                 # WorkScheduler

app/src/test/java/com/smartinvest/
├── core/money/MoneyParserTest.kt
├── data/importer/XlsxReaderTest.kt
├── data/network/MarketClientsTest.kt
├── domain/engine/
│   ├── InstallmentEngineTest.kt
│   ├── SettlementCalculatorTest.kt
│   ├── PortfolioValuationTest.kt
│   ├── DailyInterestEngineTest.kt
│   ├── ExpenseReportEngineTest.kt
│   ├── PaymentAgendaEngineTest.kt
│   ├── PortfolioReconciliationEngineTest.kt
│   ├── MacroRiskTest.kt
│   └── PlanningTest.kt
├── data/importer/ReceiptParserTest.kt
└── domain/importer/
    └── SplitHomeParserTest.kt
```

---

## Reglas de negocio y fórmulas

### Dinero

- Todo importe se guarda y calcula en **centavos** (`Long`): `1_000_00` = 100.000 centavos = **$1.000,00**.
- El formateo usa locale `es-AR`.
- Los repartos usan el **método del mayor resto** para que la suma de las partes sea exactamente igual al total.

### Cuotas y ciclo de tarjeta

- `InstallmentEngine.schedule(total, N, primerMes)` divide el total en N cuotas sin perder centavos.
- **Primera cuota**:
  - compra **después** del día de cierre → mes siguiente;
  - compra **el mismo día de cierre o antes** → mes actual.
- **Vencimiento**: si el día de vencimiento es posterior al de cierre, vence en el mismo mes; si no, vence al mes siguiente.
- En la base se guarda el cronograma completo (`installments`). Las consultas del mes suman **solo** `installments.amountCents` de las cuotas cuyo `dueMonth` coincide; nunca el total de la compra.
- Máximo: 60 cuotas.

### Búsqueda y reportes de gastos

- La búsqueda y los reportes trabajan sobre **una fila por impacto mensual**: los gastos en efectivo, transferencia, fondo común y FCI aparecen una vez en su mes; las compras con tarjeta aparecen una vez por cuota, con el **mes de vencimiento** como mes imputado y el **valor de la cuota** como monto.
- `totalCents` conserva el total de la compra y `impactCents` el monto del mes, así el CSV muestra ambos sin inflar los totales.
- El filtro de texto normaliza mayúsculas y acentos (`Normalizer.Form.NFD`) y busca en descripción, notas, cuota, categoría, integrante, tarjeta y medio de pago.
- Los promedios usan los meses **con datos** dentro del período, no la cantidad de meses del calendario.
- El CSV se genera con BOM UTF-8, separador `;`, coma decimal y escape de campos con `;`, comillas o saltos de línea. Respeta el modo privacidad solo en pantalla: el archivo exporta los valores reales.
- La descarga usa `ActivityResultContracts.CreateDocument("text/csv")`, sin `FileProvider` ni permisos de almacenamiento.

### Liquidación del hogar

Sea para cada integrante participante:

```
neto = gastos_directos_pagados + aportes_al_fondo − extracciones_del_fondo
total_neto = Σ neto
objetivo = reparto exacto de total_neto en partes iguales
ajuste_saldado = +pagos_registrados (pagador) − pagos_registrados (receptor)
diferencia_pendiente = neto − objetivo + ajuste_saldado
```

Reglas:

1. Los gastos pagados con **fondo común** no se imputan a una persona: los cubre el fondo y se muestran aparte.
2. Las **extracciones** reducen el aporte neto de quien las hizo (uso personal).
3. El **saldo preexistente** del fondo es una reserva compartida y no altera la igualación del mes.
4. Los centavos sobrantes del reparto se asignan a los mayores aportantes.
5. La suma de los objetivos siempre coincide con el total neto, incluso cuando es negativo (división con piso).
6. Con las diferencias pendientes se generan transferencias **deudor → acreedor** de forma greedy, minimizando la cantidad de operaciones.
7. Cada **saldado manual** registrado en el mes resta la deuda del pagador y el crédito del receptor. Si el pago cubre todo, la diferencia queda en cero y el mes figura como **Mes saldado**; si es parcial, la app muestra **Saldo pendiente** con el remanente exacto y conserva los pagos ya registrados.

**Ejemplo**

| Concepto | Vos | Mamá |
| --- | ---: | ---: |
| Gastos directos pagados | $50.000 | $10.000 |
| Aportes al fondo | $20.000 | $0 |
| Extracciones del fondo | $0 | $5.000 |
| **Neto** | **$70.000** | **$5.000** |

Total neto = $75.000 → objetivo = $37.500 cada uno.

- Vos: +$32.500 (debe recibir).
- Mamá: −$32.500 (debe transferir).
- Resultado: **Mamá → Vos $32.500**.

### Valoración de cartera

- **MEP**: se toma la cotización con `house == "bolsa"`; si no existe, `"mep"`; luego `"blue"`. Se usa el precio de venta. Si no hay dato válido se usa `fallbackMepRate` (por defecto 1.000) y se marca `mepIsEstimated = true`.
- Saldo en ARS: valor nominal en centavos.
- Saldo en USD: `saldo_usd × MEP`.
- **Interés diario**:

```
base = min(saldo, tope_remunerado)      // tope = 0 → sin tope
interés_diario = base × TNA / 100 / 365
proyección_mensual = interés_diario × 30
```

- Patrimonio total = activos ARS + activos USD convertidos a ARS.
- Patrimonio en USD = patrimonio total ARS / MEP.
- Costo total: costo de tenencias (o valor actual si el costo es 0) + saldos de billeteras, convirtiendo USD a ARS.
- **TNA efectiva de una billetera**: si tiene un FCI vinculado con TNA > 0, se usa esa TNA; si no, la TNA manual. La tenencia vinculada no vuelve a devengar TNA por su cuenta para evitar doble conteo.
- **Conciliación del interés del mes**:

```
base_día_1 = saldo_actual
base_día_n = base_día_(n-1) + interés_día_(n-1)
interés_día_n = base_día_n × TNA / 100 / 365

proyección    = interés_día_1 × días_del_mes
diferencia    = acumulado_real − proyección
capitalización = acumulado_TNA − proyección
mercado       = variación_de_cotizaciones (Rava/CAFCI)
sin_explicar  = diferencia − capitalización − mercado
```

El acumulado parte del saldo y la TNA **actuales** y reinvierte los intereses de cada día. Para los FCI sin cotización fresca, el precio se proyecta día a día con el factor del último intervalo CAFCI. La tarjeta de conciliación separa la parte explicada por capitalización, la de mercado y el remanente por topes/redondeos.

### Analítica

- Ventanas de **7 días**, **30 días** y **YTD**.
- Se compara el valor actual contra el último snapshot anterior o igual a la fecha de inicio de la ventana.
- `% = (valor_actual − valor_base) / valor_base × 100`.
- Ganancia de 30 días y ganancia promedio por día = ganancia / 30.
- El gráfico usa los snapshots de los últimos 90 días.

### Rebalanceo 60/40

```
peso_% = activos_ARS / patrimonio_total × 100
banda = objetivo ± tolerancia        // por defecto 60% ± 5 pp
```

- Fuera de banda se sugiere mover la diferencia hacia dólar MEP o hacia instrumentos en pesos según corresponda.
- El objetivo y la tolerancia se configuran en Ajustes.

### Asistente macro y rebalanceo orgánico

El índice mide **solo el escenario macro**. El desvío de cartera quedó fuera a propósito:
es la variable de control, no una señal de mercado, y usarlo en el índice lo volvía circular.

```
componente_tasa_real   = 40 × clamp((4% − tasa_real_mensual) / 5, 0, 1)
componente_brecha_nivel = 25 × clamp((brecha_MEP − 5%) / 7, 0, 1)
componente_brecha_vel   = 10 × clamp((brecha_hoy − brecha_hace_30d − 1 pp) / 1,5, 0, 1)
componente_riesgo_país  = 15 × clamp((nivel − 500) / 500, 0, 1)
                        + 10 × clamp(variación_30d / 150, 0, 1)
riesgo = clamp(tasa_real + brecha_nivel + brecha_vel + riesgo_país, 0, 100)
```

Tasa real y brecha:

```
tasa_real_mensual = (1 + TNA/12) / (1 + devaluación_mensual) − 1
tasa_real_anual   = (1 + tasa_real_mensual)^12 − 1
brecha_%          = (MEP / oficial − 1) × 100
velocidad_brecha  = max(brecha_hoy − brecha_hace_30d, 0)
```

Reglas de estado:

- **Techo AMARILLO por riesgo país**: nivel > 800 pts o suba > 150 pts en 30 días ⇒ la banda no puede ser VERDE.
- **Techo AMARILLO por velocidad**: si la brecha se expande ≥ 2,5 pp en 30 días, la banda no puede ser VERDE aunque el nivel siga bajo.
- **Histéresis**: se entra a ROJO en 66 pero se sale recién en 55; se entra a VERDE en 30 y se sale recién en 35. La banda efectiva se persiste en DataStore.
- **Calendario electoral** (techo del VERDE en pesos): hasta feb/2027 70%; mar–jun/2027 65%; jul–nov/2027 60% (elección general + balotaje); después vuelve a 70%.
- **Cadencia**: la lectura puede ser semanal, pero la decisión de flujo se toma una vez al mes, en la fecha del sueldo.

| Índice | Banda | Meta de pesos ajustada |
| --- | --- | --- |
| 0 – 30 | 🟢 Riesgo bajo | `min(70%, techo electoral)` |
| 31 – 65 | 🟡 Riesgo moderado | objetivo estratégico (por defecto 60%) |
| 66 – 100 | 🔴 Riesgo alto | objetivo ROJO configurable (por defecto 50%) |

- **TNA de referencia**: si hay una TNA manual (> 0) se usa esa; si no, el promedio ponderado por saldo de las billeteras ARS y de las tenencias en pesos con TNA (LECAP, BONCAP, FCI, bonos y otros).
- **Brecha**: dólar oficial vs. MEP de DolarAPI. Si falta el oficial se usa el MEP (brecha 0).
- **Riesgo país**: se carga a mano en Ajustes (nivel actual y variación de 30 días). Si no hay dato, el componente suma 0 y la UI lo advierte.
- **Exposición**: se mide por riesgo subyacente. Los CEDEARs cuentan como dólares aunque coticen en pesos. El pozo de liquidez de Mercado Pago y la cubeta de viajes quedan fuera de la ratio invertible; los patrones de exclusión se editan en Ajustes (`carryExcludedPatterns`, por defecto `Mercado Pago, viaje`).

Canalización del próximo ingreso (rebalanceo orgánico, sin vender activos):

```
total_proyectado = patrimonio_total + ingreso
pesos_deseados   = meta_riesgo × total_proyectado − pesos_actuales
a_pesos          = clamp(pesos_deseados, 0, ingreso)
a_dólares        = ingreso − a_pesos
```

- Si la cartera está **sobreponderada en pesos**, `a_pesos = 0` y el **100%** del ingreso va a cobertura en dólares.
- Si está **sobreponderada en dólares**, `a_pesos = ingreso` y el **100%** va al tramo en pesos.
- El tramo en pesos se reparte entre **carry FCI T+1** y **tasa fija (LECAP/BONCAP)**; el tramo en dólares entre **MEP directo** y **bonos soberanos (AO27D)** según los porcentajes de Ajustes.
- Las piernas siempre suman exactamente el ingreso y **ningún activo se vende**.

**Vencimientos como flujo orgánico**: los instrumentos en pesos que vencen dentro de
`maturityAlertDays` (30 por defecto) no se renuevan por inercia. El plan los muestra y
propone el mismo reparto del semáforo (en ROJO, 100% a dólares). El plan también muestra
la **velocidad de realineamiento**: cuántos meses del próximo sueldo insumen cerrar la
brecha hasta la meta ajustada (por ejemplo, 10 pp ≈ 2,4 meses con un ahorro de USD 3.800/mes).

**Guardarraíl de liquidez**: el tramo de pesos por encima del objetivo estratégico solo
puede estar en instrumentos con vencimiento ≤ 6 meses o de rescate T+0/24 h. Si hay
sobrepeso en instrumentos largos, la UI lo marca para revisar.


### Reparto de sueldo

```
liquidez   = monto_24_7 configurado (si es 0, gasto_real_del_hogar)
disponible = max(sueldo_neto − liquidez, 0)
déficit    = max(gasto_hogar − sueldo_neto, 0)
```

El esquema tiene **cinco líneas**:

1. **Gastos / liquidez 24/7** → FCI marcado como billetera (monto fijo mensual, no porcentaje).
2. **Carry principal (T+1)** → FCI Adcap Balanceado III (45% del tramo pesos por defecto).
3. **Tasa fija (bróker)** → LECAP / BONCAP, por ejemplo T30A7 (15% del tramo pesos).
4. **Cobertura / MEP** → dólar MEP directo (25% del tramo dólares).
5. **Resguardo / piso** → bonos soberanos AO27D (15% del tramo dólares).

- El **tramo pesos/dólares** lo define `riskAdjustedTargetPesoPercent` del semáforo de carry (ejemplo 60/40), no un fijo.
- Los cuatro porcentajes (45/15/25/15) son **intra-tramo** y se normalizan si no suman 100%.
- Si el sueldo no alcanza, se informa el déficit sin generar líneas de inversión.
- Cobertura de emergencia = fuentes con rescate ≤ 24 h / gasto mensual del hogar (en meses).

### Rescate de emergencia

Orden de liquidez:

1. **FCI billetera** (rescate inmediato).
2. **FCI con rescate ≤ 24 h** (Adcap Balanceado III es T+1).
3. **Cuentas / billeteras existentes**.
4. **LECAP / BONCAP / bonos** (venta T+1).
5. **Dólares / MEP**.
6. **CEDEAR / cripto / otros**.

El simulador toma fuentes por tier y días de liquidación, calcula lo cubierto, el faltante y el plazo máximo.

### Importación de SplitHome

- Se acepta el `.xlsx` original (ZIP + XML). No se usan CSV intermedios ni Apache POI.
- **Hoja Gastos**: se detectan encabezados por nombre (tolerante a mayúsculas y acentos) y se leen `Fecha`, `Descripcion`, `Monto`, `Pagado por` y `Categoria`.
- Fechas: `d/M/yyyy`, `d-M-yyyy`, `yyyy-M-d` o número de serie de Excel.
- Montos: formato argentino o notación científica; se rechazan importes ≤ 0.
- **Pagadores**: `J`/`Vos` → usuario actual; `M`/`Mamá` → otro integrante; `Fondo` → `COMMON_FUND`.
- **Cuotas**: solo `(Cuota 1/N)` genera cronograma. El monto de la fila es **una cuota**, así que el total es `monto × N`. Otras cuotas se importan como gasto único con aviso.
- Las compras en cuotas usan una tarjeta sintética **SplitHome (importada)** (cierre 31, vencimiento 10) para imputar la primera cuota.
- **Fondo común**: el saldo inicial se registra como aporte **sin integrante** (`memberId = null`): no altera el reparto del mes pero el saldo coincide con el reporte. Cada compra con fondo genera su movimiento `EXPENSE` asociado.
- Las filas incompletas se omiten y se informan; si el archivo no tiene gastos válidos o le faltan columnas obligatorias, la importación se cancela.
- Tope de archivo: **10 MB** en la UI y **20 MB por entrada** del ZIP.
- Todo se inserta dentro de `db.withTransaction`: **todo o nada**.

**Ejemplo del reporte real**

| Dato | Valor |
| --- | ---: |
| Gastos | 71 |
| Total registrado | $5.026.652,82 |
| Vos (J) | $2.742.451,29 |
| Mamá (M) | $887.365,52 |
| Fondo | $1.396.836,01 |
| Saldo inicial del fondo | $1.778.466,21 |
| Saldo final del fondo | $381.630,20 |

### Alertas

| Alerta | Regla | Severidad |
| --- | --- | --- |
| Baja de tasa | caída ≥ `rateDropThresholdPp` (0,5 pp) vs lectura anterior | WARNING |
| Vencimiento | vence entre ayer y `maturityAlertDays` (30 días) | WARNING si ≤ 7 días, si no INFO |
| AO27D | suma AO27D < `ao27dThresholdUsdCents` (USD 20.000) | CRITICAL |
| Rebalanceo | cartera fuera de la banda objetivo ± tolerancia | WARNING |
| Tarjeta | vencimiento en 0–5 días | WARNING si ≤ 2 días, si no INFO |
| Fondo común | saldo negativo | CRITICAL |

- Cada alerta tiene `dedupeKey` para no repetirse (por ejemplo, semanal para AO27D y rebalanceo).
- Se eliminan alertas de más de 90 días.
- Las notificaciones respetan el switch de Ajustes y el permiso `POST_NOTIFICATIONS`.

### Trabajos periódicos

| Trabajo | Frecuencia | Notas |
| --- | --- | --- |
| `RateSyncWorker` | cada 6 h | requiere red; actualiza dólar y TNA |
| `PortfolioSnapshotWorker` | diario 23:30 | guarda snapshot del patrimonio |
| `AlertScanWorker` | diario 08:00 | escanea reglas y notifica |
| `WidgetUpdateWorker` | cada 1 h | refresca el widget |

Además, el widget se refresca al escribir datos y con el botón manual. WorkManager se inicializa **on-demand** con `HiltWorkerFactory`.

---

## Persistencia

Room, versión 4 (con migraciones no destructivas `MIGRATION_1_2`, `MIGRATION_2_3` y `MIGRATION_3_4`, y esquema exportado a `app/schemas`):

| Tabla | Contenido |
| --- | --- |
| `members` | integrantes del hogar |
| `categories` | categorías de gasto |
| `credit_cards` | tarjetas, cierre y vencimiento |
| `expenses` | compras y gastos (incluye el rescate del FCI billetera y su delta para reponer al eliminar) |
| `installments` | cronograma de cuotas por mes |
| `fund_movements` | aportes, extracciones y compras del fondo |
| `settlements` | cierres/pagos manuales de liquidación mensual del hogar |
| `wallets` | cuentas heredadas de versiones anteriores (`linkedHoldingId`) |
| `holdings` | LECAP, BONCAP, bonos, FCI, CEDEAR, etc. (con `marketKey`, `source`, `quantity`, `quoteUnit`, `isWallet`, `settlementDays`) |
| `instrument_quotes` | serie histórica de precios y valores de cuotaparte (Rava / CAFCI) |
| `portfolio_snapshots` | evolución diaria del patrimonio |
| `fx_rates` | cotizaciones (MEP, blue, oficial…) |
| `rate_history` | historial de TNA por billetera |
| `salary_plans` | planes de sueldo guardados (5 líneas: liquidez, carry, tasa fija, MEP y bonos) |
| `alerts` | alertas generadas |

Los ajustes (pesos del sueldo, umbrales, tolerancia, MEP de fallback, devaluación mensual esperada, TNA de referencia en pesos, notificaciones) viven en **DataStore Preferences**.

### Datos iniciales

En el primer arranque se crean:

- Integrantes **Vos** y **Mamá**.
- Ocho categorías.
- Cinco cuentas heredadas de referencia (Mercado Pago, Ualá, FCI eco de Adcap, Adcap y Banco Galicia) que se conservan para no romper datos previos; la sección de billeteras ya no se muestra en la app.

> Las TNA iniciales son **referencias** (`rateSource = "referencia"`). La cuenta de gastos ahora es el **FCI marcado como billetera**, que se edita desde **Cartera → Activo**.

### Fuentes externas

- **DolarAPI** (`https://dolarapi.com/v1/dolares`) para cotizaciones.
- **Comparatasas.ar** con Jsoup para TNA de billeteras. El scraping es *best-effort*: si el sitio cambia, se conservan las tasas manuales.
- **Rava Bursátil** (`https://www.rava.com/perfil/{TICKER}`) para precio actual y tabla de cierres históricos de letras, bonos y CEDEARs (`S30N6`, `AO27D`, `SPY`, etc.).
- **CAFCI** (`https://estadisticas.cafci.org.ar/fondos/{FONDO}?clase={CLASE}`) para valor de cuotaparte y rendimientos históricos de FCI (ej. Adcap Balanceado III `1402/4070`, Mercado Fondo ARS `798/1982`, Mercado Fondo USD `1522/4557`).
- **compararfondos.com.ar** (`https://compararfondos.com.ar/api/fondos` y `/api/fondo/{clase}`) para el catálogo completo y la serie diaria de cuotapartes del evaluador de estabilidad. API pública, sin clave, con atribución obligatoria a compararfondos.com.ar (datos CAFCI/CNV).

---

## Widget

Implementado con **RemoteViews** clásico (no Glance), para máxima compatibilidad:

- Layout: `app/src/main/res/layout/widget_smart_invest.xml`.
- Configuración: `app/src/main/res/xml/widget_info.xml`.
- Provider: `SmartInvestWidgetProvider`.
- Renderer: `WidgetRenderer`.
- Datos: `WidgetDataLoader` (valoración + liquidación del mes).
- Actualización: worker horario, refresco tras cambios y botón manual.

Muestra patrimonio total ARS/USD, interés diario, MEP, cuotas del mes y estado del hogar.

---

## Stack técnico

| Componente | Versión |
| --- | --- |
| Kotlin | 2.0.21 |
| AGP | 8.7.3 |
| Gradle | 8.9 |
| Compose BOM | 2024.12.01 |
| Material 3 | BOM |
| Hilt | 2.52 |
| Room | 2.6.1 (KSP) |
| WorkManager | 2.10.0 |
| Navigation Compose | 2.8.5 |
| DataStore | 1.1.1 |
| Retrofit | 2.11.0 |
| OkHttp | 4.12.0 |
| kotlinx.serialization | 1.7.3 |
| Jsoup | 1.18.3 |
| PDFBox Android | 2.0.27.0 |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| Java / JVM target | 17 |

Características: tema oscuro, edge-to-edge, `allowBackup=false`, sin tráfico cleartext.

---

## Build y ejecución

### Requisitos

- **JDK 17** (`JAVA_HOME` configurado).
- **Android SDK Platform 35** y Build Tools 35.
- `ANDROID_HOME` / `ANDROID_SDK_ROOT`, o un `local.properties` con:

```properties
sdk.dir=C\:\\ruta\\a\\Android\\Sdk
```

El wrapper de Gradle ya está incluido (`gradlew`, `gradlew.bat` y `gradle/wrapper/gradle-wrapper.jar`, verificado contra el SHA-256 oficial de Gradle 8.9).

### Comandos

```bash
# Linux / macOS
./gradlew :app:assembleDebug
./gradlew test
```

```powershell
# Windows
.\gradlew.bat :app:assembleDebug
.\gradlew.bat test
```

Instalación en un dispositivo/emulador conectado:

```bash
./gradlew :app:installDebug
```

> **Verificado:** `:app:testDebugUnitTest` (**130 tests, 0 fallos, 19 suites**) con JDK 17 y Android SDK 35. Los APK de debug (~29,6 MB) y release (~8,6 MB, sin firmar) se generan en `app/build/outputs/apk/`. `local.properties` contiene la ruta al SDK local y está ignorado por git.

### Release

El build `release` tiene `isMinifyEnabled = true` y `isShrinkResources = true`. Las reglas de ProGuard/R8 están en `app/proguard-rules.pro`; conviene revisarlas antes de publicar.

---

## Tests

Tests unitarios JVM (sin emulador):

```bash
./gradlew test
```

| Test | Cubre |
| --- | --- |
| `MoneyParserTest` | parseo de importes en formato argentino |
| `QuantityParserTest` | parseo y formato de cantidades (cuotapartes/nominales) en formato argentino, sin notación científica y con ida y vuelta exacta |
| `InstallmentEngineTest` | reparto de cuotas y ciclo de tarjeta |
| `SettlementCalculatorTest` | liquidación, aportes, extracciones, transferencias y saldado manual total/parcial (saldo pendiente) |
| `PortfolioValuationTest` | valoración ARS/USD, CEDEAR (`SPY`) y bonos (`AO27`/`AO27D`) en exposición USD, MEP, topes, interés, variación diaria de un bono USD y tenencias sin cantidad |
| `PortfolioReconciliationEngineTest` | parseo de listas de saldos (14/11/2026 y 16/11/2026), totales nativos ARS/USD, reconciliación de la planilla 16/11 a MEP 1.500, cantidades del preset 16/11 que reproducen los saldos al cierre, exposición 60/40 con bonos y SPY, precios implícitos, diferencias contra cotización y bloqueo manual |
| `DailyInterestEngineTest` | desglose diario por TNA/tope, variación real de cotizaciones (con y sin cantidad), conciliación, TNA tomada del FCI vinculado y capitalización diaria en billeteras y FCI sin cotización fresca |
| `ReceiptParserTest` | comprobantes de transferencia (Mercado Pago), pago de servicio (Galicia), resumen de tarjeta de crédito (`MASTERCARD BLACK` con vencimiento, próximo cierre y total a pagar), fechas con mes en texto y comprobantes sin datos |
| `MarketClientsTest` | normalización de claves/URLs y parseo HTML de Rava (tabla histórica y encabezado con cierre anterior) y CAFCI |
| `MacroRiskTest` | bandas de carry, techo por riesgo país, velocidad de la brecha, calendario electoral, histéresis, guardarraíl de liquidez, alcance por riesgo subyacente, vencimientos como flujo y reparto orgánico 100% pesos / 100% dólares |
| `PlanningTest` | sueldo, rebalanceo y rescate de emergencia |
| `XlsxReaderTest` | lectura de `.xlsx`: shared strings, inline strings, números y celdas salteadas |
| `SplitHomeParserTest` | hojas Gastos/Fondo, pagadores, cuotas, fechas y totales |
| `ExpenseReportEngineTest` | búsqueda por texto/categoría/medio/integrante, totales y promedios, agrupaciones, top de gastos y exportación CSV con escape |
| `FormattersTest` | enmascarado de montos en modo privacidad (ARS/USD/compactos/firmados) y restauración al desactivarlo |
| `FundStabilityEngineTest` | retorno, volatilidad anualizada, máxima caída, peor/mejor día y ratio de días positivos; recorte por ventana; score y tiers; ranking por estabilidad/rentabilidad/caída/volatilidad; umbral mínimo de estabilidad; filtros; evaluación del catálogo por horizonte (7d/30d/12m), clases sin datos a 90 días y ranking completo por rentabilidad; comparación contra Adcap Balanceado III y simulación con la serie real |
| `FundComparisonClientTest` | parseo del catálogo de compararfondos (nombre, gestora, tipo, moneda, métricas a 30/365 días) y de la serie diaria (`history`), orden, descarte de puntos inválidos y respuesta vacía |
| `FundWatchlistCodecTest` | persistencia JSON del seguimiento, deduplicación, lista vacía explícita y JSON inválido |

---

## Limitaciones y próximos pasos

- El **scraper de Comparatasas** puede romperse si cambia el HTML; las tasas siempre se pueden editar a mano.
- No hay sincronización en la nube ni backup remoto (`allowBackup=false`).
- El MEP de fallback es configurable, pero si DolarAPI no responde el widget y la cartera lo indican como estimado.
- Las TNA y topes iniciales son referencias: hay que ajustarlos a la situación real.
- La importación de comprobantes acepta **PDF con texto** o texto plano. Los PDF escaneados como imagen no tienen OCR: se pueden completar a mano.
- Pendiente: pruebas instrumentadas de Compose/Room en emulador y publicación firmada (el build release actual genera un APK sin firmar).
