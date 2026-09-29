package com.wmserp.app.data.mapper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocTypeMetaTest {

    private val bundle = Json.parseToJsonElement(
        """
        {"docs":[
          {"name":"Purchase Receipt","fields":[
             {"fieldname":"naming_series","label":"Series","fieldtype":"Select","options":"MAT-PRE-.YYYY.-","reqd":1,"default":"MAT-PRE-.YYYY.-"},
             {"fieldname":"posting_date","label":"Date","fieldtype":"Date","reqd":1,"default":"Today"},
             {"fieldname":"items","label":"Items","fieldtype":"Table","options":"Purchase Receipt Item","reqd":1},
             {"fieldname":"cost_center","label":"Cost Center","fieldtype":"Link","options":"Cost Center","reqd":0},
             {"fieldname":"custom_gate_pass","label":"Gate Pass","fieldtype":"Data","reqd":1,"is_custom_field":1}]},
          {"name":"Purchase Receipt Item","fields":[
             {"fieldname":"item_code","label":"Item Code","fieldtype":"Link","options":"Item","reqd":1},
             {"fieldname":"department","label":"Department","fieldtype":"Link","options":"Department","reqd":"1","is_custom_field":1},
             {"fieldname":"priority","label":"Priority","fieldtype":"Select","options":"\nLow\nHigh","reqd":1},
             {"fieldname":"photo","label":"Photo","fieldtype":"Attach Image","reqd":1},
             {"fieldname":"qty","label":"Quantity","fieldtype":"Float","reqd":1}]}
        ],"user_settings":"{}"}
        """.trimIndent()
    ).jsonObject

    @Test
    fun `keeps required answerable fields without a default, across the parent and its child tables`() {
        val fields = DocTypeMeta.requiredFields(bundle)

        assertEquals(
            listOf("Purchase Receipt.custom_gate_pass", "Purchase Receipt Item.item_code", "Purchase Receipt Item.department", "Purchase Receipt Item.priority", "Purchase Receipt Item.qty"),
            fields.map { it.key },
        )
        val department = fields.first { it.fieldname == "department" }
        assertTrue(department.isLink)
        assertEquals("Department", department.options)
        assertEquals("Department", department.label)
        assertEquals(listOf("Low", "High"), fields.first { it.fieldname == "priority" }.selectOptions)
    }

    @Test
    fun `an empty or malformed bundle yields nothing`() {
        assertTrue(DocTypeMeta.requiredFields(Json.parseToJsonElement("""{"docs":[]}""").jsonObject).isEmpty())
        assertTrue(DocTypeMeta.requiredFields(Json.parseToJsonElement("""{"message":"use_cache"}""").jsonObject).isEmpty())
    }
}
