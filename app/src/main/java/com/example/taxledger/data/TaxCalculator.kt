package com.example.taxledger.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

private val TWO_DP = 2
private val HALF_UP = RoundingMode.HALF_UP
fun BigDecimal.money(): BigDecimal = setScale(TWO_DP, HALF_UP)

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
            exemptAmount = cityShouldPay.multiply(BigDecimal("0.5")).toDouble(),
            reductionPercent = 50,
            reductionAmount = cityShouldPay.multiply(BigDecimal("0.5")).toDouble(),
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
