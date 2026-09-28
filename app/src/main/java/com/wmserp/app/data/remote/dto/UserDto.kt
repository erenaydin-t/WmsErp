package com.wmserp.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ERPNext `User` DocType. */
@Serializable
data class UserDto(
    val name: String,
    val email: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val username: String? = null,
    val phone: String? = null,
    @SerialName("mobile_no") val mobileNo: String? = null,
    val location: String? = null,
    @SerialName("user_image") val userImage: String? = null,
    @SerialName("role_profile_name") val roleProfileName: String? = null,
    val language: String? = null,
    val enabled: Int? = null,
    val roles: List<UserRoleDto> = emptyList(),
)

@Serializable
data class UserRoleDto(val role: String)

/** Fields the app is allowed to update on its own `User` document. */
@Serializable
data class UserUpdateRequest(
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    val phone: String? = null,
    @SerialName("mobile_no") val mobileNo: String? = null,
    val location: String? = null,
)
