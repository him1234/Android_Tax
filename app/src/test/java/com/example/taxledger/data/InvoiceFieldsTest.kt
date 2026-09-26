package com.example.taxledger.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceFieldsTest {
    @Test fun sampleInvoice() {
        val text = """
            电子发票（增值税专用发票）  发票号码：26132000002423334106
            开票日期：2026年07月26日
            合计 ¥4048.51  ¥40.49
            价税合计（大写） 肆仟零捌拾玖圆整  （小写）¥4089.00
        """.trimIndent()
        val fields = InvoiceFields.fromText(text)
        assertEquals("26132000002423334106", fields.invoiceNumber)
        assertEquals("4089.00", fields.grossAmount)
        assertEquals(LocalDate.of(2026, 7, 26), fields.issuedOn)
    }

    @Test fun qrNumberHasPriority() {
        val fields = InvoiceFields.fromText("发票号码：26132000002423334106", "号码26132000001359324691")
        assertEquals("26132000001359324691", fields.invoiceNumber)
    }
}
