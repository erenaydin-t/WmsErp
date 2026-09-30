package com.wmserp.app.data.remote

import com.wmserp.app.data.remote.dto.FrappeDocResponse
import com.wmserp.app.data.remote.dto.FrappeListResponse
import com.wmserp.app.data.remote.dto.FrappeMessageResponse
import com.wmserp.app.data.remote.dto.LoginResponse
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

/**
 * Frappe / ERPNext REST API surface used by the app.
 *
 * Paths are relative: the real host is injected per request by [com.wmserp.app.data.remote.interceptor.BaseUrlInterceptor]
 * so that the user can point the app at any ERPNext instance at login time.
 */
interface ErpNextApi {

    // ---- Authentication -------------------------------------------------------------------

    @FormUrlEncoded
    @POST("api/method/login")
    suspend fun login(
        @Field("usr") username: String,
        @Field("pwd") password: String,
        @Field("device") device: String = "mobile",
    ): Response<LoginResponse>

    @GET("api/method/logout")
    suspend fun logout(): Response<Unit>

    @GET("api/method/frappe.auth.get_logged_user")
    suspend fun getLoggedUser(): FrappeMessageResponse

    @GET("api/method/frappe.sessions.get_csrf_token")
    suspend fun getCsrfToken(): FrappeMessageResponse

    @FormUrlEncoded
    @POST("api/method/frappe.core.doctype.user.user.update_password")
    suspend fun updatePassword(
        @Field("old_password") oldPassword: String,
        @Field("new_password") newPassword: String,
        @Field("logout_all_sessions") logoutAllSessions: Int = 0,
    ): FrappeMessageResponse

    // ---- Generic document access ---------------------------------------------------------

    @GET("api/resource/{doctype}")
    suspend fun getList(
        @Path("doctype") doctype: String,
        @Query("fields") fields: String? = null,
        @Query("filters") filters: String? = null,
        @Query("or_filters") orFilters: String? = null,
        @Query("order_by") orderBy: String? = null,
        @Query("limit_page_length") limit: Int? = null,
        @Query("limit_start") start: Int? = null,
    ): FrappeListResponse

    @GET("api/resource/{doctype}/{name}")
    suspend fun getDoc(
        @Path("doctype") doctype: String,
        @Path("name") name: String,
    ): FrappeDocResponse

    @POST("api/resource/{doctype}")
    suspend fun insertDoc(
        @Path("doctype") doctype: String,
        @Body body: JsonObject,
    ): FrappeDocResponse

    @PUT("api/resource/{doctype}/{name}")
    suspend fun updateDoc(
        @Path("doctype") doctype: String,
        @Path("name") name: String,
        @Body body: JsonObject,
    ): FrappeDocResponse

    @DELETE("api/resource/{doctype}/{name}")
    suspend fun deleteDoc(
        @Path("doctype") doctype: String,
        @Path("name") name: String,
    ): Response<Unit>

    // ---- Whitelisted methods -------------------------------------------------------------

    @GET("api/method/{method}")
    suspend fun callMethod(
        @Path("method") method: String,
        @QueryMap params: Map<String, String> = emptyMap(),
    ): FrappeMessageResponse

    @POST("api/method/{method}")
    suspend fun postMethod(
        @Path("method") method: String,
        @Body body: JsonObject,
    ): FrappeMessageResponse

    /** Whitelisted methods that answer outside `message` (e.g. `getdoctype` fills `docs`). */
    @GET("api/method/{method}")
    suspend fun callMethodJson(
        @Path("method") method: String,
        @QueryMap params: Map<String, String> = emptyMap(),
    ): JsonObject
}
