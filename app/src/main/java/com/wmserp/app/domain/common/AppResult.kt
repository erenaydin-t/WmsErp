package com.wmserp.app.domain.common

/**
 * A lightweight result wrapper used across the domain and presentation layers.
 */
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Failure(val error: AppError) : AppResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.data
    fun errorOrNull(): AppError? = (this as? Failure)?.error

    inline fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    inline fun <R> flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
        is Success -> transform(data)
        is Failure -> this
    }

    inline fun onSuccess(block: (T) -> Unit): AppResult<T> {
        if (this is Success) block(data)
        return this
    }

    inline fun onFailure(block: (AppError) -> Unit): AppResult<T> {
        if (this is Failure) block(error)
        return this
    }

    inline fun <R> fold(onSuccess: (T) -> R, onFailure: (AppError) -> R): R = when (this) {
        is Success -> onSuccess(data)
        is Failure -> onFailure(error)
    }

    companion object {
        fun <T> success(data: T): AppResult<T> = Success(data)
        fun failure(error: AppError): AppResult<Nothing> = Failure(error)
    }
}

/** Runs [block] and wraps any [AppError] thrown as [AppException] into a [AppResult.Failure]. */
inline fun <T> appResult(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: AppException) {
    AppResult.Failure(e.error)
}

/** Exception carrier used by the data layer to short-circuit with a typed [AppError]. */
class AppException(val error: AppError, cause: Throwable? = null) : Exception(error.message, cause)
