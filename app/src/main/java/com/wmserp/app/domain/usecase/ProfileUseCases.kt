package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.UserProfile
import com.wmserp.app.domain.repository.ProfileRepository
import javax.inject.Inject

class GetProfileUseCase @Inject constructor(private val profileRepository: ProfileRepository) {
    suspend operator fun invoke(forceRefresh: Boolean = false): AppResult<UserProfile> =
        profileRepository.getProfile(forceRefresh)
}
