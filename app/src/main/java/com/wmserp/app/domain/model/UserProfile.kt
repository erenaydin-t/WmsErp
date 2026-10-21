package com.wmserp.app.domain.model

/**
 * The signed-in ERPNext `User`, read-only: the app shows what the site holds and never edits it
 * (personal details are maintained in ERPNext itself).
 */
data class UserProfile(
    val email: String,
    val firstName: String,
    val lastName: String,
    val fullName: String,
    val username: String? = null,
    val phone: String? = null,
    val mobileNo: String? = null,
    val location: String? = null,
    val imageUrl: String? = null,
    val roleProfile: String? = null,
    val roles: List<String> = emptyList(),
    val language: String? = null,
) {
    val initials: String
        get() {
            val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            return when {
                parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
                parts.size == 1 -> parts.first().take(2).uppercase()
                else -> email.take(2).uppercase()
            }
        }

    /** A human friendly role description shown in the profile header. */
    val displayRole: String
        get() = roleProfile?.takeIf { it.isNotBlank() }
            ?: roles.firstOrNull { it in PREFERRED_ROLES }
            ?: roles.firstOrNull { it !in GENERIC_ROLES }
            ?: "User"

    /** Roles worth showing (the technical "All" / "Guest" / "Desk User" are hidden). */
    val visibleRoles: List<String> get() = roles.filter { it !in GENERIC_ROLES }.sorted()

    private companion object {
        val PREFERRED_ROLES = listOf(
            "Stock Manager", "Stock User", "Warehouse Manager", "Purchase Manager",
            "Purchase User", "Sales Manager", "Sales User", "System Manager",
        )
        val GENERIC_ROLES = setOf("All", "Guest", "Desk User")
    }
}
