package com.smartinvest.ui.investments

import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.WalletType

/**
 * Real-world wallet templates.
 *
 * They only prefill identity fields (name, institution, type, currency and notes).
 * TNA and balances stay empty on purpose: the rate is either synced from Comparatasas
 * or taken from the linked FCI holding. [linkedAliases] are matched against the
 * symbol/name of the FCI holdings already loaded by the user.
 */
data class WalletPreset(
    val id: String,
    val name: String,
    val institution: String,
    val type: WalletType,
    val currency: CurrencyCode = CurrencyCode.ARS,
    val notes: String = "",
    val linkedAliases: List<String> = emptyList(),
)

val WalletPresets: List<WalletPreset> = listOf(
    WalletPreset(
        id = "mercado_pago",
        name = "Mercado Pago ARS",
        institution = "Mercado Pago",
        type = WalletType.MONEY_MARKET_FUND,
        notes = "Cuenta remunerada en pesos. Se vincula al FCI cargado como Mercado Pago ARS.",
        linkedAliases = listOf("mercado pago ars", "mercado pago", "mercadopago"),
    ),
    WalletPreset(
        id = "uala",
        name = "Ualá",
        institution = "Ualá",
        type = WalletType.WALLET,
        notes = "Saldo en cuenta. Verificá el tope remunerado vigente.",
        linkedAliases = listOf("ualá", "uala"),
    ),
    WalletPreset(
        id = "personal_pay",
        name = "Personal Pay",
        institution = "Personal Pay",
        type = WalletType.WALLET,
        notes = "Billetera virtual. La remuneración puede tener tope por cuenta.",
        linkedAliases = listOf("personal pay", "personalpay"),
    ),
    WalletPreset(
        id = "naranja_x",
        name = "Naranja X",
        institution = "Naranja X",
        type = WalletType.WALLET,
        notes = "Billetera virtual con cuenta remunerada y tope.",
        linkedAliases = listOf("naranja x", "naranjax"),
    ),
    WalletPreset(
        id = "brubank",
        name = "Brubank",
        institution = "Brubank",
        type = WalletType.WALLET,
        notes = "Cuenta remunerada en pesos.",
        linkedAliases = listOf("brubank"),
    ),
    WalletPreset(
        id = "cocos",
        name = "Cocos Capital",
        institution = "Cocos Capital",
        type = WalletType.BROKER,
        notes = "Bróker: el saldo remunerado suele estar en un FCI T+0.",
        linkedAliases = listOf("cocos"),
    ),
    WalletPreset(
        id = "galicia",
        name = "Banco Galicia",
        institution = "Banco Galicia",
        type = WalletType.BANK,
        notes = "Cuenta bancaria. Cargá la TNA solo si la cuenta es remunerada.",
        linkedAliases = listOf("galicia"),
    ),
    WalletPreset(
        id = "cuenta_dni",
        name = "Cuenta DNI",
        institution = "Banco Provincia",
        type = WalletType.BANK,
        notes = "Cuenta gratuita del Banco Provincia.",
        linkedAliases = listOf("cuenta dni", "banco provincia", "provincia"),
    ),
    WalletPreset(
        id = "santander",
        name = "Santander",
        institution = "Santander",
        type = WalletType.BANK,
        notes = "Cuenta bancaria. La TNA aplica solo a saldos remunerados.",
        linkedAliases = listOf("santander"),
    ),
    WalletPreset(
        id = "bbva",
        name = "BBVA",
        institution = "BBVA",
        type = WalletType.BANK,
        notes = "Cuenta bancaria. La TNA aplica solo a saldos remunerados.",
        linkedAliases = listOf("bbva"),
    ),
    WalletPreset(
        id = "banco_nacion",
        name = "Banco Nación",
        institution = "Banco Nación",
        type = WalletType.BANK,
        notes = "Cuenta bancaria. Cargá la TNA si el saldo rinde interés.",
        linkedAliases = listOf("nación", "nacion"),
    ),
    WalletPreset(
        id = "fci_t0",
        name = "FCI T+0",
        institution = "FCI money market",
        type = WalletType.MONEY_MARKET_FUND,
        notes = "Fondo money market de rescate inmediato. Vinculalo para usar su TNA.",
        linkedAliases = listOf("fci t+0", "money market", "t+0"),
    ),
)
