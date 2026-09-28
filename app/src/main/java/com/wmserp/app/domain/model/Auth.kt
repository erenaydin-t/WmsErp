package com.wmserp.app.domain.model

/** How the app authenticates against ERPNext. */
sealed class Credentials {
    /** Standard `/api/method/login` session login. */
    data class Password(val username: String, val password: String) : Credentials()

    /** Token based auth using an ERPNext API key / secret pair (`Authorization: token key:secret`). */
    data class ApiToken(val apiKey: String, val apiSecret: String) : Credentials()
}

/** An authenticated ERPNext session. */
data class UserSession(
    val userId: String,
    val fullName: String,
    val baseUrl: String,
)

/** Values used to pre-fill the login form. */
data class LoginPrefill(
    val baseUrl: String = "",
    val username: String = "",
    val rememberMe: Boolean = false,
)

sealed class SessionEvent {
    /** The server rejected the stored session; the user must sign in again. */
    data object Expired : SessionEvent()
    data object LoggedOut : SessionEvent()
}
