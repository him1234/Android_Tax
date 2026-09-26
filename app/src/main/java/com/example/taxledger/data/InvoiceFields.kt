package com.example.taxledger.data

import java.math.BigDecimal
import java.time.LocalDate

/** Fields are suggestions: the entry screen always lets the user verify before saving. */
object InvoiceFields {
    private val number = Regex("(?<!\\d)\\d{20}(?!\\d)")
    private val money = Regex("(?:[¥￥]|人民币)?\\s*([0-9][0-9,]*\\.[0-9]{2})(?!\\d)")
    private val date = Regex("(20\\d{2})\\s*[年./-]\\s*(\\d{1,2})\\s*[月./-]\\s*(\\d{1,2})\\s*日?")

    fun fromText(text: String, qr: String? = null, hint: String = "OCR"): ParsedInvoiceImport {
        val normalized = text.replace('，', ',').replace('￥', '¥')
        val lines = normalized.lines().map(String::trim).filter(String::isNotBlank)
        val invoiceNumber = number.find(qr.orEmpty())?.value
            ?: lines.firstNotNullOfOrNull { line ->
                if (line.contains("发票号码") || line.contains("票据号码")) number.find(line)?.value else null
            }
            ?: number.find(normalized)?.value.orEmpty()
        val totalLine = lines.firstOrNull { it.contains("小写") && (it.contains("价税合计") || it.contains("¥")) }
            ?: lines.firstOrNull { it.contains("价税合计") && money.containsMatchIn(it) }
            ?: lines.firstOrNull { it.contains("小写") && money.containsMatchIn(it) }
        val total = totalLine?.let { money.findAll(it).lastOrNull()?.groupValues?.get(1) }
            ?.replace(",", "")?.let { runCatching { BigDecimal(it).setScale(2).toPlainString() }.getOrNull() }
        val dateText = lines.firstOrNull { it.contains("开票日期") } ?: normalized
        val issuedOn = date.find(dateText)?.destructured?.let { (y, m, d) ->
            runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
        }
        val rate = Regex("(?<!\\d)(1|3)%").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
        return ParsedInvoiceImport(total, issuedOn, invoiceNumber, rate, null, null, null, hint)
    }
}
