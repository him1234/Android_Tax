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

fun buildQuarterInvoiceDetails(
    year: Int,
    quarter: Int,
    people: List<Person>,
    invoices: List<Invoice>,
    taxSettings: TaxSettings,
): List<QuarterInvoiceTaxDetail> {
    val peopleById = people.associateBy { it.id }
    val ordered = invoices
        .filter { it.issuedOn.year == year && quarterOf(it.issuedOn) == quarter }
        .sortedWith(compareBy<Invoice> { it.issuedOn }.thenBy { it.createdAt })
    return ordered.mapIndexed { index, invoice ->
        val person = peopleById[invoice.personId]
        QuarterInvoiceTaxDetail(
            personId = invoice.personId,
            personName = person?.displayName ?: "未命名人员",
            invoice = invoice,
            breakdown = invoiceBreakdown(invoice, ordered.take(index), taxSettings),
        )
    }
}

fun buildQuarterPersonSummaries(
    people: List<Person>,
    details: List<QuarterInvoiceTaxDetail>,
): List<QuarterPersonSummary> {
    return people.map { person ->
        val personDetails = details.filter { it.personId == person.id }
        QuarterPersonSummary(
            personId = person.id,
            personName = person.displayName,
            invoiceCount = personDetails.size,
            grossAmount = personDetails.sumOf { detail ->
                val gross = detail.invoice.grossAmount.toMoneyOrNull() ?: BigDecimal.ZERO
                if (detail.invoice.isRedFlush) gross.negate() else gross
            }.money().toDouble(),
            taxableAmount = personDetails.sumOf { BigDecimal.valueOf(it.breakdown.taxableAmount) }.money().toDouble(),
            vat = personDetails.sumOf { BigDecimal.valueOf(it.breakdown.vat) }.money().toDouble(),
            cityTax = personDetails.sumOf { BigDecimal.valueOf(it.breakdown.cityTax.finalPayable) }.money().toDouble(),
            educationFee = personDetails.sumOf { BigDecimal.valueOf(it.breakdown.educationFee.finalPayable) }.money().toDouble(),
            localEducationFee = personDetails.sumOf { BigDecimal.valueOf(it.breakdown.localEducationFee.finalPayable) }.money().toDouble(),
            totalPayable = personDetails.sumOf { BigDecimal.valueOf(it.breakdown.totalPayable) }.money().toDouble(),
        )
    }
}

fun buildQuarterExport(
    year: Int,
    quarter: Int,
    people: List<Person>,
    invoices: List<Invoice>,
    taxSettings: TaxSettings,
): ExportBundle {
    val filtered = invoices.filter { it.issuedOn.year == year && quarterOf(it.issuedOn) == quarter }
    val details = buildQuarterInvoiceDetails(year, quarter, people, invoices, taxSettings)
    val summaries = buildQuarterPersonSummaries(people.filter { it.isEnabled || details.any { detail -> detail.personId == it.id } }, details)
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
        appendLine()
        appendLine("type,person,issued_on,invoice_number,red_flush,gross,tax_rate,taxable,vat,city_should,city_reduction,city_final,education_should,education_reduction,education_final,local_should,local_reduction,local_final,total")
        details.forEach { detail ->
            appendLine(
                listOf(
                    "invoice",
                    detail.personName,
                    formatDate(detail.invoice.issuedOn),
                    detail.invoice.invoiceNumber,
                    if (detail.invoice.isRedFlush) "yes" else "no",
                    (detail.invoice.grossAmount.toMoneyOrNull() ?: BigDecimal.ZERO).let { if (detail.invoice.isRedFlush) it.negate() else it }.money().toPlainString(),
                    "${detail.invoice.invoiceTaxRatePercent}%",
                    detail.breakdown.taxableAmount.money().toPlainString(),
                    detail.breakdown.vat.money().toPlainString(),
                    detail.breakdown.cityTax.shouldPay.money().toPlainString(),
                    detail.breakdown.cityTax.reductionAmount.money().toPlainString(),
                    detail.breakdown.cityTax.finalPayable.money().toPlainString(),
                    detail.breakdown.educationFee.shouldPay.money().toPlainString(),
                    detail.breakdown.educationFee.reductionAmount.money().toPlainString(),
                    detail.breakdown.educationFee.finalPayable.money().toPlainString(),
                    detail.breakdown.localEducationFee.shouldPay.money().toPlainString(),
                    detail.breakdown.localEducationFee.reductionAmount.money().toPlainString(),
                    detail.breakdown.localEducationFee.finalPayable.money().toPlainString(),
                    detail.breakdown.totalPayable.money().toPlainString(),
                ).joinToString(",")
            )
        }
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
    val pdfLines = buildFormalQuarterPdfLines(year, quarter, summaries, details, totals, taxSettings)
    return ExportBundle(csv = csv, summaryText = summaryText, pdfLines = pdfLines)
}

private fun buildFormalQuarterPdfLines(
    year: Int,
    quarter: Int,
    summaries: List<QuarterPersonSummary>,
    details: List<QuarterInvoiceTaxDetail>,
    totals: QuarterTotals,
    taxSettings: TaxSettings,
): List<String> = buildList {
    add("季度税费备案明细表")
    add("所属期间：${quarterLabel(year, quarter)}")
    add("生成日期：${formatDate(LocalDate.now())}")
    add("")
    add("一、计税口径")
    add("1. 本表按单张发票逐张计算，金额保留两位小数并四舍五入。")
    add("2. 不含税销售额 = 含税发票金额 ÷ (1 + 发票税率)。增值税 = 含税金额 - 不含税销售额。")
    add("3. 城市维护建设税税率 ${taxSettings.cityConstructionTaxRatePercent}%，减征比例 ${taxSettings.cityConstructionTaxReductionPercent}%。")
    add("4. 教育费附加税率 ${taxSettings.educationFeeRatePercent}%，减征比例 ${taxSettings.educationFeeReductionPercent}%；地方教育附加税率 ${taxSettings.localEducationFeeRatePercent}%，减征比例 ${taxSettings.localEducationFeeReductionPercent}%。")
    add("5. 教育费附加及地方教育附加按季度累计不含税销售额 ${taxSettings.quarterlyEducationThreshold} 元阈值判断。")
    add("")
    add("二、季度合计")
    add("发票张数：${details.size}")
    add("含税金额合计：${totals.grossAmount.moneyText()} 元")
    add("不含税销售额合计：${totals.taxableAmount.moneyText()} 元")
    add("增值税合计：${totals.vat.moneyText()} 元")
    add("城市维护建设税实缴合计：${totals.cityTax.moneyText()} 元")
    add("教育费附加实缴合计：${totals.educationFee.moneyText()} 元")
    add("地方教育附加实缴合计：${totals.localEducationFee.moneyText()} 元")
    add("本季度应向各人员收取税费合计：${totals.totalPayable.moneyText()} 元")
    add("")
    add("三、按人员汇总")
    summaries.forEachIndexed { index, item ->
        add("${index + 1}. ${item.personName}")
        add("   发票张数：${item.invoiceCount}；含税金额：${item.grossAmount.moneyText()} 元；不含税销售额：${item.taxableAmount.moneyText()} 元；增值税：${item.vat.moneyText()} 元。")
        add("   城建税：${item.cityTax.moneyText()} 元；教育费附加：${item.educationFee.moneyText()} 元；地方教育附加：${item.localEducationFee.moneyText()} 元。")
        add("   应向该人员收取税费合计：${item.totalPayable.moneyText()} 元。")
    }
    add("")
    add("四、逐张发票计税明细")
    summaries.forEach { summary ->
        val personDetails = details.filter { it.personId == summary.personId }
        add("")
        add("【${summary.personName}】发票明细")
        if (personDetails.isEmpty()) {
            add("无本季度发票。")
        }
        personDetails.forEachIndexed { index, detail ->
            val invoice = detail.invoice
            val breakdown = detail.breakdown
            val signedGross = (invoice.grossAmount.toMoneyOrNull() ?: BigDecimal.ZERO).let { if (invoice.isRedFlush) it.negate() else it }
            add("${index + 1}. ${if (invoice.isRedFlush) "红冲发票" else "正常发票"}")
            add("   开票日期：${formatDate(invoice.issuedOn)}；发票号码：${invoice.invoiceNumber.ifBlank { "未填写" }}；发票税率：${invoice.invoiceTaxRatePercent}%。")
            add("   含税金额：${signedGross.money().toPlainString()} 元；不含税销售额：${breakdown.taxableAmount.moneyText()} 元；增值税：${breakdown.vat.moneyText()} 元。")
            addTaxLine("城市维护建设税", breakdown.cityTax)
            addTaxLine("教育费附加", breakdown.educationFee)
            addTaxLine("地方教育附加", breakdown.localEducationFee)
            add("   本张发票应承担税费合计：${breakdown.totalPayable.moneyText()} 元。")
            invoice.note.takeIf { it.isNotBlank() }?.let { add("   备注：$it") }
        }
    }
    add("")
    add("五、备案说明")
    add("本文件由本地账本依据已录入发票和系统税费参数自动生成，用于季度内部对账、收款确认及备案审核。")
}

private fun MutableList<String>.addTaxLine(label: String, line: TaxLine) {
    add("   $label：计税依据 ${line.taxBase.moneyText()} 元，税率 ${line.ratePercent}%，本期应纳 ${line.shouldPay.moneyText()} 元，减免/减征 ${line.reductionAmount.moneyText()} 元，应补（退）${line.finalPayable.moneyText()} 元。")
}

private fun Double.moneyText(): String = money().toPlainString()
