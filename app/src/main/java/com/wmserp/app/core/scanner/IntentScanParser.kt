package com.wmserp.app.core.scanner

/**
 * Knows the broadcast actions and extras used by common industrial PDA vendors when the scanner
 * is configured for "intent output" instead of keyboard wedge. Pure Kotlin for testability;
 * the Android receiver hands in a map of extras.
 */
object IntentScanParser {

    /** Our own generic action; configure DataWedge / vendor tools to send it. */
    const val ACTION_WMSERP_SCAN = "com.wmserp.app.SCAN"

    val supportedActions: List<String> = listOf(
        ACTION_WMSERP_SCAN,
        "com.symbol.datawedge.api.RESULT_ACTION",           // Zebra DataWedge (default result action)
        "com.zebra.datawedge.SCAN",                          // Zebra DataWedge (common custom action)
        "com.honeywell.decode.intent.action.EDIT_DATA",      // Honeywell
        "android.intent.ACTION_DECODE_DATA",                 // Urovo
        "nlscan.action.SCANNER_RESULT",                      // Newland
        "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED",       // Sunmi
        "com.scanner.broadcast",                             // Chainway
        "com.datalogic.decodewedge.decode_action",           // Datalogic
        "com.android.server.scannerservice.broadcast",       // Seuic / generic
        "unitech.scanservice.data",                          // Unitech
        "com.cipherlab.barcodebaseapi.PASS_DATA_2_APP",      // CipherLab
        "device.scanner.EVENT",                              // Point Mobile
    )

    private val knownDataKeys = listOf(
        "com.symbol.datawedge.data_string",
        "com.motorolasolutions.emdk.datawedge.data_string",
        "com.datalogic.decode.intentwedge.barcode_string",
        "barcode_string",
        "SCAN_BARCODE1",
        "scannerdata",
        "EXTRA_EVENT_DECODE_VALUE",
        "Decoder_Data",
        "barcode",
        "scan_data",
        "data",
        "value",
    )

    private val knownTypeKeys = listOf(
        "com.symbol.datawedge.label_type",
        "com.datalogic.decode.intentwedge.barcode_type",
        "barcodeType",
        "SCAN_BARCODE_TYPE",
        "symbology",
        "type",
    )

    data class Parsed(val value: String, val symbology: String?)

    /** Extracts a barcode from broadcast extras. Returns null when nothing usable is present. */
    fun parse(extras: Map<String, Any?>): Parsed? {
        for (key in knownDataKeys) {
            extras[key]?.let { raw -> asText(raw)?.let { return Parsed(it, symbology(extras)) } }
        }
        // Fallback: any string-ish extra whose key hints at barcode data.
        val fallback = extras.entries.firstOrNull { (k, v) ->
            val key = k.lowercase()
            (key.contains("barcode") || key.contains("data") || key.contains("string")) && asText(v) != null
        }
        return fallback?.let { Parsed(asText(it.value)!!, symbology(extras)) }
    }

    private fun symbology(extras: Map<String, Any?>): String? =
        knownTypeKeys.firstNotNullOfOrNull { key -> (extras[key] as? String)?.takeIf { it.isNotBlank() } }

    private fun asText(value: Any?): String? = when (value) {
        is String -> value.trim().takeIf { it.isNotEmpty() }
        is ByteArray -> String(value, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        is CharSequence -> value.toString().trim().takeIf { it.isNotEmpty() }
        else -> null
    }
}
