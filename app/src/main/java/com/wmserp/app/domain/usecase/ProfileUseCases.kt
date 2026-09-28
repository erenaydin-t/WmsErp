package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ProfileUpdate
import com.wmserp.app.domain.model.UserProfile
import com.wmserp.app.domain.repository.ProfileRepository
import javax.inject.Inject

class GetProfileUseCase @Inject constructor(private val profileRepository: ProfileRepository) {
    suspend operator fun invoke(forceRefresh: Boolean = false): AppResult<UserProfile> =
        profileRepository.getProfile(forceRefresh)
}

class UpdateProfileUseCase @Inject constructor(private val profileRepository: ProfileRepository) {
    suspend operator fun invoke(update: ProfileUpdate): AppResult<UserProfile> {
        if (update.firstName.isBlank()) return AppResult.Failure(AppError.Validation("First name is required"))
        val cleaned = update.copy(
            firstName = update.firstName.trim(),
            lastName = update.lastName.trim(),
            phone = update.phone?.trim()?.ifBlank { null },
            mobileNo = update.mobileNo?.trim()?.ifBlank { null },
            location = update.location?.trim()?.ifBlank { null },
        )
        return profileRepository.updateProfile(cleaned)
    }
}
