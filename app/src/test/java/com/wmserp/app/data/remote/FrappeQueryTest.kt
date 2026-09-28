package com.wmserp.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrappeQueryTest {

    @Test
    fun `renders fields and filters as json arrays`() {
        assertEquals("""["name","item_name"]""", FrappeQuery.fields("name", "item_name"))
        assertEquals(
            """[["docstatus","=",1],["status","in",["To Receive","To Bill"]],["Item Barcode","barcode","=","123"]]""",
            FrappeQuery.filters(
                Filter.eq("docstatus", 1),
                Filter.inList("status", listOf("To Receive", "To Bill")),
                Filter("barcode", "=", "123", doctype = "Item Barcode"),
            ),
        )
    }

    @Test
    fun `empty filters render as null and booleans become ints`() {
        assertNull(FrappeQuery.filters(emptyList()))
        assertEquals("""[["disabled","=",0]]""", FrappeQuery.filters(Filter.eq("disabled", false)))
        assertEquals("""[["item_name","like","%bolt%"]]""", FrappeQuery.filters(Filter.like("item_name", "bolt")))
    }
}
