package com.smartinvest.data.backup

/**
 * Collapses duplicated holdings inside an imported backup.
 *
 * A restore used to reinsert every holding with the id exported by another
 * installation. When the new installation had already seeded its preset
 * holdings, those ids did not collide and the same asset ended up twice. The
 * restore now replaces the tables, and this filter also protects against a JSON
 * that was exported after a duplicate was already created: rows with the same
 * symbol, account tag, currency and asset type count as one asset, and the most
 * recent entry (highest id) wins.
 */
object BackupHoldingDedupe {

    data class Result(
        val holdings: List<BackupHoldingDto>,
        val removed: Int,
    )

    fun apply(holdings: List<BackupHoldingDto>): Result {
        if (holdings.size < 2) return Result(holdings, 0)
        val kept = LinkedHashMap<String, BackupHoldingDto>(holdings.size)
        for (holding in holdings.sortedBy { it.id }) {
            kept[identity(holding)] = holding
        }
        return Result(kept.values.toList(), holdings.size - kept.size)
    }

    /** Natural identity of a holding: the same asset in the same account is one row. */
    fun identity(holding: BackupHoldingDto): String = listOf(
        holding.symbol.trim().uppercase(),
        accountTag(holding),
        holding.currency.trim().uppercase(),
        holding.assetType.trim().uppercase(),
    ).joinToString("|")

    /**
     * Account tag stored in the name or in the notes (`SPY (IEB)`, `S30N6 (GALICIA)`).
     * Empty when the holding has no account marker.
     */
    fun accountTag(holding: BackupHoldingDto): String {
        val text = "${holding.name} ${holding.notes}"
        return when {
            text.contains("IEB", ignoreCase = true) -> "IEB"
            text.contains("GALICIA", ignoreCase = true) -> "GALICIA"
            else -> ""
        }
    }
}
