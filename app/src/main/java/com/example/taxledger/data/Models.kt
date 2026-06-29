package com.example.taxledger.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class Person(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val isEnabled: Boolean = true,
    val defaultInvoiceTaxRatePercent: Int = 1,
)

data class Invoice(
    val id: String = UUID.randomUUID().toString(),
    val personId: String,
    val grossAmount: String,
    val invoiceTaxRatePercent: Int,
    val issuedOn: LocalDate,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val attachmentName: String? = null,
    val attachmentPath: String? = null,
    val sourceFormat: AttachmentFormat? = null,
    val note: String = "",
    val invoiceNumber: String = "",
    val isRedFlush: Boolean = false,
    val originalInvoiceId: String? = null,
    val cityTaxRatePercent: Int? = null,
    val educationFeeRatePercent: Int? = null,
    val localEducationFeeRatePercent: Int? = null,
    val cityTaxReductionPercent: Int? = null,
    val educationFeeReductionPercent: Int? = null,
    val localEducationFeeReductionPercent: Int? = null,
)

enum class AttachmentFormat(val label: String) {
    Pdf("PDF"),
    Png("PNG"),
    Jpg("JPG"),
    Ofd("OFD"),
    Xml("XML"),
}

data class InvoiceDraft(
    val personId: String = "",
    val grossAmount: String = "",
    val invoiceTaxRatePercent: Int = 1,
    val issuedOn: LocalDate = LocalDate.now(),
    val attachmentName: String? = null,
    val attachmentPath: String? = null,
    val sourceFormat: AttachmentFormat? = null,
    val note: String = "",
    val invoiceNumber: String = "",
    val isRedFlush: Boolean = false,
    val originalInvoiceId: String? = null,
    val cityTaxRatePercent: Int? = null,
    val educationFeeRatePercent: Int? = null,
    val localEducationFeeRatePercent: Int? = null,
    val cityTaxReductionPercent: Int? = null,
    val educationFeeReductionPercent: Int? = null,
    val localEducationFeeReductionPercent: Int? = null,
)

data class TaxSettings(
    val cityConstructionTaxRatePercent: Int = 5,
    val cityConstructionTaxReductionPercent: Int = 50,
    val educationFeeRatePercent: Int = 3,
    val educationFeeReductionPercent: Int = 100,
    val localEducationFeeRatePercent: Int = 2,
    val localEducationFeeReductionPercent: Int = 100,
    val quarterlyEducationThreshold: Long = 300_000L,
)

enum class AppTab(val title: String) {
    Overview("概览"),
    Entry("录入"),
    Quarter("季度"),
    People("人员"),
    Settings("设置"),
}

data class LedgerUiState(
    val people: List<Person> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val draft: InvoiceDraft = InvoiceDraft(),
    val activeTab: AppTab = AppTab.Overview,
    val selectedYear: Int = LocalDate.now().year,
    val selectedQuarter: Int = ((LocalDate.now().monthValue - 1) / 3) + 1,
    val taxSettings: TaxSettings = TaxSettings(),
    val pendingPersonName: String = "",
    val showAddPersonDialog: Boolean = false,
    val editingPersonId: String? = null,
    val editingInvoiceId: String? = null,
    val statusMessage: String? = null,
)

data class TaxLine(
    val name: String,
    val taxBase: Double,
    val ratePercent: Int,
    val shouldPay: Double,
    val exemptAmount: Double,
    val reductionPercent: Int,
    val reductionAmount: Double,
    val finalPayable: Double,
    val isExempt: Boolean = false,
)

data class InvoiceBreakdown(
    val taxableAmount: Double,
    val vat: Double,
    val cityTax: TaxLine,
    val educationFee: TaxLine,
    val localEducationFee: TaxLine,
    val totalPayable: Double,
)

data class QuarterPersonSummary(
    val personId: String,
    val personName: String,
    val invoiceCount: Int,
    val grossAmount: Double,
    val taxableAmount: Double,
    val vat: Double,
    val cityTax: Double,
    val educationFee: Double,
    val localEducationFee: Double,
    val totalPayable: Double,
)

data class QuarterInvoiceTaxDetail(
    val personId: String,
    val personName: String,
    val invoice: Invoice,
    val breakdown: InvoiceBreakdown,
)

data class QuarterTotals(
    val grossAmount: Double,
    val taxableAmount: Double,
    val vat: Double,
    val cityTax: Double,
    val educationFee: Double,
    val localEducationFee: Double,
    val totalPayable: Double,
    val taxableThresholdReached: Boolean,
)

data class ExportBundle(
    val csv: String,
    val summaryText: String,
    val pdfLines: List<String>,
)

data class ParsedInvoiceImport(
    val grossAmount: String?,
    val issuedOn: LocalDate?,
    val invoiceNumber: String,
    val taxRatePercent: Int?,
    val buyerName: String?,
    val sellerName: String?,
    val taxAmount: String?,
    val sourceHint: String,
)

fun defaultState(seedPeople: List<Person>): LedgerUiState {
    val first = seedPeople.firstOrNull()
    return LedgerUiState(
        people = seedPeople,
        draft = InvoiceDraft(
            personId = first?.id.orEmpty(),
            invoiceTaxRatePercent = first?.defaultInvoiceTaxRatePercent ?: 1,
            issuedOn = LocalDate.now(),
        ),
    )
}

fun Invoice.toDraft(): InvoiceDraft = InvoiceDraft(
    personId = personId,
    grossAmount = grossAmount,
    invoiceTaxRatePercent = invoiceTaxRatePercent,
    issuedOn = issuedOn,
    attachmentName = attachmentName,
    attachmentPath = attachmentPath,
    sourceFormat = sourceFormat,
    note = note,
    invoiceNumber = invoiceNumber,
    isRedFlush = isRedFlush,
    originalInvoiceId = originalInvoiceId,
    cityTaxRatePercent = cityTaxRatePercent,
    educationFeeRatePercent = educationFeeRatePercent,
    localEducationFeeRatePercent = localEducationFeeRatePercent,
    cityTaxReductionPercent = cityTaxReductionPercent,
    educationFeeReductionPercent = educationFeeReductionPercent,
    localEducationFeeReductionPercent = localEducationFeeReductionPercent,
)
