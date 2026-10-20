package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.economy.CurrencyLocaleManager
import com.example.data.economy.EconomyCatalog
import com.example.data.economy.EconomyItem
import com.example.data.economy.EconomyProfile
import com.example.data.economy.GuidedChatsRepository
import com.example.data.economy.ItemCategory
import com.example.data.economy.UserEconomyTier
import com.example.ui.components.InteractiveArtifact3D

@Composable
fun AtelierScreen(
    profile: EconomyProfile,
    onClaimCoins: () -> Unit,
    onTransferCoins: (recipientEmail: String, amount: Long) -> Unit,
    onUnlockItem: (EconomyItem) -> Unit,
    onEquipItem: (EconomyItem) -> Unit,
    onPurchasePackage: (com.example.data.economy.CoinPurchasePackage) -> Unit,
    onUpgradeTier: (UserEconomyTier) -> Unit,
    guidedChatsRepository: GuidedChatsRepository,
    onNavigateBack: () -> Unit,
    isAdmin: Boolean = false
) {
    VaultEconomyScreen(
        profile = profile,
        onMineCoins = onClaimCoins,
        onTransferCoins = onTransferCoins,
        onUnlockItem = onUnlockItem,
        onEquipItem = onEquipItem,
        onPurchasePackage = onPurchasePackage,
        onUpgradeTier = onUpgradeTier,
        guidedChatsRepository = guidedChatsRepository,
        onNavigateBack = onNavigateBack,
        isAdmin = isAdmin
    )
}
