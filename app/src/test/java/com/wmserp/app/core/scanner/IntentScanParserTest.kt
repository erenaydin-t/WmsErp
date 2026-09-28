package com.wmserp.app.core.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentScanParserTest {

    @Test
    fun `reads zebra datawedge extras`() {
        val parsed = IntentScanParser.parse(mapOf("com.symbol.datawedge.data_string" to "12345", "com.symbol.datawedge.label_type" to "LABEL-TYPE-EAN13"))
        assertEquals("12345", parsed?.value)
        assertEquals("LABEL-TYPE-EAN13", parsed?.symbology)
    }

    @Test
    fun `reads byte array payloads and falls back to barcode-like keys`() {
        assertEquals("ABC", IntentScanParser.parse(mapOf("EXTRA_EVENT_DECODE_VALUE" to "ABC".toByteArray()))?.value)
        assertEquals("XYZ", IntentScanParser.parse(mapOf("vendor_barcode_payload" to " XYZ "))?.value)
        assertNull(IntentScanParser.parse(mapOf("unrelated" to 5, "note" to "")))
    }
}
