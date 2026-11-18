package com.smartinvest.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcoValoresFundsTest {

    @Test
    fun `matches an exact Clase A name`() {
        assertTrue(EcoValoresFunds.matches("Balanz Capital Money Market - Clase A"))
        assertTrue(EcoValoresFunds.matches("SF Value - Clase A"))
    }

    @Test
    fun `matches ignoring accents case and dash variants`() {
        assertTrue(EcoValoresFunds.matches("megaqm ahorro dolares - clase a"))
        assertTrue(EcoValoresFunds.matches("MEGAQM Ahorro Dólares – Clase A"))
        assertTrue(EcoValoresFunds.matches("  Delta   Renta Dolares — Clase A  "))
    }

    @Test
    fun `matches the catalog name of the Adcap alias`() {
        assertTrue(EcoValoresFunds.matches("Adcap Ahorro Dinámico Fondo de Dinero - Clase A"))
        assertEquals(
            EcoValoresFunds.normalize("Adcap Ahorro Dinámico Fondo de Dinero - Clase A"),
            EcoValoresFunds.normalize("adcap ahorro dinamico fondo de dinero – clase a"),
        )
    }

    @Test
    fun `excludes Clase B and other classes`() {
        assertFalse(EcoValoresFunds.matches("Adcap Acciones - Clase B"))
        assertFalse(EcoValoresFunds.matches("Balanz Capital Money Market - Clase C"))
        assertFalse(EcoValoresFunds.matches("Balanz Capital Money Market"))
    }

    @Test
    fun `excludes Clase A funds the catalog does not publish`() {
        assertTrue(EcoValoresFunds.NOT_PUBLISHED.isNotEmpty())
        EcoValoresFunds.NOT_PUBLISHED.forEach { name ->
            assertFalse(name, EcoValoresFunds.matches(name))
        }
    }

    @Test
    fun `catalog names are unique and within the published universe`() {
        assertEquals(
            EcoValoresFunds.CATALOG_NAMES.size,
            EcoValoresFunds.CATALOG_NAMES.distinct().size,
        )
        assertTrue(
            EcoValoresFunds.CATALOG_NAMES.size + EcoValoresFunds.NOT_PUBLISHED.size <=
                EcoValoresFunds.CLASE_A_TOTAL,
        )
    }
}
