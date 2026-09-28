package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ActivityEntry
import com.wmserp.app.domain.model.AnalyticsError
import com.wmserp.app.domain.model.AnalyticsSection
import com.wmserp.app.domain.model.DashboardKpis
import com.wmserp.app.domain.model.InventoryAnalytics
import com.wmserp.app.domain.repository.AnalyticsRepository
import com.wmserp.app.domain.repository.InventoryRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import javax.inject.Inject

data class DashboardData(
    val kpis: DashboardKpis,
    val recentActivity: List<ActivityEntry>,
    val activityError: AppError? = null,
)

/** Loads dashboard KPIs and the recent activity feed concurrently. */
class GetDashboardUseCase @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val inventoryRepository: InventoryRepository,
) {
    suspend operator fun invoke(): AppResult<DashboardData> = coroutineScope {
        val kpisDeferred = async { analyticsRepository.getDashboardKpis() }
        val activityDeferred = async { inventoryRepository.getRecentActivity(limit = 8) }

        val kpis = when (val result = kpisDeferred.await()) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return@coroutineScope AppResult.Failure(result.error)
        }
        val activity = activityDeferred.await()
        AppResult.Success(
            DashboardData(
                kpis = kpis,
                recentActivity = activity.getOrNull().orEmpty(),
                activityError = activity.errorOrNull(),
            )
        )
    }
}

/** Loads all analytics widgets; individual widget failures are reported instead of failing everything. */
class GetInventoryAnalyticsUseCase @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
) {
    suspend operator fun invoke(): AppResult<InventoryAnalytics> = coroutineScope {
        val kpis = async { analyticsRepository.getInventoryKpis() }
        val delays = async { analyticsRepository.getDeliveryDelays() }
        val heatmap = async { analyticsRepository.getActivityHeatmap() }
        val aging = async { analyticsRepository.getStockAging() }

        val kpiResult = kpis.await()
        if (kpiResult is AppResult.Failure && kpiResult.error is AppError.Unauthorized) {
            return@coroutineScope AppResult.Failure(kpiResult.error)
        }
        val delaysResult = delays.await()
        val heatmapResult = heatmap.await()
        val agingResult = aging.await()

        val errors = buildList {
            kpiResult.errorOrNull()?.let { add(AnalyticsError(AnalyticsSection.KPIS, it)) }
            delaysResult.errorOrNull()?.let { add(AnalyticsError(AnalyticsSection.DELIVERY_DELAYS, it)) }
            heatmapResult.errorOrNull()?.let { add(AnalyticsError(AnalyticsSection.ACTIVITY_HEATMAP, it)) }
            agingResult.errorOrNull()?.let { add(AnalyticsError(AnalyticsSection.STOCK_AGING, it)) }
        }
        AppResult.Success(
            InventoryAnalytics(
                kpis = kpiResult.getOrNull() ?: com.wmserp.app.domain.model.InventoryKpis(0, 0, 0),
                delays = delaysResult.getOrNull(),
                heatmap = heatmapResult.getOrNull(),
                aging = agingResult.getOrNull(),
                errors = errors,
            )
        )
    }
}
