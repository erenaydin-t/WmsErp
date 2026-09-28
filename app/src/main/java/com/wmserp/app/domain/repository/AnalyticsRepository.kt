package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ActivityHeatmap
import com.wmserp.app.domain.model.DashboardKpis
import com.wmserp.app.domain.model.DeliveryDelayReport
import com.wmserp.app.domain.model.InventoryKpis
import com.wmserp.app.domain.model.StockAgingReport

interface AnalyticsRepository {
    suspend fun getDashboardKpis(): AppResult<DashboardKpis>
    suspend fun getInventoryKpis(): AppResult<InventoryKpis>
    suspend fun getDeliveryDelays(limit: Int = 50): AppResult<DeliveryDelayReport>
    suspend fun getActivityHeatmap(days: Int = 7): AppResult<ActivityHeatmap>
    suspend fun getStockAging(limit: Int = 50): AppResult<StockAgingReport>
}
