package com.wmserp.app.data.repository

import com.wmserp.app.data.local.SessionStore
import com.wmserp.app.data.mapper.toDomain
import com.wmserp.app.data.mapper.toRequest
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.dto.UserDto
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ProfileUpdate
import com.wmserp.app.domain.model.UserProfile
import com.wmserp.app.domain.repository.ProfileRepository

class ProfileRepositoryImpl(
    private val dataSource: ErpNextDataSource,
    private val sessionStore: SessionStore,
    private val apiCaller: ApiCaller,
) : ProfileRepository {

    @Volatile
    private var cached: UserProfile? = null

    override suspend fun getProfile(forceRefresh: Boolean): AppResult<UserProfile> {
        val userId = sessionStore.current.userId
        val cachedProfile = cached
        if (!forceRefresh && cachedProfile != null && cachedProfile.email.equals(userId, ignoreCase = true)) {
            return AppResult.Success(cachedProfile)
        }
        return apiCaller.call {
            val id = userId ?: throw AppException(AppError.Unauthorized())
            dataSource.getDoc<UserDto>(DOCTYPE, id).toDomain(sessionStore.current.baseUrl).also { cached = it }
        }
    }

    override suspend fun updateProfile(update: ProfileUpdate): AppResult<UserProfile> = apiCaller.call {
        val id = sessionStore.current.userId ?: throw AppException(AppError.Unauthorized())
        val updated = dataSource.update<com.wmserp.app.data.remote.dto.UserUpdateRequest, UserDto>(DOCTYPE, id, update.toRequest())
            .toDomain(sessionStore.current.baseUrl)
        cached = updated
        sessionStore.update { it.copy(fullName = updated.fullName) }
        updated
    }

    private companion object {
        const val DOCTYPE = "User"
    }
}
