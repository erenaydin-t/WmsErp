package com.wmserp.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** `/api/resource/{doctype}` list responses: `{"data": [...]}`. */
@Serializable
data class FrappeListResponse(val data: List<JsonObject> = emptyList())

/** `/api/resource/{doctype}/{name}` document responses: `{"data": {...}}`. */
@Serializable
data class FrappeDocResponse(val data: JsonObject)

/** `/api/method/...` responses: `{"message": <anything>}`. */
@Serializable
data class FrappeMessageResponse(val message: JsonElement? = null)

/** `/api/method/login` response. */
@Serializable
data class LoginResponse(
    val message: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("home_page") val homePage: String? = null,
)
