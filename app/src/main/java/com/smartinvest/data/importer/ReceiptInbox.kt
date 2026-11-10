package com.smartinvest.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

data class StagedReceipt(
    val file: File,
    val displayName: String?,
    val mimeType: String?,
)

/**
 * Copies a shared content:// document into the app cache so the import screen can read
 * it without holding a transient permission. The cache entry is safe to delete later.
 */
object ReceiptInbox {

    fun stage(
        context: Context,
        uri: Uri,
        mimeType: String? = null,
        displayName: String? = null,
    ): StagedReceipt? {
        val resolvedName = displayName ?: queryDisplayName(context, uri)
        val extension = extensionFor(mimeType, resolvedName)
        val folder = File(context.cacheDir, "receipts").apply { mkdirs() }
        var target = File(folder, "receipt_${System.currentTimeMillis()}.$extension")
        var counter = 1
        while (target.exists()) {
            target = File(folder, "receipt_${System.currentTimeMillis()}_${counter++}.$extension")
        }
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull() ?: return null
        return StagedReceipt(file = copied, displayName = resolvedName, mimeType = mimeType)
    }

    fun stageText(context: Context, text: String): StagedReceipt? {
        if (text.isBlank()) return null
        val folder = File(context.cacheDir, "receipts").apply { mkdirs() }
        var target = File(folder, "receipt_${System.currentTimeMillis()}.txt")
        var counter = 1
        while (target.exists()) {
            target = File(folder, "receipt_${System.currentTimeMillis()}_${counter++}.txt")
        }
        return runCatching {
            target.writeText(text, Charsets.UTF_8)
            StagedReceipt(file = target, displayName = target.name, mimeType = "text/plain")
        }.getOrNull()
    }

    fun extensionFor(mimeType: String?, displayName: String?): String {
        val name = displayName?.lowercase().orEmpty()
        return when {
            name.endsWith(".pdf") -> "pdf"
            name.endsWith(".txt") -> "txt"
            name.endsWith(".png") -> "png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "jpg"
            name.endsWith(".webp") -> "webp"
            mimeType?.contains("pdf", ignoreCase = true) == true -> "pdf"
            mimeType?.startsWith("image/", ignoreCase = true) == true -> "png"
            else -> "txt"
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}
