package com.wmserp.app.domain.common

/**
 * Domain-level error taxonomy. The data layer maps transport/ERPNext errors into one of these
 * so that ViewModels never depend on HTTP or Retrofit types.
 */
sealed class AppError(open val message: String) {
    /** Could not reach the server (DNS, timeout, TLS, offline...). */
    data class Network(override val message: String = "Unable to reach the ERPNext server") : AppError(message)

    /** Invalid credentials or the session has expired. */
    data class Unauthorized(override val message: String = "Invalid credentials or session expired") : AppError(message)

    /** The user is authenticated but lacks the ERPNext role/permission. */
    data class Forbidden(override val message: String = "You do not have permission for this action") : AppError(message)

    /** The requested document does not exist. */
    data class NotFound(override val message: String = "Not found") : AppError(message)

    /** Input validation failed either locally or on the server (e.g. ValidationError). */
    data class Validation(override val message: String) : AppError(message)

    /** ERPNext returned a server side failure. */
    data class Server(override val message: String, val code: Int? = null) : AppError(message)

    /** Anything that could not be classified. */
    data class Unknown(override val message: String = "Something went wrong") : AppError(message)
}
