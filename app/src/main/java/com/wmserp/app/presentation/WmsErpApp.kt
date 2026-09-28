package com.wmserp.app.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wmserp.app.R
import com.wmserp.app.presentation.common.LocaleDefaults
import com.wmserp.app.presentation.common.LocalizedContent
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.components.BottomNavItem
import com.wmserp.app.presentation.components.WmsBottomBar
import com.wmserp.app.presentation.dashboard.DashboardRoute
import com.wmserp.app.presentation.inventory.InventoryRoute
import com.wmserp.app.presentation.login.LoginRoute
import com.wmserp.app.presentation.navigation.Routes
import com.wmserp.app.presentation.orders.DispatchRoute
import com.wmserp.app.presentation.orders.OrdersRoute
import com.wmserp.app.presentation.orders.OrdersTab
import com.wmserp.app.presentation.orders.ReceiveRoute
import com.wmserp.app.presentation.picking.PickListRoute
import com.wmserp.app.presentation.profile.ProfileRoute
import com.wmserp.app.presentation.scan.ScanRoute
import com.wmserp.app.presentation.splash.SplashScreen

private val bottomNavItems = listOf(
    BottomNavItem(Routes.DASHBOARD, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    BottomNavItem(Routes.INVENTORY, R.string.nav_inventory, Icons.Outlined.Insights),
    BottomNavItem(Routes.ORDERS, R.string.nav_orders, Icons.AutoMirrored.Outlined.ReceiptLong),
    BottomNavItem(Routes.PROFILE, R.string.nav_profile, Icons.Outlined.Person, Icons.Filled.Person),
)

@Composable
fun WmsErpApp(mainViewModel: MainViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val language by mainViewModel.language.collectAsStateWithLifecycle()

    LaunchedEffect(language) { LocaleDefaults.apply(language) }

    LocalizedContent(language = language) {
        WmsErpScaffold(mainViewModel = mainViewModel, navController = navController)
    }
}

@Composable
private fun WmsErpScaffold(mainViewModel: MainViewModel, navController: NavHostController) {
    val state by mainViewModel.uiState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = Routes.base(backStackEntry?.destination?.route)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.status) {
        when (val status = state.status) {
            is SessionStatus.SignedIn -> {
                if (currentRoute == null || currentRoute == Routes.SPLASH || currentRoute == Routes.LOGIN) {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            is SessionStatus.SignedOut -> {
                if (currentRoute != Routes.LOGIN) {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                status.message?.let { message ->
                    mainViewModel.consumeMessage()
                    val text = when (message) {
                        is UiText.Res -> context.getString(message.id, *message.args.toTypedArray())
                        is UiText.Plain -> message.value
                    }
                    snackbarHostState.showSnackbar(text)
                }
            }
            SessionStatus.Loading -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (currentRoute in Routes.topLevel) {
                WmsBottomBar(
                    items = bottomNavItems,
                    currentRoute = currentRoute,
                    scanSelected = currentRoute == Routes.SCAN,
                    onNavigate = { navController.navigateToTab(it) },
                    onScan = { navController.navigateToTab(Routes.SCAN) },
                )
            }
        },
    ) { padding ->
        WmsNavHost(navController = navController, modifier = Modifier.padding(padding))
    }
}

fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(Routes.DASHBOARD) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun WmsNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController = navController, startDestination = Routes.SPLASH, modifier = modifier) {
        composable(Routes.SPLASH) { SplashScreen() }

        composable(Routes.LOGIN) { LoginRoute() }

        composable(Routes.DASHBOARD) {
            DashboardRoute(
                onScan = { navController.navigateToTab(Routes.SCAN) },
                onReceive = { navController.navigateToTab(Routes.orders(OrdersTab.RECEIVE)) },
                onDispatch = { navController.navigateToTab(Routes.orders(OrdersTab.DISPATCH)) },
                onReport = { navController.navigateToTab(Routes.INVENTORY) },
            )
        }

        composable(Routes.INVENTORY) { InventoryRoute() }

        composable(
            route = Routes.SCAN_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_TARGET) { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) {
            ScanRoute(onReceivePurchaseOrder = { navController.navigate(Routes.receive(it)) })
        }

        composable(
            route = Routes.ORDERS_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_TAB) { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) {
            OrdersRoute(
                onOpenPurchaseOrder = { navController.navigate(Routes.receive(it)) },
                onOpenSalesOrder = { navController.navigate(Routes.dispatch(it)) },
                onOpenPickList = { navController.navigate(Routes.pickList(it)) },
            )
        }

        composable(
            route = Routes.PICK_LIST_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_PICK_LIST_NAME) { type = NavType.StringType }),
        ) {
            PickListRoute(onBack = { navController.popBackStack() })
        }

        composable(Routes.PROFILE) { ProfileRoute() }

        composable(
            route = Routes.RECEIVE_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_PO_NAME) { type = NavType.StringType }),
        ) {
            ReceiveRoute(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.DISPATCH_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_SO_NAME) { type = NavType.StringType }),
        ) {
            DispatchRoute(onBack = { navController.popBackStack() })
        }
    }
}
