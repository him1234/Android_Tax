package com.example.taxledger.data

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class InvoiceImportParser(
    private val context: Context,
    private val attachmentsDir: File,
) {
    suspend fun parseAndPersist(uri: Uri): ImportedInvoiceDraft = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: "invoice_${System.currentTimeMillis()}"
        val format = inferFormat(name, uri)
        val copied = persistAttachment(uri, name)
        val parsed = try { parseFile(copied, format) } catch (error: Exception) {
            copied.delete()
            throw error
        }
        ImportedInvoiceDraft(
            grossAmount = parsed.grossAmount.orEmpty(),
            issuedOn = parsed.issuedOn ?: LocalDate.now(),
            invoiceNumber = parsed.invoiceNumber,
            attachmentName = name,
            attachmentPath = copied.absolutePath,
            format = format,
            note = parsed.sourceHint + if (parsed.grossAmount == null || parsed.invoiceNumber.isBlank()) "（部分字段未识别，请核对）" else "",
            taxRatePercent = parsed.taxRatePercent,
        )
    }

    private suspend fun parseFile(file: File, format: AttachmentFormat): ParsedInvoiceImport {
        val bytes = file.readBytes()
        return when {
            format == AttachmentFormat.Ofd || isZip(bytes) -> parseOfd(bytes)
            format == AttachmentFormat.Xml || looksLikeXml(bytes) -> parseXmlInvoiceSafely(bytes)
            format == AttachmentFormat.Pdf -> InvoiceOcr().use { InvoiceFields.fromText(it.pdf(file), hint = "PDF OCR") }
            format == AttachmentFormat.Png || format == AttachmentFormat.Jpg -> InvoiceOcr().use { InvoiceFields.fromText(it.image(file), hint = "图片 OCR") }
            else -> parseFallback(safeDecode(bytes), "未知格式")
        }
    }

    private fun parseOfd(bytes: ByteArray): ParsedInvoiceImport {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                require(entries.size < 200) { "OFD文件包含过多条目" }
                if (!entry.isDirectory && entry.name.endsWith(".xml", ignoreCase = true)) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= 4_000_000) { "OFD XML文件过大" }
                        output.write(buffer, 0, read)
                    }
                    entries[entry.name] = output.toByteArray()
                }
            }
        }

        val ofdXml = entries["OFD.xml"]?.let(::safeDecode).orEmpty()
        val customTagXml = entries.entries.firstOrNull { it.key.endsWith("CustomTag.xml", ignoreCase = true) }?.value?.let(::safeDecode).orEmpty()
        val contentXml = entries.entries.firstOrNull { it.key.endsWith("Pages/Page_0/Content.xml", ignoreCase = true) }?.value?.let(::safeDecode).orEmpty()
        val taggedValues = parseOfdTaggedValues(customTagXml, contentXml)

        if (ofdXml.isNotBlank()) {
            val invoiceNumber = firstOf(ofdXml, listOf("发票号码", "InvoiceNumber", "invoiceNumber"))
                ?: taggedValues["InvoiceNo"]
            val gross = taggedValues["TaxInclusiveTotalAmount"]
                ?: customData(ofdXml, "价税合计")
                ?: customData(ofdXml, "TaxInclusiveTotalAmount")
            val tax = firstOf(ofdXml, listOf("合计税额", "税额", "TotalTaxAm", "TaxAmount"))
                ?: taggedValues["TaxTotalAmount"]
            val issueDate = parseDate(firstOf(ofdXml, listOf("开票日期", "IssueDate", "IssueTime")) ?: taggedValues["IssueDate"])
            if (invoiceNumber != null || gross != null || issueDate != null) {
                return ParsedInvoiceImport(
                    grossAmount = gross,
                    issuedOn = issueDate,
                    invoiceNumber = invoiceNumber.orEmpty(),
                    taxRatePercent = null,
                    buyerName = firstOf(ofdXml, listOf("购买方名称", "BuyerName", "PurchaserName")) ?: taggedValues["BuyerName"],
                    sellerName = firstOf(ofdXml, listOf("销售方名称", "SellerName")) ?: taggedValues["SellerName"],
                    taxAmount = tax,
                    sourceHint = "OFD结构化",
                )
            }
        }

        entries.values.firstOrNull { safeDecode(it).contains("<EInvoice", ignoreCase = true) }?.let { return parseXmlInvoiceSafely(it) }

        entries.values.forEach { chunk ->
            val text = safeDecode(chunk)
            parseStructuredXmlText(text)?.let { return it }
            if (text.contains("<ofd:TextObject", ignoreCase = true)) {
                return parseOfdText(text)
            }
        }

        return parseFallback(safeDecode(bytes), "OFD兜底")
    }

    private fun parseOfdText(text: String): ParsedInvoiceImport = ParsedInvoiceImport(
        grossAmount = InvoiceFields.fromText(text.replace(Regex("<[^>]*>"), "\n"), hint = "OFD文本层").grossAmount,
        issuedOn = parseDate(firstMatch(text, """20\d{2}年\d{1,2}月\d{1,2}日""")),
        invoiceNumber = firstMatch(text, """\b\d{20}\b""").orEmpty(),
        taxRatePercent = firstMatch(text, """\b(1|3)%\b""")?.toIntOrNull(),
        buyerName = null,
        sellerName = null,
        taxAmount = keywordAmount(text, listOf("合计税额", "税额")),
        sourceHint = "OFD文本层",
    )

    private fun parseXmlInvoice(bytes: ByteArray): ParsedInvoiceImport {
        val xml = safeDecode(bytes)
        val root = parseXml(xml)
        return ParsedInvoiceImport(
            grossAmount = root.textOf("TaxInclusiveTotalAmount")
                ?: root.textOf("TotalAmount")
                ?: root.textOf("AmountWithTax")
                ?: root.textOf("TotalAmWithTax")
                ?: root.textOf("Amount"),
            issuedOn = parseDate(root.textOf("IssueTime") ?: root.textOf("RequestTime") ?: root.textOf("IssueDate")),
            invoiceNumber = root.textOf("InvoiceNumber") ?: root.textOf("EIid").orEmpty(),
            taxRatePercent = parseTaxRate(root.textOf("TaxRate")),
            buyerName = root.textOf("BuyerName") ?: root.textOf("PurchaserName"),
            sellerName = root.textOf("SellerName"),
            taxAmount = root.textOf("TotalTaxAm") ?: root.textOf("ComTaxAm") ?: root.textOf("TaxAmount") ?: root.textOf("Tax"),
            sourceHint = "税局XML",
        )
    }

    private fun parseXmlInvoiceSafely(bytes: ByteArray): ParsedInvoiceImport {
        return runCatching { parseXmlInvoice(bytes) }
            .getOrElse { parseStructuredXmlText(safeDecode(bytes)) ?: parseFallback(safeDecode(bytes), "XML文本兜底") }
    }

    private fun parseFallback(text: String, hint: String): ParsedInvoiceImport = ParsedInvoiceImport(
        grossAmount = keywordAmount(text, listOf("价税合计", "合计金额", "金额", "含税金额")),
        issuedOn = parseDate(firstMatch(text, """20\d{2}年\d{1,2}月\d{1,2}日""") ?: firstMatch(text, """20\d{2}[-/.]\d{1,2}[-/.]\d{1,2}""")),
        invoiceNumber = firstMatch(text, """\b\d{20}\b""").orEmpty(),
        taxRatePercent = firstMatch(text, """\b(1|3)%\b""")?.toIntOrNull(),
        buyerName = null,
        sellerName = null,
        taxAmount = keywordAmount(text, listOf("合计税额", "税额")),
        sourceHint = hint,
    )

    private fun persistAttachment(uri: Uri, fileName: String): File {
        val safeName = sanitizeFileName(fileName.ifBlank { "attachment_${System.currentTimeMillis()}" })
        val target = uniqueFile(File(attachmentsDir, safeName))
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var size = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        require(size <= 30L * 1024 * 1024) { "附件大于30 MB" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("无法读取附件")
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        return target
    }

    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var index = 1
        while (true) {
            val candidate = File(file.parentFile, "${base}_$index$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun inferFormat(name: String, uri: Uri): AttachmentFormat {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".xml") -> AttachmentFormat.Xml
            lower.endsWith(".pdf") -> AttachmentFormat.Pdf
            lower.endsWith(".png") -> AttachmentFormat.Png
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> AttachmentFormat.Jpg
            lower.endsWith(".ofd") -> AttachmentFormat.Ofd
            uri.toString().lowercase(Locale.ROOT).contains("ofd") -> AttachmentFormat.Ofd
            else -> AttachmentFormat.Pdf
        }
    }

    private fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
    private fun looksLikeXml(bytes: ByteArray): Boolean = safeDecode(bytes).trimStart().startsWith("<")

    private fun safeDecode(bytes: ByteArray): String {
        val utf8 = runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault("")
        return if (utf8.count { it == '\uFFFD' } < utf8.length / 8) utf8 else runCatching { bytes.toString(Charsets.UTF_16LE) }.getOrDefault(utf8)
    }

    private fun parseXml(xml: String): XmlRoot {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(InputSource(StringReader(xml)))
        return XmlRoot(doc.documentElement)
    }

    private fun tag(xml: String, name: String): String? {
        val regex = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun customData(xml: String, name: String): String? {
        return Regex("""<[^:>]*:?CustomData\s+Name=["']${Regex.escape(name)}["'][^>]*>(.*?)</[^:>]*:?CustomData>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun firstOf(xml: String, names: List<String>): String? {
        names.forEach { name ->
            tag(xml, name)?.let { return it }
            Regex("""<$name[^>]*>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun parseStructuredXmlText(text: String): ParsedInvoiceImport? {
        if (!text.contains("<", ignoreCase = true)) return null
        val invoiceNumber = firstOf(text, listOf("发票号码", "InvoiceNumber", "invoiceNumber"))
        val gross = firstOf(text, listOf("价税合计", "TaxInclusiveTotalAmount", "TotalAmount", "AmountWithTax", "TotalAmWithTax", "Amount"))
        val issueDate = parseDate(firstOf(text, listOf("开票日期", "IssueDate", "IssueTime", "RequestTime")))
        val tax = firstOf(text, listOf("合计税额", "税额", "TotalTaxAm", "TaxAmount", "Tax"))
        val taxRate = parseTaxRate(firstOf(text, listOf("税率", "TaxRate")))
        if (invoiceNumber == null && gross == null && issueDate == null && tax == null && taxRate == null) return null
        return ParsedInvoiceImport(
            grossAmount = gross,
            issuedOn = issueDate,
            invoiceNumber = invoiceNumber.orEmpty(),
            taxRatePercent = taxRate,
            buyerName = firstOf(text, listOf("购买方名称", "BuyerName", "PurchaserName")),
            sellerName = firstOf(text, listOf("销售方名称", "SellerName")),
            taxAmount = tax,
            sourceHint = "结构化XML",
        )
    }

    private fun parseTaxRate(value: String?): Int? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        val numeric = raw.trimEnd('%').toDoubleOrNull() ?: return null
        return when {
            raw.contains('%') -> numeric.toInt()
            numeric <= 1.0 -> (numeric * 100).toInt()
            numeric <= 100.0 -> numeric.toInt()
            else -> null
        }
    }

    private fun parseDate(value: String?): LocalDate? {
        if (value.isNullOrBlank()) return null
        val normalized = value.replace("年", "-").replace("月", "-").replace("日", "").replace("/", "-").replace(".", "-")
        val parts = Regex("""(20\d{2})-(\d{1,2})-(\d{1,2})""").find(normalized)?.destructured ?: return null
        return runCatching { LocalDate.of(parts.component1().toInt(), parts.component2().toInt(), parts.component3().toInt()) }.getOrNull()
    }

    private fun firstMatch(text: String, pattern: String): String? = Regex(pattern).find(text)?.value

    private fun keywordAmount(text: String, keywords: List<String>): String? {
        for (keyword in keywords) {
            val match = Regex("""$keyword.{0,40}?(\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""").find(text)
            if (match != null) return match.groupValues[1].replace(",", "")
        }
        return null
    }

    private fun parseOfdTaggedValues(customTagXml: String, contentXml: String): Map<String, String> {
        if (customTagXml.isBlank() || contentXml.isBlank()) return emptyMap()
        val textById = Regex("""<ofd:TextObject\s+ID=["']([^"']+)["'][\s\S]*?<ofd:TextCode[^>]*>([\s\S]*?)</ofd:TextCode>""")
            .findAll(contentXml)
            .associate { match ->
                match.groupValues[1] to match.groupValues[2].trim()
            }
        val result = mutableMapOf<String, String>()
        Regex("""<ofd:([A-Za-z0-9]+)>[\s\S]*?</ofd:\1>""")
            .findAll(customTagXml)
            .forEach { tagMatch ->
                val tagName = tagMatch.groupValues[1]
                val value = Regex("""<ofd:ObjectRef[^>]*>([^<]+)</ofd:ObjectRef>""")
                    .findAll(tagMatch.value)
                    .mapNotNull { ref -> textById[ref.groupValues[1]] }
                    .filter { it != "¥" }
                    .joinToString("")
                    .trim()
                if (value.isNotBlank()) result[tagName] = value
            }
        return result
    }

    private fun sanitizeFileName(name: String): String = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
}

private class XmlRoot(private val element: org.w3c.dom.Element) {
    fun textOf(tagName: String): String? {
        val nodes = element.getElementsByTagName(tagName)
        if (nodes.length == 0) {
            val localNodes = element.getElementsByTagNameNS("*", tagName)
            if (localNodes.length == 0) return null
            return localNodes.item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
        }
        return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
    }
}
