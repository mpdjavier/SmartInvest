package com.smartinvest.data.importer

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** Extracts plain text from a staged receipt (PDF or text file). */
object ReceiptTextExtractor {

    fun extract(context: Context, file: File): String {
        if (!file.exists()) return ""
        val lowerName = file.name.lowercase()
        return when {
            lowerName.endsWith(".pdf") -> runCatching { extractPdf(context, file) }.getOrElse { "" }
            lowerName.endsWith(".txt") -> runCatching { file.readText(Charsets.UTF_8) }.getOrElse { "" }
            // Images need OCR, which is not bundled: the review screen asks for manual input.
            else -> ""
        }
    }

    private fun extractPdf(context: Context, file: File): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file).use { document ->
            return PDFTextStripper().getText(document)
        }
    }
}
