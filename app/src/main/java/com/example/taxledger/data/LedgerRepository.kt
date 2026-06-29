package com.example.taxledger.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime

data class LedgerSnapshot(
    val people: List<Person>,
    val invoices: List<Invoice>,
    val taxSettings: TaxSettings,
    val selectedYear: Int,
    val selectedQuarter: Int,
    val activeTab: AppTab,
)

data class ImportedInvoiceDraft(
    val grossAmount: String,
    val issuedOn: LocalDate,
    val invoiceNumber: String,
    val attachmentName: String,
    val attachmentPath: String,
    val format: AttachmentFormat,
    val note: String = "",
)

class LedgerRepository(private val context: Context) {
    private val db = LedgerDbHelper(context)
    private val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
    private val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
    private val importParser = InvoiceImportParser(context, attachmentsDir)

    fun load(): LedgerSnapshot? {
        val database = db.readableDatabase
        val people = database.query("people", null, null, null, null, null, "display_name ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toPerson()) }
        }
        val invoices = database.query("invoices", null, null, null, null, null, "issued_on ASC, created_at ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toInvoice()) }
        }
        val taxSettings = database.query("settings", null, "id=?", arrayOf("1"), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTaxSettings() else TaxSettings()
        }
        val ui = database.query("ui_state", null, "id=?", arrayOf("1"), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toUiState() else UiStateRecord()
        }
        return LedgerSnapshot(people, invoices, taxSettings, ui.selectedYear, ui.selectedQuarter, ui.activeTab)
    }

    fun save(snapshot: LedgerSnapshot) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.delete("people", null, null)
            database.delete("invoices", null, null)
            database.delete("settings", null, null)
            database.delete("ui_state", null, null)
            snapshot.people.forEach { database.insert("people", null, it.toValues()) }
            snapshot.invoices.forEach { database.insert("invoices", null, it.toValues()) }
            database.insert("settings", null, snapshot.taxSettings.toValues())
            database.insert("ui_state", null, UiStateRecord(snapshot.selectedYear, snapshot.selectedQuarter, snapshot.activeTab).toValues())
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun exportQuarter(year: Int, quarter: Int, bundle: ExportBundle): List<File> {
        val slug = "${year}Q$quarter"
        val csvFile = File(exportsDir, "ledger_$slug.csv")
        val txtFile = File(exportsDir, "ledger_$slug.txt")
        csvFile.writeText(bundle.csv)
        txtFile.writeText(bundle.summaryText)
        return listOf(csvFile, txtFile)
    }

    fun parseImportedInvoice(uri: Uri): ImportedInvoiceDraft = importParser.parseAndPersist(uri)

    fun addPerson(name: String): Person {
        val person = Person(displayName = name.trim())
        val snapshot = load() ?: LedgerSnapshot(emptyList(), emptyList(), TaxSettings(), LocalDate.now().year, ((LocalDate.now().monthValue - 1) / 3) + 1, AppTab.Overview)
        save(snapshot.copy(people = snapshot.people + person))
        return person
    }

    fun updatePerson(person: Person) {
        val snapshot = requireSnapshot()
        save(snapshot.copy(people = snapshot.people.map { if (it.id == person.id) person else it }))
    }

    fun deletePerson(personId: String) {
        val snapshot = requireSnapshot()
        save(snapshot.copy(
            people = snapshot.people.filterNot { it.id == personId },
            invoices = snapshot.invoices.filterNot { it.personId == personId || it.originalInvoiceId == personId },
        ))
    }

    fun updateInvoice(invoice: Invoice) {
        val snapshot = requireSnapshot()
        save(snapshot.copy(invoices = snapshot.invoices.map { if (it.id == invoice.id) invoice else it }))
    }

    fun deleteInvoice(invoiceId: String) {
        val snapshot = requireSnapshot()
        save(snapshot.copy(invoices = snapshot.invoices.filterNot { it.id == invoiceId }))
    }

    fun saveInvoice(invoice: Invoice) {
        val snapshot = requireSnapshot()
        save(snapshot.copy(invoices = snapshot.invoices.filterNot { it.id == invoice.id } + invoice))
    }

    fun createRedFlush(source: Invoice, draft: InvoiceDraft): Invoice {
        val amount = source.grossAmount.toMoneyOrNull() ?: return source.copy(isRedFlush = true, originalInvoiceId = source.id)
        return Invoice(
            id = java.util.UUID.randomUUID().toString(),
            personId = source.personId,
            grossAmount = amount.toPlainString(),
            invoiceTaxRatePercent = source.invoiceTaxRatePercent,
            issuedOn = draft.issuedOn,
            attachmentName = source.attachmentName,
            attachmentPath = source.attachmentPath,
            sourceFormat = source.sourceFormat,
            note = "红冲 ${source.invoiceNumber}",
            invoiceNumber = source.invoiceNumber,
            isRedFlush = true,
            originalInvoiceId = source.id,
            cityTaxRatePercent = source.cityTaxRatePercent ?: draft.cityTaxRatePercent,
            educationFeeRatePercent = source.educationFeeRatePercent ?: draft.educationFeeRatePercent,
            localEducationFeeRatePercent = source.localEducationFeeRatePercent ?: draft.localEducationFeeRatePercent,
            cityTaxReductionPercent = source.cityTaxReductionPercent ?: draft.cityTaxReductionPercent,
            educationFeeReductionPercent = source.educationFeeReductionPercent ?: draft.educationFeeReductionPercent,
            localEducationFeeReductionPercent = source.localEducationFeeReductionPercent ?: draft.localEducationFeeReductionPercent,
        )
    }

    private fun requireSnapshot(): LedgerSnapshot = load() ?: LedgerSnapshot(emptyList(), emptyList(), TaxSettings(), LocalDate.now().year, ((LocalDate.now().monthValue - 1) / 3) + 1, AppTab.Overview)

    private fun Cursor.toPerson(): Person = Person(
        id = getString(getColumnIndexOrThrow("id")),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        isEnabled = getInt(getColumnIndexOrThrow("is_enabled")) == 1,
        defaultInvoiceTaxRatePercent = getInt(getColumnIndexOrThrow("default_rate")),
    )

    private fun Cursor.toInvoice(): Invoice = Invoice(
        id = getString(getColumnIndexOrThrow("id")),
        personId = getString(getColumnIndexOrThrow("person_id")),
        grossAmount = getString(getColumnIndexOrThrow("gross_amount")),
        invoiceTaxRatePercent = getInt(getColumnIndexOrThrow("invoice_rate")),
        issuedOn = LocalDate.parse(getString(getColumnIndexOrThrow("issued_on"))),
        createdAt = LocalDateTime.parse(getString(getColumnIndexOrThrow("created_at"))),
        attachmentName = getStringOrNull("attachment_name"),
        attachmentPath = getStringOrNull("attachment_path"),
        sourceFormat = getStringOrNull("source_format")?.let { runCatching { AttachmentFormat.valueOf(it) }.getOrNull() },
        note = getStringOrNull("note").orEmpty(),
        invoiceNumber = getStringOrNull("invoice_number").orEmpty(),
        isRedFlush = getInt(getColumnIndexOrThrow("is_red_flush")) == 1,
        originalInvoiceId = getStringOrNull("original_invoice_id"),
        cityTaxRatePercent = getIntOrNull("city_tax_rate_percent"),
        educationFeeRatePercent = getIntOrNull("education_fee_rate_percent"),
        localEducationFeeRatePercent = getIntOrNull("local_education_fee_rate_percent"),
        cityTaxReductionPercent = getIntOrNull("city_tax_reduction_percent"),
        educationFeeReductionPercent = getIntOrNull("education_fee_reduction_percent"),
        localEducationFeeReductionPercent = getIntOrNull("local_education_fee_reduction_percent"),
    )

    private fun Cursor.toTaxSettings(): TaxSettings = TaxSettings(
        cityConstructionTaxRatePercent = getInt(getColumnIndexOrThrow("city_tax_rate")),
        cityConstructionTaxReductionPercent = getInt(getColumnIndexOrThrow("city_tax_reduction")),
        educationFeeRatePercent = getInt(getColumnIndexOrThrow("education_rate")),
        educationFeeReductionPercent = getInt(getColumnIndexOrThrow("education_reduction")),
        localEducationFeeRatePercent = getInt(getColumnIndexOrThrow("local_education_rate")),
        localEducationFeeReductionPercent = getInt(getColumnIndexOrThrow("local_education_reduction")),
        quarterlyEducationThreshold = getLong(getColumnIndexOrThrow("quarterly_threshold")),
    )

    private fun Cursor.toUiState(): UiStateRecord = UiStateRecord(
        selectedYear = getInt(getColumnIndexOrThrow("selected_year")),
        selectedQuarter = getInt(getColumnIndexOrThrow("selected_quarter")),
        activeTab = runCatching { AppTab.valueOf(getString(getColumnIndexOrThrow("active_tab"))) }.getOrDefault(AppTab.Overview),
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.getIntOrNull(column: String): Int? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else null
    }

    private fun Person.toValues() = ContentValues().apply {
        put("id", id)
        put("display_name", displayName)
        put("is_enabled", if (isEnabled) 1 else 0)
        put("default_rate", defaultInvoiceTaxRatePercent)
    }

    private fun Invoice.toValues() = ContentValues().apply {
        put("id", id)
        put("person_id", personId)
        put("gross_amount", grossAmount)
        put("invoice_rate", invoiceTaxRatePercent)
        put("issued_on", issuedOn.toString())
        put("created_at", createdAt.toString())
        put("attachment_name", attachmentName)
        put("attachment_path", attachmentPath)
        put("source_format", sourceFormat?.name)
        put("note", note)
        put("invoice_number", invoiceNumber)
        put("is_red_flush", if (isRedFlush) 1 else 0)
        put("original_invoice_id", originalInvoiceId)
        put("city_tax_rate_percent", cityTaxRatePercent)
        put("education_fee_rate_percent", educationFeeRatePercent)
        put("local_education_fee_rate_percent", localEducationFeeRatePercent)
        put("city_tax_reduction_percent", cityTaxReductionPercent)
        put("education_fee_reduction_percent", educationFeeReductionPercent)
        put("local_education_fee_reduction_percent", localEducationFeeReductionPercent)
    }

    private fun TaxSettings.toValues() = ContentValues().apply {
        put("id", 1)
        put("city_tax_rate", cityConstructionTaxRatePercent)
        put("city_tax_reduction", cityConstructionTaxReductionPercent)
        put("education_rate", educationFeeRatePercent)
        put("education_reduction", educationFeeReductionPercent)
        put("local_education_rate", localEducationFeeRatePercent)
        put("local_education_reduction", localEducationFeeReductionPercent)
        put("quarterly_threshold", quarterlyEducationThreshold)
    }

    private fun UiStateRecord.toValues() = ContentValues().apply {
        put("id", 1)
        put("selected_year", selectedYear)
        put("selected_quarter", selectedQuarter)
        put("active_tab", activeTab.name)
    }
}

private data class UiStateRecord(
    val selectedYear: Int = LocalDate.now().year,
    val selectedQuarter: Int = ((LocalDate.now().monthValue - 1) / 3) + 1,
    val activeTab: AppTab = AppTab.Overview,
)

private class LedgerDbHelper(context: Context) : SQLiteOpenHelper(context, "ledger.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE people (id TEXT PRIMARY KEY, display_name TEXT NOT NULL, is_enabled INTEGER NOT NULL, default_rate INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE invoices (id TEXT PRIMARY KEY, person_id TEXT NOT NULL, gross_amount TEXT NOT NULL, invoice_rate INTEGER NOT NULL, issued_on TEXT NOT NULL, created_at TEXT NOT NULL, attachment_name TEXT, attachment_path TEXT, source_format TEXT, note TEXT, invoice_number TEXT, is_red_flush INTEGER NOT NULL DEFAULT 0, original_invoice_id TEXT, city_tax_rate_percent INTEGER, education_fee_rate_percent INTEGER, local_education_fee_rate_percent INTEGER, city_tax_reduction_percent INTEGER, education_fee_reduction_percent INTEGER, local_education_fee_reduction_percent INTEGER)")
        db.execSQL("CREATE TABLE settings (id INTEGER PRIMARY KEY, city_tax_rate INTEGER NOT NULL, city_tax_reduction INTEGER NOT NULL, education_rate INTEGER NOT NULL, education_reduction INTEGER NOT NULL, local_education_rate INTEGER NOT NULL, local_education_reduction INTEGER NOT NULL, quarterly_threshold INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE ui_state (id INTEGER PRIMARY KEY, selected_year INTEGER NOT NULL, selected_quarter INTEGER NOT NULL, active_tab TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE invoices ADD COLUMN is_red_flush INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE invoices ADD COLUMN original_invoice_id TEXT")
            db.execSQL("ALTER TABLE invoices ADD COLUMN city_tax_rate_percent INTEGER")
            db.execSQL("ALTER TABLE invoices ADD COLUMN education_fee_rate_percent INTEGER")
            db.execSQL("ALTER TABLE invoices ADD COLUMN local_education_fee_rate_percent INTEGER")
            db.execSQL("ALTER TABLE invoices ADD COLUMN city_tax_reduction_percent INTEGER")
            db.execSQL("ALTER TABLE invoices ADD COLUMN education_fee_reduction_percent INTEGER")
            db.execSQL("ALTER TABLE invoices ADD COLUMN local_education_fee_reduction_percent INTEGER")
            db.execSQL("ALTER TABLE settings ADD COLUMN city_tax_reduction INTEGER NOT NULL DEFAULT 50")
            db.execSQL("ALTER TABLE settings ADD COLUMN education_rate INTEGER NOT NULL DEFAULT 3")
            db.execSQL("ALTER TABLE settings ADD COLUMN education_reduction INTEGER NOT NULL DEFAULT 100")
            db.execSQL("ALTER TABLE settings ADD COLUMN local_education_rate INTEGER NOT NULL DEFAULT 2")
            db.execSQL("ALTER TABLE settings ADD COLUMN local_education_reduction INTEGER NOT NULL DEFAULT 100")
        }
    }
}
