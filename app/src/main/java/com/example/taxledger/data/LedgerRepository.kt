package com.example.taxledger.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

class LedgerRepository(context: Context) {
    private val file = File(context.filesDir, "ledger_state.json")

    fun load(): LedgerSnapshot? {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        return LedgerSnapshot(
            people = json.optJSONArray("people")?.orEmpty { array, idx -> jsonToPerson(array.getJSONObject(idx)) } ?: emptyList(),
            invoices = json.optJSONArray("invoices")?.orEmpty { array, idx -> jsonToInvoice(array.getJSONObject(idx)) } ?: emptyList(),
            taxSettings = json.optJSONObject("taxSettings")?.let(::jsonToTaxSettings) ?: TaxSettings(),
            selectedYear = json.optInt("selectedYear", LocalDate.now().year),
            selectedQuarter = json.optInt("selectedQuarter", ((LocalDate.now().monthValue - 1) / 3) + 1),
            activeTab = runCatching { AppTab.valueOf(json.optString("activeTab", AppTab.Overview.name)) }.getOrDefault(AppTab.Overview),
        )
    }

    fun save(snapshot: LedgerSnapshot) {
        val json = JSONObject()
            .put("people", JSONArray().apply { snapshot.people.forEach { put(personToJson(it)) } })
            .put("invoices", JSONArray().apply { snapshot.invoices.forEach { put(invoiceToJson(it)) } })
            .put("taxSettings", taxSettingsToJson(snapshot.taxSettings))
            .put("selectedYear", snapshot.selectedYear)
            .put("selectedQuarter", snapshot.selectedQuarter)
            .put("activeTab", snapshot.activeTab.name)
        file.writeText(json.toString(2))
    }

    private fun personToJson(person: Person) = JSONObject()
        .put("id", person.id)
        .put("displayName", person.displayName)
        .put("isEnabled", person.isEnabled)
        .put("defaultInvoiceTaxRatePercent", person.defaultInvoiceTaxRatePercent)

    private fun invoiceToJson(invoice: Invoice) = JSONObject()
        .put("id", invoice.id)
        .put("personId", invoice.personId)
        .put("grossAmount", invoice.grossAmount)
        .put("invoiceTaxRatePercent", invoice.invoiceTaxRatePercent)
        .put("issuedOn", invoice.issuedOn.toString())
        .put("createdAt", invoice.createdAt.toString())
        .put("attachmentName", invoice.attachmentName)
        .put("sourceFormat", invoice.sourceFormat?.name)
        .put("note", invoice.note)

    private fun taxSettingsToJson(settings: TaxSettings) = JSONObject()
        .put("cityConstructionTaxRatePercent", settings.cityConstructionTaxRatePercent)
        .put("quarterlyEducationThreshold", settings.quarterlyEducationThreshold)

    private fun jsonToPerson(json: JSONObject) = Person(
        id = json.optString("id"),
        displayName = json.optString("displayName"),
        isEnabled = json.optBoolean("isEnabled", true),
        defaultInvoiceTaxRatePercent = json.optInt("defaultInvoiceTaxRatePercent", 1),
    )

    private fun jsonToInvoice(json: JSONObject) = Invoice(
        id = json.optString("id"),
        personId = json.optString("personId"),
        grossAmount = json.optString("grossAmount"),
        invoiceTaxRatePercent = json.optInt("invoiceTaxRatePercent", 1),
        issuedOn = LocalDate.parse(json.optString("issuedOn")),
        createdAt = runCatching { LocalDateTime.parse(json.optString("createdAt")) }.getOrElse { LocalDateTime.now() },
        attachmentName = json.optString("attachmentName").takeIf { it.isNotBlank() },
        sourceFormat = json.optString("sourceFormat").takeIf { it.isNotBlank() }?.let { runCatching { AttachmentFormat.valueOf(it) }.getOrNull() },
        note = json.optString("note"),
    )

    private fun jsonToTaxSettings(json: JSONObject) = TaxSettings(
        cityConstructionTaxRatePercent = json.optInt("cityConstructionTaxRatePercent", 5),
        quarterlyEducationThreshold = json.optLong("quarterlyEducationThreshold", 300_000L),
    )

    private fun <T> JSONArray.orEmpty(builder: (JSONArray, Int) -> T): List<T> =
        List(length()) { index -> builder(this, index) }
}
