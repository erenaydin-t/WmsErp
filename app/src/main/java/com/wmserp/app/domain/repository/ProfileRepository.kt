package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.UserProfile

/** The signed-in user's ERPNext `User` document, read only. */
interface ProfileRepository {
    suspend fun getProfile(forceRefresh: Boolean = false): AppResult<UserProfile>
}
