package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.model.WmsQrKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrLabelParserTest {

    private fun valid(raw: String, keys: WmsQrKeys = WmsQrKeys.DEFAULT) = (QrLabelParser.parse(raw, keys) as QrParseResult.Valid).label
    private fun invalid(raw: String, keys: WmsQrKeys = WmsQrKeys.DEFAULT) = (QrLabelParser.parse(raw, keys) as QrParseResult.Invalid).error

    @Test
    fun `parses labels printed by ERPNext`() {
        val label = valid("""{"item_code":"ITEM-001","batch_no":"B-2026-01"}""")
        assertEquals("ITEM-001", label.itemCode)
        assertEquals("B-2026-01", label.batchNo)
    }

    @Test
    fun `tolerates scanner noise, whitespace, numbers and missing batch`() {
        assertEquals("B-1", valid(" {\"item_code\": \" ITEM-001 \", \"batch_no\": \"B-1\"}\r\n").batchNo)
        assertEquals("ITEM-001", valid("\u001D{\"item_code\":\"ITEM-001\"}\n").itemCode)
        assertNull(valid("""{"item_code":"ITEM-001"}""").batchNo)
        assertNull(valid("""{"item_code":"ITEM-001","batch_no":null}""").batchNo)
        assertNull(valid("""{"item_code":"ITEM-001","batch_no":""}""").batchNo)
        assertEquals("12", valid("""{"item_code":"ITEM-001","batch_no":12}""").batchNo)
    }

    @Test
    fun `uses the keys configured in WMS Settings`() {
        val keys = WmsQrKeys(itemKey = "sku", batchKey = "lot")
        val label = valid("""{"sku":"ITEM-001","lot":"L-9","item_code":"ignored"}""", keys)
        assertEquals("ITEM-001", label.itemCode)
        assertEquals("L-9", label.batchNo)
        assertEquals(QrError.MISSING_ITEM, invalid("""{"item_code":"ITEM-001"}""", keys))
    }

    @Test
    fun `rejects everything that is not a JSON label`() {
        assertEquals(QrError.EMPTY, invalid("   "))
        assertEquals(QrError.NOT_JSON, invalid("8690000000017"))
        assertEquals(QrError.NOT_JSON, invalid("ITEM-001"))
        assertEquals(QrError.NOT_JSON, invalid("{not json"))
        assertEquals(QrError.NOT_JSON, invalid("[\"item_code\"]"))
        assertEquals(QrError.MISSING_ITEM, invalid("""{"batch_no":"B-1"}"""))
        assertEquals(QrError.MISSING_ITEM, invalid("""{"item_code":""}"""))
        assertEquals(QrError.MISSING_ITEM, invalid("""{"item_code":{"nested":1}}"""))
    }
}
