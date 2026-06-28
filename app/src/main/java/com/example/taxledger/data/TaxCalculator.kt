package com.example.taxledger.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val SCALE = 2
private val HALF_UP = RoundingMode.HALF_UP
private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

fun BigDecimal.money(): BigDecimal = setScale(SCALE, HALF_UP)
fun Double.money(): BigDecimal = BigDecimal.valueOf(this).money()

fun String.toMoneyOrNull(): BigDecimal? = runCatching { BigDecimal(trim()) }.getOrNull()?.money()

fun quarterOf(date: LocalDate): Int = ((date.monthValue - 1) / 3) + 1

fun quarterLabel(year: Int, quarter: Int): String = "Q$quarter $year"

fun invoiceBreakdown(
    invoice: Invoice,
    quarterInvoicesBefore: List<Invoice>,
    taxSettings: TaxSettings,
): InvoiceBreakdown {
    val gross = invoice.grossAmount.toMoneyOrNull() ?: BigDecimal.ZERO
    val rate = BigDecimal(invoice.invoiceTaxRatePercent).divide(BigDecimal(100), 4, HALF_UP)
    val taxable = gross.divide(BigDecimal.ONE.add(rate), 10, HALF_UP).money()
    val vat = taxable.multiply(rate).money()

    val cityRate = BigDecimal(taxSettings.cityConstructionTaxRatePercent).divide(BigDecimal(100), 4, HALF_UP)
    val cityShouldPay = vat.multiply(cityRate).money()
    val cityFinal = cityShouldPay.multiply(BigDecimal("0.5")).money()

    val taxableBefore = quarterInvoicesBefore.fold(BigDecimal.ZERO) { acc, item ->
        val itemGross = item.grossAmount.toMoneyOrNull() ?: BigDecimal.ZERO
        val itemRate = BigDecimal(item.invoiceTaxRatePercent).divide(BigDecimal(100), 4, HALF_UP)
        acc + itemGross.divide(BigDecimal.ONE.add(itemRate), 10, HALF_UP).money()
    }.money()

    val educationShouldPay = vat.multiply(BigDecimal("0.03")).money()
    val localShouldPay = vat.multiply(BigDecimal("0.02")).money()
    val threshold = BigDecimal(taxSettings.quarterlyEducationThreshold)
    val thresholdReached = taxableBefore.add(taxable) > threshold

    val educationFinal = if (thresholdReached || taxableBefore > threshold) educationShouldPay else BigDecimal.ZERO.money()
    val localFinal = if (thresholdReached || taxableBefore > threshold) localShouldPay else BigDecimal.ZERO.money()

    return InvoiceBreakdown(
        taxableAmount = taxable.toDouble(),
        vat = vat.toDouble(),
        cityTax = TaxLine(
            name = "城市维护建设税",
            taxBase = vat.toDouble(),
            ratePercent = taxSettings.cityConstructionTaxRatePercent,
            shouldPay = cityShouldPay.toDouble(),
            exemptAmount = cityFinal.toDouble(),
            reductionPercent = 50,
            reductionAmount = cityFinal.toDouble(),
            finalPayable = cityFinal.toDouble(),
        ),
        educationFee = TaxLine(
            name = "教育费附加",
            taxBase = vat.toDouble(),
            ratePercent = 3,
            shouldPay = educationShouldPay.toDouble(),
            exemptAmount = if (educationFinal.compareTo(BigDecimal.ZERO) == 0) educationShouldPay.toDouble() else 0.0,
            reductionPercent = if (educationFinal.compareTo(BigDecimal.ZERO) == 0) 100 else 0,
            reductionAmount = if (educationFinal.compareTo(BigDecimal.ZERO) == 0) educationShouldPay.toDouble() else 0.0,
            finalPayable = educationFinal.toDouble(),
            isExempt = educationFinal.compareTo(BigDecimal.ZERO) == 0,
        ),
        localEducationFee = TaxLine(
            name = "地方教育附加",
            taxBase = vat.toDouble(),
            ratePercent = 2,
            shouldPay = localShouldPay.toDouble(),
            exemptAmount = if (localFinal.compareTo(BigDecimal.ZERO) == 0) localShouldPay.toDouble() else 0.0,
            reductionPercent = if (localFinal.compareTo(BigDecimal.ZERO) == 0) 100 else 0,
            reductionAmount = if (localFinal.compareTo(BigDecimal.ZERO) == 0) localShouldPay.toDouble() else 0.0,
            finalPayable = localFinal.toDouble(),
            isExempt = localFinal.compareTo(BigDecimal.ZERO) == 0,
        ),
        totalPayable = (vat + cityFinal + educationFinal + localFinal).toDouble(),
    )
}

fun buildQuarterTotals(
    invoices: List<Invoice>,
    taxSettings: TaxSettings,
): QuarterTotals {
    val ordered = invoices.sortedWith(compareBy<Invoice> { it.issuedOn }.thenBy { it.createdAt })
    var gross = BigDecimal.ZERO
    var taxable = BigDecimal.ZERO
    var vat = BigDecimal.ZERO
    var city = BigDecimal.ZERO
    var education = BigDecimal.ZERO
    var local = BigDecimal.ZERO
    var total = BigDecimal.ZERO

    ordered.forEachIndexed { index, invoice ->
        val breakdown = invoiceBreakdown(invoice, ordered.take(index), taxSettings)
        gross += invoice.grossAmount.toMoneyOrNull() ?: BigDecimal.ZERO
        taxable += BigDecimal.valueOf(breakdown.taxableAmount)
        vat += BigDecimal.valueOf(breakdown.vat)
        city += BigDecimal.valueOf(breakdown.cityTax.finalPayable)
        education += BigDecimal.valueOf(breakdown.educationFee.finalPayable)
        local += BigDecimal.valueOf(breakdown.localEducationFee.finalPayable)
        total += BigDecimal.valueOf(breakdown.totalPayable)
    }

    return QuarterTotals(
        grossAmount = gross.money().toDouble(),
        taxableAmount = taxable.money().toDouble(),
        vat = vat.money().toDouble(),
        cityTax = city.money().toDouble(),
        educationFee = education.money().toDouble(),
        localEducationFee = local.money().toDouble(),
        totalPayable = total.money().toDouble(),
        taxableThresholdReached = taxable > BigDecimal(taxSettings.quarterlyEducationThreshold),
    )
}

fun buildQuarterPersonSummary(
    person: Person,
    invoices: List<Invoice>,
    taxSettings: TaxSettings,
): QuarterPersonSummary {
    val ordered = invoices.sortedWith(compareBy<Invoice> { it.issuedOn }.thenBy { it.createdAt })
    var gross = BigDecimal.ZERO
    var taxable = BigDecimal.ZERO
    var vat = BigDecimal.ZERO
    var city = BigDecimal.ZERO
    var education = BigDecimal.ZERO
    var local = BigDecimal.ZERO
    var total = BigDecimal.ZERO

    ordered.forEachIndexed { index, invoice ->
        val breakdown = invoiceBreakdown(invoice, ordered.take(index), taxSettings)
        gross += invoice.grossAmount.toMoneyOrNull() ?: BigDecimal.ZERO
        taxable += BigDecimal.valueOf(breakdown.taxableAmount)
        vat += BigDecimal.valueOf(breakdown.vat)
        city += BigDecimal.valueOf(breakdown.cityTax.finalPayable)
        education += BigDecimal.valueOf(breakdown.educationFee.finalPayable)
        local += BigDecimal.valueOf(breakdown.localEducationFee.finalPayable)
        total += BigDecimal.valueOf(breakdown.totalPayable)
    }

    return QuarterPersonSummary(
        personId = person.id,
        personName = person.displayName,
        invoiceCount = ordered.size,
        grossAmount = gross.money().toDouble(),
        taxableAmount = taxable.money().toDouble(),
        vat = vat.money().toDouble(),
        cityTax = city.money().toDouble(),
        educationFee = education.money().toDouble(),
        localEducationFee = local.money().toDouble(),
        totalPayable = total.money().toDouble(),
    )
}

fun formatDate(date: LocalDate): String = DATE_FORMAT.format(date)

fun buildQuarterExport(
    year: Int,
    quarter: Int,
    people: List<Person>,
    invoices: List<Invoice>,
    taxSettings: TaxSettings,
): ExportBundle {
    val filtered = invoices.filter { it.issuedOn.year == year && quarterOf(it.issuedOn) == quarter }
    val summaries = people.filter { it.isEnabled }.map { person ->
        val personInvoices = filtered.filter { it.personId == person.id }
        buildQuarterPersonSummary(person, personInvoices, taxSettings)
    }
    val totals = buildQuarterTotals(filtered, taxSettings)
    val csv = buildString {
        appendLine("type,person,invoice_count,gross,taxable,vat,city_tax,education_fee,local_education_fee,total")
        summaries.forEach { item ->
            appendLine(
                listOf(
                    "person",
                    item.personName,
                    item.invoiceCount,
                    item.grossAmount.money().toPlainString(),
                    item.taxableAmount.money().toPlainString(),
                    item.vat.money().toPlainString(),
                    item.cityTax.money().toPlainString(),
                    item.educationFee.money().toPlainString(),
                    item.localEducationFee.money().toPlainString(),
                    item.totalPayable.money().toPlainString(),
                ).joinToString(",")
            )
        }
        appendLine(
            listOf(
                "quarter_total",
                quarterLabel(year, quarter),
                filtered.size,
                totals.grossAmount.money().toPlainString(),
                totals.taxableAmount.money().toPlainString(),
                totals.vat.money().toPlainString(),
                totals.cityTax.money().toPlainString(),
                totals.educationFee.money().toPlainString(),
                totals.localEducationFee.money().toPlainString(),
                totals.totalPayable.money().toPlainString(),
            ).joinToString(",")
        )
    }
    val summaryText = buildString {
        appendLine("${quarterLabel(year, quarter)} 季度导出")
        appendLine("发票数量：${filtered.size}")
        appendLine("含税总额：${totals.grossAmount.money().toPlainString()}")
        appendLine("不含税总额：${totals.taxableAmount.money().toPlainString()}")
        appendLine("应缴总税费：${totals.totalPayable.money().toPlainString()}")
        summaries.forEach { item ->
            appendLine("${item.personName}：${item.totalPayable.money().toPlainString()}")
        }
    }
    return ExportBundle(csv = csv, summaryText = summaryText)
}
