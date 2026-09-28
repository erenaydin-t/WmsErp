package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ProfileUpdate
import com.wmserp.app.domain.model.UserProfile

interface ProfileRepository {
    suspend fun getProfile(forceRefresh: Boolean = false): AppResult<UserProfile>
    suspend fun updateProfile(update: ProfileUpdate): AppResult<UserProfile>
}
