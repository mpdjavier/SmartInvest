package com.smartinvest.data.importer

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class XlsxSheet(
    val name: String,
    val rows: List<List<String>>,
)

/**
 * Minimal, dependency-free reader for the subset of XLSX used by reports such
 * as SplitHome: shared strings, inline strings, numbers, sparse cells and
 * sheet relationships. Dates are kept as raw text/numbers and interpreted by
 * [com.smartinvest.domain.importer.SplitHomeParser].
 */
object XlsxReader {

    private const val MAX_ENTRY_BYTES = 20 * 1024 * 1024

    fun read(bytes: ByteArray): List<XlsxSheet> {
        val entries = readEntries(bytes)
        val workbookBytes = entries["xl/workbook.xml"]
            ?: error("El archivo no parece un .xlsx válido (falta workbook.xml)")
        val relationships = entries["xl/_rels/workbook.xml.rels"]
            ?.let(::parseRelationships)
            .orEmpty()
        val sharedStrings = entries["xl/sharedStrings.xml"]
            ?.let(::parseSharedStrings)
            .orEmpty()

        return parseWorkbook(workbookBytes).map { (name, relationshipId) ->
            val target = relationships[relationshipId].orEmpty()
            val path = resolvePath(target)
            val rows = entries[path]?.let { parseSheet(it, sharedStrings) }.orEmpty()
            XlsxSheet(name, rows)
        }
    }

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val content = zip.readBytes()
                    if (content.size > MAX_ENTRY_BYTES) {
                        error("El archivo es demasiado grande para importarlo")
                    }
                    result[entry.name] = content
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private fun resolvePath(target: String): String {
        val clean = target.replace('\\', '/')
        return when {
            clean.startsWith("/") -> clean.removePrefix("/")
            clean.startsWith("xl/") -> clean
            else -> "xl/$clean"
        }
    }

    private fun parseXml(xml: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }

    /** Disabled namespace processing keeps names such as "r:id" and "sheet" simple. */
    private fun localName(tag: String): String = tag.substringAfter(':')

    private fun Element.children(name: String): List<Element> {
        val nodes = childNodes
        val result = ArrayList<Element>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && localName(node.tagName) == name) result += node
        }
        return result
    }

    private fun Document.elements(name: String): List<Element> {
        val nodes = getElementsByTagName(name)
        val result = ArrayList<Element>(nodes.length)
        for (index in 0 until nodes.length) {
            (nodes.item(index) as? Element)?.let(result::add)
        }
        return result
    }

    private fun parseRelationships(xml: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        parseXml(xml).getElementsByTagName("Relationship").let { nodes ->
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                val id = element.getAttribute("Id")
                val target = element.getAttribute("Target")
                if (id.isNotBlank() && target.isNotBlank()) result[id] = target
            }
        }
        return result
    }

    private fun parseWorkbook(xml: ByteArray): List<Pair<String, String>> {
        val document = parseXml(xml)
        val sheets = document.getElementsByTagName("sheet")
        return buildList {
            for (index in 0 until sheets.length) {
                val element = sheets.item(index) as? Element ?: continue
                val name = element.getAttribute("name").ifBlank { "Hoja ${index + 1}" }
                // With namespace processing disabled the relationship id keeps
                // its "r:" prefix; some writers use a bare "id".
                val id = element.getAttribute("r:id").ifBlank { element.getAttribute("id") }
                add(name to id)
            }
        }
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> =
        parseXml(xml).getElementsByTagName("si").let { nodes ->
            buildList {
                for (index in 0 until nodes.length) {
                    val element = nodes.item(index) as? Element ?: continue
                    add(element.textContent.orEmpty())
                }
            }
        }

    private fun parseSheet(xml: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val document = parseXml(xml)
        val rowNodes = document.getElementsByTagName("row")

        for (rowIndex in 0 until rowNodes.length) {
            val row = rowNodes.item(rowIndex) as? Element ?: continue
            val cells = sortedMapOf<Int, String>()
            row.children("c").forEach { cell ->
                val reference = cell.getAttribute("r")
                val column = if (reference.isNotBlank()) columnIndex(reference) else cells.size
                val type = cell.getAttribute("t")
                val raw = cell.children("v").firstOrNull()?.textContent.orEmpty()
                val value = when (type) {
                    "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
                    "inlineStr" -> cell.children("is").firstOrNull()?.textContent.orEmpty()
                    else -> raw
                }
                cells[column] = value
            }
            val width = (cells.keys.maxOrNull() ?: -1) + 1
            rows += if (width > 0) List(width) { index -> cells[index].orEmpty() } else emptyList()
        }
        return rows
    }

    /** "A" -> 0, "Z" -> 25, "AA" -> 26. */
    private fun columnIndex(reference: String): Int {
        var index = 0
        for (char in reference) {
            if (char.isLetter()) {
                index = index * 26 + (char.uppercaseChar() - 'A' + 1)
            } else {
                break
            }
        }
        return index - 1
    }
}
