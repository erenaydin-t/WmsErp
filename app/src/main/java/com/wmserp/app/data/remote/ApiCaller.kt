package com.wmserp.app.data.remote

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.SessionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Broadcasts session level events (expiry / logout) from the data layer to the UI. */
class SessionEventBus {
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SessionEvent> = _events
    fun emit(event: SessionEvent) { _events.tryEmit(event) }
}

/**
 * Executes API calls and converts every failure mode into an [AppResult.Failure] with a typed [AppError].
 * When the server rejects the session, [SessionEvent.Expired] is broadcast so the app can return to login.
 */
class ApiCaller(private val eventBus: SessionEventBus) {

    suspend fun <T> call(notifyOnAuthFailure: Boolean = true, block: suspend () -> T): AppResult<T> {
        return try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppException) {
            handle(e.error, notifyOnAuthFailure)
        } catch (e: HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            handle(ErpNextErrorParser.parse(e.code(), body), notifyOnAuthFailure)
        } catch (e: SerializationException) {
            AppResult.Failure(AppError.Server("Unexpected response from ERPNext: ${e.message ?: "invalid JSON"}"))
        } catch (e: IllegalArgumentException) {
            AppResult.Failure(AppError.Server("Unexpected response from ERPNext: ${e.message ?: "invalid data"}"))
        } catch (e: IOException) {
            AppResult.Failure(AppError.Network(networkMessage(e)))
        }
    }

    private fun <T> handle(error: AppError, notify: Boolean): AppResult<T> {
        if (notify && error is AppError.Unauthorized) eventBus.emit(SessionEvent.Expired)
        return AppResult.Failure(error)
    }

    private fun networkMessage(e: IOException): String = when (e) {
        is UnknownHostException -> "Cannot resolve the ERPNext server address. Check the URL and your connection."
        is SocketTimeoutException -> "The ERPNext server timed out. Please try again."
        is SSLException -> "Secure connection failed: ${e.message ?: "TLS error"}"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Unable to reach the ERPNext server"
    }
}
