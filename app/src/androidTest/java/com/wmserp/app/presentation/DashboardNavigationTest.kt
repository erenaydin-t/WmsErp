package com.wmserp.app.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wmserp.app.R
import com.wmserp.app.domain.model.DashboardKpis
import com.wmserp.app.domain.usecase.DashboardData
import com.wmserp.app.presentation.components.BottomNavItem
import com.wmserp.app.presentation.components.WmsBottomBar
import com.wmserp.app.presentation.dashboard.DashboardScreen
import com.wmserp.app.presentation.dashboard.DashboardUiState
import com.wmserp.app.presentation.navigation.Routes
import com.wmserp.app.presentation.theme.WmsErpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val state = DashboardUiState(
        isLoading = false,
        greetingName = "Eren",
        data = DashboardData(
            kpis = DashboardKpis(totalItems = 1250, pendingOrders = 18, revenue = 152000.0, currency = "USD", dispatched = 42, periodLabel = "Sep 2026"),
            recentActivity = emptyList(),
        ),
    )

    @Test
    fun dashboardShowsGreetingAndKpis() {
        composeRule.setContent { WmsErpTheme { DashboardScreen(state, {}, {}, {}, {}, {}, {}) } }

        composeRule.onNodeWithTag("dashboard_greeting").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.dashboard_hello_name, "Eren")).assertIsDisplayed()
        composeRule.onNodeWithTag("kpi_total_items").assertIsDisplayed()
        composeRule.onNodeWithText("1,250").assertIsDisplayed()
        composeRule.onNodeWithTag("kpi_pending_orders").assertIsDisplayed()
        composeRule.onNodeWithText("18").assertIsDisplayed()
    }

    @Test
    fun quickActionsTriggerNavigationCallbacks() {
        val clicks = mutableListOf<String>()
        composeRule.setContent {
            WmsErpTheme {
                DashboardScreen(
                    state = state,
                    onRefresh = {},
                    onRetry = {},
                    onScan = { clicks += "scan" },
                    onReceive = { clicks += "receive" },
                    onDispatch = { clicks += "dispatch" },
                    onReport = { clicks += "report" },
                )
            }
        }

        composeRule.onNodeWithTag("action_scan").performClick()
        composeRule.onNodeWithTag("action_receive").performClick()
        composeRule.onNodeWithTag("action_dispatch").performClick()
        composeRule.onNodeWithTag("action_report").performClick()

        assertEquals(listOf("scan", "receive", "dispatch", "report"), clicks)
    }

    @Test
    fun bottomBarSwitchesBetweenDestinations() {
        val items = listOf(
            BottomNavItem(Routes.DASHBOARD, R.string.nav_home, Icons.Outlined.Home),
            BottomNavItem(Routes.INVENTORY, R.string.nav_inventory, Icons.Outlined.Insights),
            BottomNavItem(Routes.ORDERS, R.string.nav_orders, Icons.AutoMirrored.Outlined.ReceiptLong),
            BottomNavItem(Routes.PROFILE, R.string.nav_profile, Icons.Outlined.Person),
        )
        composeRule.setContent {
            WmsErpTheme {
                var route by remember { mutableStateOf(Routes.DASHBOARD) }
                Scaffold(bottomBar = { WmsBottomBar(items, route, onNavigate = { route = it }, onScan = { route = Routes.SCAN }) }) { padding ->
                    androidx.compose.material3.Text("Current: $route", modifier = androidx.compose.ui.Modifier.padding(padding))
                }
            }
        }

        composeRule.onNodeWithText("Current: dashboard").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_inventory").performClick()
        composeRule.onNodeWithText("Current: inventory").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_scan").performClick()
        composeRule.onNodeWithText("Current: scan").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_profile").performClick()
        composeRule.onNodeWithText("Current: profile").assertIsDisplayed()
    }
}
