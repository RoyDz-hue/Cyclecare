package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.CycleCareDatabase
import com.example.data.repository.CycleRepository
import com.example.ui.screens.AppLockScreen
import com.example.ui.screens.CareScreen
import com.example.ui.screens.CycleScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.InsightsScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SomethingDifferentSheet
import com.example.ui.screens.TrackScreen
import com.example.ui.theme.CycleCareTheme
import com.example.ui.viewmodel.AppNavTab
import com.example.ui.viewmodel.CycleCareViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = CycleCareDatabase.getDatabase(applicationContext)
        val repository = CycleRepository(database)
        val viewModelFactory = CycleCareViewModel.Factory(repository)

        setContent {
            val viewModel: CycleCareViewModel by viewModels { viewModelFactory }
            CycleCareApp(viewModel = viewModel)
        }
    }
}

@Composable
fun CycleCareApp(viewModel: CycleCareViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logsMap by viewModel.logsMap.collectAsStateWithLifecycle()
    var isSettingsOpen by remember { mutableStateOf(false) }

    CycleCareTheme {
        when {
            // First Launch Onboarding Flow
            uiState.showOnboarding -> {
                OnboardingScreen(
                    onComplete = { goals, lastPeriod, cycleLength, periodDuration, pin, enableLock, remindFertile ->
                        viewModel.completeOnboarding(goals, lastPeriod, cycleLength, periodDuration, pin, enableLock, remindFertile)
                    }
                )
            }

            // Private App Lock Overlay
            uiState.isAppLocked -> {
                AppLockScreen(
                    onUnlock = { pin -> viewModel.unlockApp(pin) }
                )
            }

            // Global Settings Overlay
            isSettingsOpen -> {
                BackHandler { isSettingsOpen = false }
                SettingsScreen(
                    viewModel = viewModel,
                    uiState = uiState,
                    onNavigateBack = { isSettingsOpen = false }
                )
            }

            // Main Core App Shell (Home / Cycle / Track / Insights / Care)
            else -> {
                BackHandler(enabled = uiState.activeTab != AppNavTab.HOME) {
                    viewModel.selectTab(AppNavTab.HOME)
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        CycleCareBottomBar(
                            currentTab = uiState.activeTab,
                            onSelectTab = { viewModel.selectTab(it) }
                        )
                    }
                ) { innerPadding ->
                    AnimatedContent(
                        targetState = uiState.activeTab,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        label = "tab_transition"
                    ) { targetTab ->
                        when (targetTab) {
                            AppNavTab.HOME -> HomeScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                todayLog = logsMap[uiState.currentDate.toString()],
                                onOpenSettings = { isSettingsOpen = true }
                            )
                            AppNavTab.CYCLE -> CycleScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                logsMap = logsMap
                            )
                            AppNavTab.TRACK -> TrackScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                currentLog = logsMap[uiState.selectedDate.toString()]
                            )
                            AppNavTab.INSIGHTS -> InsightsScreen(
                                viewModel = viewModel,
                                uiState = uiState,
                                logsMap = logsMap
                            )
                            AppNavTab.CARE -> CareScreen(
                                viewModel = viewModel,
                                uiState = uiState
                            )
                        }
                    }

                    if (uiState.isSomethingDifferentSheetOpen) {
                        SomethingDifferentSheet(
                            viewModel = viewModel,
                            uiState = uiState
                        )
                    }
                }
            }
        }
    }
}

data class NavItem(val tab: AppNavTab, val label: String, val icon: ImageVector)

@Composable
fun CycleCareBottomBar(
    currentTab: AppNavTab,
    onSelectTab: (AppNavTab) -> Unit
) {
    val items = listOf(
        NavItem(AppNavTab.HOME, "Home", Icons.Default.Home),
        NavItem(AppNavTab.CYCLE, "Cycle", Icons.Default.CalendarMonth),
        NavItem(AppNavTab.TRACK, "Track", Icons.Default.Edit),
        NavItem(AppNavTab.INSIGHTS, "Insights", Icons.Default.TrendingUp),
        NavItem(AppNavTab.CARE, "Care", Icons.Default.Spa)
    )

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("cyclecare_bottom_bar")
    ) {
        items.forEach { item ->
            val selected = currentTab == item.tab
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(item.tab) },
                icon = {
                    Icon(imageVector = item.icon, contentDescription = item.label)
                },
                label = {
                    Text(text = item.label, style = MaterialTheme.typography.labelSmall)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.testTag("nav_${item.tab.name.lowercase()}")
            )
        }
    }
}
