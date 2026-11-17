package com.smartinvest.domain.model

import java.text.Normalizer
import java.util.Locale

/**
 * Clases A que Eco Valores ofrece, según la planilla provista por el usuario el
 * 17/11/2026. El evaluador FCI usa esta lista para mostrar por defecto solo
 * fondos a los que se tiene acceso; la opción «Todo el catálogo» sigue
 * disponible para ampliar la búsqueda.
 *
 * Los nombres son los que publica compararfondos.com.ar (CAFCI/CNV), no siempre
 * idénticos a la planilla: «Adcap Ahorro Dinámico» aparece como «Adcap Ahorro
 * Dinámico Fondo de Dinero» y dos clases A de la planilla todavía no tienen
 * publicación en el catálogo (ver [NOT_PUBLISHED]).
 */
object EcoValoresFunds {

    /** Total de clases A de la planilla, incluidas las que el catálogo no publica. */
    const val CLASE_A_TOTAL: Int = 155

    /** Clases A de la planilla que el catálogo publica con su nombre actual. */
    val CATALOG_NAMES: List<String> = listOf(
        "Adcap Acciones - Clase A",
        "Adcap Ahorro Dinámico Fondo de Dinero - Clase A",
        "Adcap Ahorro Pesos Fondo de Dinero - Clase A",
        "Adcap Balanceado III - Clase A",
        "Adcap Balanceado XVI - Clase A",
        "Adcap Cobertura - Clase A",
        "Adcap Financiamiento Abierto Pymes - Clase A",
        "Adcap Gestión Estratégica III - Clase A",
        "Adcap Moneda - Clase A",
        "Adcap Pesos Plus - Clase A",
        "Alycbur Renta Cobertura - Clase A",
        "Axis Pesos - Clase A",
        "Balanz Acciones - Clase A",
        "Balanz Ahorro en Dólares - Clase A",
        "Balanz Capital Abierto Pymes FCI - Clase A",
        "Balanz Capital Ahorro - Clase A",
        "Balanz Capital Estrategia I USD - Clase A",
        "Balanz Capital Estrategia III USD - Clase A",
        "Balanz Capital Money Market - Clase A",
        "Balanz Capital Renta Fija - Clase A",
        "Balanz Capital Renta Fija en Dólares - Clase A",
        "Balanz Credito Privado - Clase A",
        "Balanz Equity Selection - Clase A",
        "Balanz Excalibur Regisseur - Clase A",
        "Balanz Infraestructura - Clase A",
        "Balanz Institucional - Clase A",
        "Balanz Latam Selection - Clase A",
        "Balanz Lecaps - Clase A",
        "Balanz Long Pesos - Clase A",
        "Balanz Money Market USD - Clase A",
        "Balanz Opportunity - Clase A",
        "Balanz Performance III - Clase A",
        "Balanz Renta Fija Estrategica - Clase A",
        "Balanz Retorno Total - Clase A",
        "Balanz Soja - Clase A",
        "CMA Acciones - Clase A",
        "CMA Estrategia Pesos - Clase A",
        "CMA Performance - Clase A",
        "CMA Proteccion - Clase A",
        "CMA Renta Dólar - Clase A",
        "Compass Ahorro - Clase A",
        "Compass Ahorro en Dolares - Clase A",
        "Compass Argentina Abierto PyMEs - Clase A",
        "Compass Best Ideas - Clase A",
        "Compass Crecimiento - Clase A",
        "Compass Crecimiento II - Clase A",
        "Compass Opportunity - Clase A",
        "Compass Renta Fija - Clase A",
        "Compass Renta Fija III - Clase A",
        "Compass Renta Fija IV - Clase A",
        "Compass Renta Mixta Dólares - Clase A",
        "Compass Renta Mixta II - Clase A",
        "Compass Renta Mixta III - Clase A",
        "Consultatio Abierto Pymes - Clase A",
        "Consultatio Acciones Argentina - Clase A",
        "Consultatio Ahorro Plus Argentina F.C.I. - Clase A",
        "Consultatio Balance Fund - Clase A",
        "Consultatio Deuda Argentina - Clase A",
        "Consultatio Income Fund - Clase A",
        "Consultatio Multiestrategia - Clase A",
        "Consultatio Multimercado III - Clase A",
        "Consultatio Multimercado V - Clase A",
        "Consultatio Renta Fija Argentina F.C.I. - Clase A",
        "Consultatio Renta Local - Clase A",
        "Consultatio Renta Nacional - Clase A",
        "Consultatio Renta Pesos - Clase A",
        "Consultatio Retorno Total - Clase A",
        "Consultatio Rofex 20 - Clase A",
        "Consultatio Zero - Clase A",
        "Delta Acciones - Clase A",
        "Delta Ahorro - Clase A",
        "Delta Ahorro Dolares - Clase A",
        "Delta Ahorro Plus - Clase A",
        "Delta Dólares - Clase A",
        "Delta Empresas Argentinas Pymes - Clase A",
        "Delta Federal I - Clase A",
        "Delta Gestion IX - Clase A",
        "Delta Gestion VI - Clase A",
        "Delta Latinoamerica - Clase A",
        "Delta Moneda - Clase A",
        "Delta Multimercado I - Clase A",
        "Delta Patrimonio I - Clase A",
        "Delta Performance - Clase A",
        "Delta Pesos - Clase A",
        "Delta Recursos Naturales - Clase A",
        "Delta Renta - Clase A",
        "Delta Renta Dolares - Clase A",
        "Delta Renta Dólares Plus - Clase A",
        "Delta Retorno Real - Clase A",
        "Delta Select - Clase A",
        "Delta Sustentable ASG - Clase A",
        "Dracma Balanceado - Clase A",
        "Dracma Estrategia Dólar I - Clase A",
        "Dracma Insider Renta Pymes - Clase A",
        "Dracma Renta Ahorro - Clase A",
        "DXA Multicobertura - Clase A",
        "Ecopymes FCI Abierto Pymes - Clase A",
        "Galileo Acciones - Clase A",
        "Galileo Ahorro - Clase A",
        "Galileo Ahorro Plus - Clase A",
        "Galileo Argentina - Clase A",
        "Galileo Blend - Clase A",
        "Galileo Estrategia - Clase A",
        "Galileo Event Driven - Clase A",
        "Galileo Fixed Income - Clase A",
        "Galileo Income - Clase A",
        "Galileo Multi Strategy - Clase A",
        "Galileo Multi Strategy II - Clase A",
        "Galileo Multimercado II - Clase A",
        "Galileo Multimercado IV - Clase A",
        "Galileo Multimercado VI - Clase A",
        "Galileo Multimercado VII - Clase A",
        "Galileo Multimercado VIII - Clase A",
        "Galileo Pesos - Clase A",
        "Galileo Premium - Clase A",
        "Galileo Renta - Clase A",
        "Galileo Renta Fija - Clase A",
        "IAM Ahorro Pesos - Clase A",
        "IAM Liquidez en Dólares - Clase A",
        "IAM Renta Capital - Clase A",
        "IAM Renta Crecimiento - Clase A",
        "IAM Renta Dólares - Clase A",
        "IAM Renta Mixta - Clase A",
        "IAM Renta Plus - Clase A",
        "IAM Renta Variable - Clase A",
        "MEGAQM Acciones - Clase A",
        "MEGAQM Acciones Latam - Clase A",
        "MEGAQM Ahorro - Clase A",
        "MEGAQM Ahorro Dólares - Clase A",
        "MEGAQM Balanceado - Clase A",
        "MEGAQM Cobertura - Clase A",
        "MEGAQM Corporativo Dólares - Clase A",
        "MEGAQM Latam Corporativo - Clase A",
        "MEGAQM Liquidez Dólar - Clase A",
        "MEGAQM Performance II - Clase A",
        "MEGAQM Pesos - Clase A",
        "MEGAQM Provincial - Clase A",
        "MEGAQM Renta Dólares - Clase A",
        "MEGAQM Renta Fija LATAM - Clase A",
        "MEGAQM Renta Mixta - Clase A",
        "MEGAQM Retorno Absoluto - Clase A",
        "MEGAQM Retorno Total - Clase A",
        "MEGAQM Sustentable ASG - Clase A",
        "SF Value - Clase A",
        "SMR Fondo Comun de Inversion - Clase A",
        "Vinci Compass Liquidez - Clase A",
        "Zofingen Infraestructura - Clase A",
        "Zofingen Renta Fija Dólar - Clase A",
    )

    /** Clases A de la planilla sin publicación en el catálogo al 17/11/2026. */
    val NOT_PUBLISHED: List<String> = listOf(
        "Desarrollo Argentino I FCI Abierto para el Fin. - Clase A",
        "Desarrollo Argentino II FCI Abierto para el Fin. - Clase A",
        "Galileo Multimercado V - Clase A",
        "MEGAQM Cobertura FCIA Pymes - Clase A",
    )

    private val ACCENT_MARKS = Regex("\\p{M}+")
    private val DASHES = Regex("[\\u2010-\\u2015]")
    private val WHITESPACE = Regex("\\s+")
    private val keys: Set<String> = CATALOG_NAMES.map(::normalize).toSet()

    /** True when [name] is one of the Eco Valores Clase A funds. */
    fun matches(name: String): Boolean = normalize(name) in keys

    /** Lowercase, without accents, with a single hyphen and single spaces. */
    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(ACCENT_MARKS, "")
        .lowercase(Locale.ROOT)
        .replace(DASHES, "-")
        .replace(WHITESPACE, " ")
        .trim()
}
