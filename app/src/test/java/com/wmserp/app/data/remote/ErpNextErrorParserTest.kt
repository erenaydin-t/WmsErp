package com.wmserp.app.data.remote

import com.wmserp.app.domain.common.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErpNextErrorParserTest {

    @Test
    fun `maps authentication errors to Unauthorized`() {
        val error = ErpNextErrorParser.parse(401, """{"exc_type":"AuthenticationError","message":"Incorrect password"}""")
        assertTrue(error is AppError.Unauthorized)
        assertEquals("Incorrect password", error.message)
    }

    @Test
    fun `extracts nested server messages and strips html`() {
        val body = """{"exception":"frappe.exceptions.ValidationError: Qty is mandatory","exc_type":"ValidationError",
            "_server_messages":"[\"{\\\"message\\\": \\\"<b>Qty</b> is mandatory for row 1\\\", \\\"title\\\": \\\"Message\\\"}\"]"}"""
        val error = ErpNextErrorParser.parse(417, body)
        assertTrue(error is AppError.Validation)
        assertEquals("Qty is mandatory for row 1", error.message)
    }

    @Test
    fun `falls back to exception text and status defaults`() {
        val fromException = ErpNextErrorParser.parse(500, """{"exception":"pymysql.err.OperationalError: Lost connection"}""")
        assertTrue(fromException is AppError.Server)
        assertEquals("Lost connection", fromException.message)

        val fromStatus = ErpNextErrorParser.parse(404, "not json")
        assertTrue(fromStatus is AppError.NotFound)

        val permission = ErpNextErrorParser.parse(403, """{"exc_type":"PermissionError","message":"Not permitted"}""")
        assertTrue(permission is AppError.Forbidden)
    }

    @Test
    fun `reads exception type helper`() {
        assertEquals("CSRFTokenError", ErpNextErrorParser.exceptionTypeOf("""{"exc_type":"CSRFTokenError"}"""))
        assertEquals(null, ErpNextErrorParser.exceptionTypeOf("oops"))
    }
}
