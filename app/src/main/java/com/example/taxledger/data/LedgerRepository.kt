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

    fun load(): LedgerSnapshot? {
        val database = db.readableDatabase
        val people = database.query("people", null, null, null, null, null, "display_name ASC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toPerson())
            }
        }
        val invoices = database.query("invoices", null, null, null, null, null, "issued_on ASC, created_at ASC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toInvoice())
            }
        }
        val taxSettings = database.query("settings", null, "id=?", arrayOf("1"), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTaxSettings() else TaxSettings()
        }
        val ui = database.query("ui_state", null, "id=?", arrayOf("1"), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toUiState() else UiStateRecord()
        }
        return LedgerSnapshot(
            people = people,
            invoices = invoices,
            taxSettings = taxSettings,
            selectedYear = ui.selectedYear,
            selectedQuarter = ui.selectedQuarter,
            activeTab = ui.activeTab,
        )
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

    fun persistAttachment(uri: Uri, fileName: String): File {
        val safeName = fileName.ifBlank { "attachment_${System.currentTimeMillis()}" }
        val target = File(attachmentsDir, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: error("Unable to open attachment")
        return target
    }

    fun exportQuarter(year: Int, quarter: Int, bundle: ExportBundle): List<File> {
        val slug = "${year}Q$quarter"
        val csvFile = File(exportsDir, "ledger_$slug.csv")
        val txtFile = File(exportsDir, "ledger_$slug.txt")
        csvFile.writeText(bundle.csv)
        txtFile.writeText(bundle.summaryText)
        return listOf(csvFile, txtFile)
    }

    fun parseImportedInvoice(uri: Uri): ImportedInvoiceDraft {
        val name = queryDisplayName(uri) ?: "invoice_${System.currentTimeMillis()}"
        val format = inferFormat(name, uri)
        val copied = persistAttachment(uri, name)
        val text = runCatching { copied.readText() }.getOrDefault("")
        val gross = extractMoney(text) ?: "0.00"
        val date = extractDate(text) ?: LocalDate.now()
        val invoiceNumber = extractInvoiceNumber(text)
        return ImportedInvoiceDraft(
            grossAmount = gross,
            issuedOn = date,
            invoiceNumber = invoiceNumber,
            attachmentName = name,
            attachmentPath = copied.absolutePath,
            format = format,
            note = when (format) {
                AttachmentFormat.Pdf -> "PDF导入"
                AttachmentFormat.Png, AttachmentFormat.Jpg -> "图片导入"
                AttachmentFormat.Ofd -> "OFD导入"
            },
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }

    private fun inferFormat(name: String, uri: Uri): AttachmentFormat {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pdf") -> AttachmentFormat.Pdf
            lower.endsWith(".png") -> AttachmentFormat.Png
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> AttachmentFormat.Jpg
            lower.endsWith(".ofd") -> AttachmentFormat.Ofd
            uri.toString().lowercase().contains("ofd") -> AttachmentFormat.Ofd
            else -> AttachmentFormat.Pdf
        }
    }

    private fun extractMoney(text: String): String? {
        val regex = Regex("""(?:(?:￥|¥|RMB|金额|合计)[^\d]{0,8})?(\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""")
        return regex.find(text)?.groupValues?.getOrNull(1)?.replace(",", "")
    }

    private fun extractDate(text: String): LocalDate? {
        val patterns = listOf(
            Regex("""(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})"""),
            Regex("""(20\d{2})(\d{2})(\d{2})"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            return runCatching {
                val (y, m, d) = match.destructured
                LocalDate.of(y.toInt(), m.toInt(), d.toInt())
            }.getOrNull()
        }
        return null
    }

    private fun extractInvoiceNumber(text: String): String {
        val regex = Regex("""(?:发票号码|票号|NO\.?|No\.?)[:：\s]*([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.getOrNull(1).orEmpty()
    }

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
    )

    private fun Cursor.toTaxSettings(): TaxSettings = TaxSettings(
        cityConstructionTaxRatePercent = getInt(getColumnIndexOrThrow("city_tax_rate")),
        quarterlyEducationThreshold = getLong(getColumnIndexOrThrow("quarterly_threshold")),
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.toUiState(): UiStateRecord = UiStateRecord(
        selectedYear = getInt(getColumnIndexOrThrow("selected_year")),
        selectedQuarter = getInt(getColumnIndexOrThrow("selected_quarter")),
        activeTab = runCatching { AppTab.valueOf(getString(getColumnIndexOrThrow("active_tab"))) }.getOrDefault(AppTab.Overview),
    )

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
    }

    private fun TaxSettings.toValues() = ContentValues().apply {
        put("id", 1)
        put("city_tax_rate", cityConstructionTaxRatePercent)
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

private class LedgerDbHelper(context: Context) : SQLiteOpenHelper(context, "ledger.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE people (id TEXT PRIMARY KEY, display_name TEXT NOT NULL, is_enabled INTEGER NOT NULL, default_rate INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE invoices (id TEXT PRIMARY KEY, person_id TEXT NOT NULL, gross_amount TEXT NOT NULL, invoice_rate INTEGER NOT NULL, issued_on TEXT NOT NULL, created_at TEXT NOT NULL, attachment_name TEXT, attachment_path TEXT, source_format TEXT, note TEXT, invoice_number TEXT)")
        db.execSQL("CREATE TABLE settings (id INTEGER PRIMARY KEY, city_tax_rate INTEGER NOT NULL, quarterly_threshold INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE ui_state (id INTEGER PRIMARY KEY, selected_year INTEGER NOT NULL, selected_quarter INTEGER NOT NULL, active_tab TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}
