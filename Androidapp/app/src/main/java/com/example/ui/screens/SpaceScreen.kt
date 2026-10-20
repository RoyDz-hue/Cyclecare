package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.CycleCareViewModel
import com.example.ui.viewmodel.CycleUiState

/**
 * Spec §75: SPACE destination.
 * Combines Circles (Guided community connection) and Atelier (rewards, 3D artifacts, themes).
 */
@Composable
fun SpaceScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    initialTab: Int = 0 // 0 = Circles, 1 = Atelier
) {
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Circles", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) },
                icon = { Icon(Icons.Default.Groups, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Atelier & Rewards", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) },
                icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> {
                    GuidedChatsScreen(
                        profile = uiState.economyProfile,
                        repository = viewModel.guidedChatsRepo,
                        onNavigateBack = { /* Stay in Space */ },
                        onUpgradeTierClicked = { tier -> viewModel.upgradeEconomyTier(tier) },
                        isAdmin = uiState.isAdmin
                    )
                }
                1 -> {
                    AtelierScreen(
                        profile = uiState.economyProfile,
                        onClaimCoins = { viewModel.claimCoinsManual() },
                        onTransferCoins = { recipient, amount -> viewModel.transferCoins(recipient, amount) },
                        onUnlockItem = { item -> viewModel.unlockArtifact(item) },
                        onEquipItem = { item -> viewModel.equipItem(item) },
                        onPurchasePackage = { pkg -> viewModel.openMpesaDialog(pkg) },
                        onUpgradeTier = { tier -> viewModel.upgradeEconomyTier(tier) },
                        guidedChatsRepository = viewModel.guidedChatsRepo,
                        onNavigateBack = { selectedTab = 0 },
                        isAdmin = uiState.isAdmin
                    )
                }
            }
        }
    }
}
