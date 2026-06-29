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
fun formatDate(date: LocalDate): String = DATE_FORMAT.format(date)

private fun rateOf(percent: Int?): BigDecimal = BigDecimal((percent ?: 0)).divide(BigDecimal(100), 4, HALF_UP)

fun buildTaxLine(
    name: String,
    taxBase: Double,
    ratePercent: Int,
    reductionPercent: Int,
    isExempt: Boolean = false,
): TaxLine {
    val base = taxBase.money()
    val rate = rateOf(ratePercent)
    val shouldPay = base.multiply(rate).money()
    val reduction = shouldPay.multiply(rateOf(reductionPercent)).money()
    val finalPayable = if (isExempt) BigDecimal.ZERO.money() else shouldPay.subtract(reduction).money()
    return TaxLine(
        name = name,
        taxBase = base.toDouble(),
        ratePercent = ratePercent,
        shouldPay = shouldPay.toDouble(),
        exemptAmount = reduction.toDouble(),
        reductionPercent = reductionPercent,
        reductionAmount = reduction.toDouble(),
        finalPayable = finalPayable.toDouble(),
        isExempt = isExempt,
    )
}

private fun taxLine(
    name: String,
    base: BigDecimal,
    ratePercent: Int?,
    reductionPercent: Int?,
    isExempt: Boolean = false,
): TaxLine {
    val rate = rateOf(ratePercent)
    val shouldPay = base.multiply(rate).money()
    val reduction = rateOf(reductionPercent)
    val reductionAmount = shouldPay.multiply(reduction).money()
    val finalPayable = shouldPay.subtract(reductionAmount).money()
    return TaxLine(
        name = name,
        taxBase = base.toDouble(),
        ratePercent = ratePercent ?: 0,
        shouldPay = shouldPay.toDouble(),
        exemptAmount = reductionAmount.toDouble(),
        reductionPercent = reductionPercent ?: 0,
        reductionAmount = reductionAmount.toDouble(),
        finalPayable = if (isExempt) BigDecimal.ZERO.money().toDouble() else finalPayable.toDouble(),
        isExempt = isExempt,
    )
}

fun invoiceBreakdown(
    invoice: Invoice,
    quarterInvoicesBefore: List<Invoice>,
    taxSettings: TaxSettings,
): InvoiceBreakdown {
    val gross = invoice.grossAmount.toMoneyOrNull() ?: BigDecimal.ZERO
    val rate = rateOf(invoice.invoiceTaxRatePercent)
    val taxable = gross.divide(BigDecimal.ONE.add(rate), 10, HALF_UP).money()
    val vat = gross.subtract(taxable).money()

    val cityRate = invoice.cityTaxRatePercent ?: taxSettings.cityConstructionTaxRatePercent
    val cityReduction = invoice.cityTaxReductionPercent ?: taxSettings.cityConstructionTaxReductionPercent
    val educationRate = invoice.educationFeeRatePercent ?: taxSettings.educationFeeRatePercent
    val educationReduction = invoice.educationFeeReductionPercent ?: taxSettings.educationFeeReductionPercent
    val localRate = invoice.localEducationFeeRatePercent ?: taxSettings.localEducationFeeRatePercent
    val localReduction = invoice.localEducationFeeReductionPercent ?: taxSettings.localEducationFeeReductionPercent

    val taxableBefore = quarterInvoicesBefore.fold(BigDecimal.ZERO) { acc, item ->
        val itemGross = item.grossAmount.toMoneyOrNull() ?: BigDecimal.ZERO
        val itemRate = rateOf(item.invoiceTaxRatePercent)
        val itemTaxable = itemGross.divide(BigDecimal.ONE.add(itemRate), 10, HALF_UP).money()
        if (item.isRedFlush) acc - itemTaxable else acc + itemTaxable
    }.money()

    val thresholdReached = taxableBefore.add(taxable) > BigDecimal(taxSettings.quarterlyEducationThreshold)
    val cityLine = taxLine("城市维护建设税", vat, cityRate, cityReduction, false)
    val educationLine = taxLine("教育费附加", vat, educationRate, if (thresholdReached) 0 else educationReduction, !thresholdReached)
    val localLine = taxLine("地方教育附加", vat, localRate, if (thresholdReached) 0 else localReduction, !thresholdReached)

    val cityFinal = cityLine.finalPayable.money()
    val educationFinal = educationLine.finalPayable.money()
    val localFinal = localLine.finalPayable.money()
    val total = vat + cityFinal + educationFinal + localFinal
    return InvoiceBreakdown(
        taxableAmount = if (invoice.isRedFlush) taxable.negate().toDouble() else taxable.toDouble(),
        vat = if (invoice.isRedFlush) vat.negate().toDouble() else vat.toDouble(),
        cityTax = cityLine.copy(
            shouldPay = if (invoice.isRedFlush) cityLine.shouldPay * -1 else cityLine.shouldPay,
            exemptAmount = if (invoice.isRedFlush) cityLine.exemptAmount * -1 else cityLine.exemptAmount,
            reductionAmount = if (invoice.isRedFlush) cityLine.reductionAmount * -1 else cityLine.reductionAmount,
            finalPayable = if (invoice.isRedFlush) cityFinal.negate().toDouble() else cityFinal.toDouble(),
        ),
        educationFee = educationLine.copy(
            shouldPay = if (invoice.isRedFlush) educationLine.shouldPay * -1 else educationLine.shouldPay,
            exemptAmount = if (invoice.isRedFlush) educationLine.exemptAmount * -1 else educationLine.exemptAmount,
            reductionAmount = if (invoice.isRedFlush) educationLine.reductionAmount * -1 else educationLine.reductionAmount,
            finalPayable = if (invoice.isRedFlush) educationFinal.negate().toDouble() else educationFinal.toDouble(),
        ),
        localEducationFee = localLine.copy(
            shouldPay = if (invoice.isRedFlush) localLine.shouldPay * -1 else localLine.shouldPay,
            exemptAmount = if (invoice.isRedFlush) localLine.exemptAmount * -1 else localLine.exemptAmount,
            reductionAmount = if (invoice.isRedFlush) localLine.reductionAmount * -1 else localLine.reductionAmount,
            finalPayable = if (invoice.isRedFlush) localFinal.negate().toDouble() else localFinal.toDouble(),
        ),
        totalPayable = if (invoice.isRedFlush) total.negate().toDouble() else total.toDouble(),
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
        val sign = if (invoice.isRedFlush) BigDecimal.ONE.negate() else BigDecimal.ONE
        gross += (invoice.grossAmount.toMoneyOrNull() ?: BigDecimal.ZERO).multiply(sign)
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
        val sign = if (invoice.isRedFlush) BigDecimal.ONE.negate() else BigDecimal.ONE
        gross += (invoice.grossAmount.toMoneyOrNull() ?: BigDecimal.ZERO).multiply(sign)
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
