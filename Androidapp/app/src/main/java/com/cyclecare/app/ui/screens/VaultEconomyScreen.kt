package com.cyclecare.app.ui.screens

import com.cyclecare.app.util.CoinFormatter
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
import androidx.compose.runtime.LaunchedEffect
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
import com.cyclecare.app.R
import com.cyclecare.app.data.economy.CurrencyLocaleManager
import com.cyclecare.app.data.economy.EconomyCatalog
import com.cyclecare.app.data.economy.EconomyItem
import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.economy.CommunityGuidesService
import com.cyclecare.app.data.economy.GuidedChatsRepository
import com.cyclecare.app.data.economy.ItemCategory
import com.cyclecare.app.data.economy.UserEconomyTier
import com.cyclecare.app.ui.components.InteractiveArtifact3D

@Composable
fun VaultEconomyScreen(
    profile: EconomyProfile,
    onMineCoins: () -> Unit,
    onTransferCoins: (recipientEmail: String, amount: java.math.BigDecimal) -> Unit,
    onUnlockItem: (EconomyItem) -> Unit,
    onEquipItem: (EconomyItem) -> Unit,
    onPurchasePackage: (com.cyclecare.app.data.economy.CoinPurchasePackage) -> Unit,
    onUpgradeTier: (UserEconomyTier) -> Unit,
    guidedChatsRepository: GuidedChatsRepository,
    onNavigateBack: () -> Unit,
    isAdmin: Boolean = false,
    communityGuidesService: CommunityGuidesService? = null,
    embedded: Boolean = false
) {
    val context = LocalContext.current
    var storePackages by remember { mutableStateOf(com.cyclecare.app.data.economy.RemoteCoinPackages.active(context)) }
    var pricingTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (com.cyclecare.app.data.economy.RemoteCoinPackages.refresh(context)) {
            storePackages = com.cyclecare.app.data.economy.RemoteCoinPackages.active(context)
        }
        if (com.cyclecare.app.data.economy.RemoteLocalPricing.refresh(context, CurrencyLocaleManager.detectCountryCode(context))) {
            pricingTick += 1
        }
    }
    val localizedPackages = remember(storePackages, pricingTick) { CurrencyLocaleManager.localizePackages(storePackages, context) }

    var selectedTabIndex by remember { mutableIntStateOf(0) } // 0 = Artifacts, 1 = Frames & Themes, 2 = Coin Store
    var showTransferDialog by remember { mutableStateOf(false) }
    var showGuidedChatsScreen by remember { mutableStateOf(false) }
    var showHowItWorksDialog by remember { mutableStateOf(false) }

    var selectedArtifactForDetail by remember {
        mutableStateOf(EconomyCatalog.findItem(profile.activeArtifact) ?: EconomyCatalog.ALL_ITEMS[0])
    }
    var itemForInsufficientCoins by remember { mutableStateOf<EconomyItem?>(null) }

    if (showGuidedChatsScreen) {
        GuidedChatsScreen(
            profile = profile,
            repository = guidedChatsRepository,
            onNavigateBack = { showGuidedChatsScreen = false },
            onUpgradeTierClicked = { tier ->
                onUpgradeTier(tier)
            },
            isAdmin = isAdmin,
            communityGuidesService = communityGuidesService,
            embedded = embedded
        )
        return
    }

    Scaffold(
        topBar = {
            if (embedded) {
                // Inside the Space tab the tab row already names this section; keep only the help action.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = { showHowItWorksDialog = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = "How it works",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Hub",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Earn coins, collect artifacts & customize your space",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = { showHowItWorksDialog = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = "How it works",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(if (embedded) Modifier else Modifier.navigationBarsPadding())
                .padding(horizontal = 16.dp),
            // Bottom clearance for the floating Ask bubble so every action
            // (including Claim Daily Coins) can scroll fully clear of it.
            contentPadding = PaddingValues(bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 1: Balance & Mining Hub
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_balance_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFFD4AF37).copy(alpha = 0.14f),
                                        Color(0xFFF9A825).copy(alpha = 0.05f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "BALANCE",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFFB8860B),
                                        letterSpacing = 1.sp
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = CoinFormatter.format(profile.coinBalance),
                                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        InteractiveArtifact3D(
                                            drawableRes = R.drawable.cycle_coin,
                                            size = 34.dp,
                                            isInteractive = false
                                        )
                                    }
                                }

                                // Tier & Multiplier Badge
                                Column(horizontalAlignment = Alignment.End) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                when (profile.tier) {
                                                    UserEconomyTier.PREMIUM -> Color(0xFF8E24AA)
                                                    UserEconomyTier.SUPPORTER -> Color(0xFFE5A65E)
                                                    UserEconomyTier.FREE -> Color(0xFF546E7A)
                                                }
                                            )
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = if (isAdmin) "Admin access" else "${profile.tier.title} plan",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Rank ${profile.rank.title} · ${profile.miningMultiplier}× daily reward",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Gentle Progress Info Banner
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFD4AF37).copy(alpha = 0.12f))
                                    .border(1.dp, Color(0xFFD4AF37).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "✨", fontSize = 16.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Gentle Progress",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFFB8860B)
                                        )
                                        Text(
                                            text = "Earn coins with your daily check-ins",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Lifetime stats on their own line
                            Text(
                                text = "Earned: ${CoinFormatter.format(profile.lifetimeEarned)} • Used: ${CoinFormatter.format(profile.lifetimeSpent)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Action buttons: two balanced, equal-width targets
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showTransferDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("open_transfer_dialog_button")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share Coins", style = MaterialTheme.typography.labelMedium)
                                }

                                Button(
                                    onClick = onMineCoins,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("mine_coins_action_button")
                                ) {
                                    Text("Claim Daily Coins", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
            }

            // Tabs (Plain English)
            item {
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp))
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("Artifacts", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text("Frames & Themes", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        text = { Text("Coin Store", fontWeight = FontWeight.Bold) }
                    )
                }
            }

            // TAB 0: Artifacts
            if (selectedTabIndex == 0) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Artifacts",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Tap or drag.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Selected Artifact Showcase
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    // Static underlay of the same artifact: if the
                                    // interactive 3D layer cannot draw on a device
                                    // (GPU/driver limits), the artifact is never blank.
                                    androidx.compose.foundation.Image(
                                        painter = androidx.compose.ui.res.painterResource(id = selectedArtifactForDetail.drawableRes),
                                        contentDescription = null,
                                        modifier = Modifier.size(240.dp)
                                    )
                                    InteractiveArtifact3D(
                                        drawableRes = selectedArtifactForDetail.drawableRes,
                                        // Expanded, unobstructed viewport (was a cramped 180dp box).
                                        size = 260.dp,
                                        auraColor = Color(selectedArtifactForDetail.auraColorHex),
                                        isInteractive = true,
                                        enableIdleRotation = true,
                                        harmonicPitchHz = selectedArtifactForDetail.harmonicPitchHz
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = selectedArtifactForDetail.name,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                )

                                Text(
                                    text = selectedArtifactForDetail.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                val isUnlocked = profile.unlockedItemIds.contains(selectedArtifactForDetail.id)
                                val isActive = profile.activeArtifact == selectedArtifactForDetail.id

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (isActive) {
                                        OutlinedButton(
                                            onClick = { },
                                            shape = RoundedCornerShape(12.dp),
                                            enabled = false
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Active Artifact")
                                        }
                                    } else if (isUnlocked) {
                                        Button(
                                            onClick = { onEquipItem(selectedArtifactForDetail) },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.testTag("equip_artifact_button")
                                        ) {
                                            Text("Set as Active Artifact")
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                if (profile.coinBalance < selectedArtifactForDetail.price.toBigDecimal()) {
                                                    itemForInsufficientCoins = selectedArtifactForDetail
                                                } else {
                                                    onUnlockItem(selectedArtifactForDetail)
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37)),
                                            modifier = Modifier.testTag("unlock_artifact_button")
                                        ) {
                                            Text(
                                                text = "Unlock for ${selectedArtifactForDetail.price} Coins",
                                                color = Color.Black,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Carousel Row of Artifacts (static fast-path thumbnails for low RAM/GPU)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(EconomyCatalog.ALL_ITEMS.filter { it.category == ItemCategory.ARTIFACT }) { item ->
                                val isSelected = item.id == selectedArtifactForDetail.id
                                val isUnlocked = profile.unlockedItemIds.contains(item.id)

                                Card(
                                    onClick = { selectedArtifactForDetail = item },
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
                                    ),
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                    modifier = Modifier
                                        .width(115.dp)
                                        .testTag("artifact_carousel_${item.id}")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        InteractiveArtifact3D(
                                            drawableRes = item.drawableRes,
                                            size = 64.dp,
                                            isInteractive = false
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = if (isUnlocked) "Owned" else "${item.price} Coins",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isUnlocked) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Community Circles Shortcut Banner
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showGuidedChatsScreen = true }
                            .testTag("open_guided_circles_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Community Circles topic previews",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Browse guided discussion topics. Live group chat is not connected in this build.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Subtle Contextual Supporter/Premium Upgrade Card
                if (profile.tier == UserEconomyTier.FREE && !isAdmin) {
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC89D54).copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFFC89D54), modifier = Modifier.size(16.dp))
                                        Text(
                                            text = "Supporter & Premium plans",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Plans list Circle access; this build currently offers topic previews only, not live group chat.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                OutlinedButton(
                                    onClick = { selectedTabIndex = 2 },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Explore")
                                }
                            }
                        }
                    }
                }
            }

            // TAB 1: Frames & Themes
            if (selectedTabIndex == 1) {
                item {
                    Text(
                        text = "Profile Frames & Custom Themes",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Customize your avatar in community chats and personalize app colors",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(EconomyCatalog.ALL_ITEMS.filter { it.category == ItemCategory.FRAME || it.category == ItemCategory.THEME || it.category == ItemCategory.AVATAR_ART }) { item ->
                    val isUnlocked = profile.unlockedItemIds.contains(item.id)
                    val isActive = profile.activeFrame == item.id || profile.activeAvatarArt == item.id

                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            InteractiveArtifact3D(
                                drawableRes = item.drawableRes,
                                size = 68.dp,
                                isInteractive = false
                            )

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isUnlocked) "Owned" else "Price: ${item.price} Coins",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (isUnlocked) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            if (isActive) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(8.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            } else if (isUnlocked) {
                                OutlinedButton(
                                    onClick = { onEquipItem(item) },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Equip", fontSize = 12.sp)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        if (profile.coinBalance < item.price.toBigDecimal()) {
                                            itemForInsufficientCoins = item
                                        } else {
                                            onUnlockItem(item)
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Unlock", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // TAB 2: Coin Store (fixed server catalog, displayed in KES or USD)
            if (selectedTabIndex == 2) {
                item {
                    Text(
                        text = "Coin Store",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Top up your Cycle Coins to unlock artifacts and profile frames immediately",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(localizedPackages) { pkg ->
                    val isHero = pkg.isHeroPackage

                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isHero) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface
                        ),
                        border = if (isHero) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isHero) 3.dp else 1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = pkg.title,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "${pkg.coins} Coins ${if (pkg.bonusCoins > 0) "+ ${pkg.bonusCoins} Bonus" else ""}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFFD4AF37)
                                    )
                                }

                                if (pkg.badge != null) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.primary)
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = pkg.badge,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = pkg.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    val canonicalPackage = (storePackages + com.cyclecare.app.data.economy.DefaultCoinPackages.PACKAGES)
                                        .firstOrNull { it.id == pkg.id }
                                    if (canonicalPackage != null) onPurchasePackage(canonicalPackage)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isHero) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Text(
                                    text = "Get Coins for ${pkg.priceDisplay}",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Insufficient Coins Validation Dialog
    itemForInsufficientCoins?.let { item ->
        InsufficientCoinsDialog(
            item = item,
            userCoins = profile.coinBalance,
            userEmail = profile.userId,
            onGoToStore = {
                itemForInsufficientCoins = null
                selectedTabIndex = 2
            },
            onDismiss = { itemForInsufficientCoins = null }
        )
    }

    // P2P Coin Transfer Dialog
    if (showTransferDialog) {
        CoinTransferDialog(
            currentBalance = profile.coinBalance,
            onDismiss = { showTransferDialog = false },
            onSendTransfer = { recipient, amount ->
                onTransferCoins(recipient, amount)
                showTransferDialog = false
            }
        )
    }

    // Plain English "How It Works" Dialog
    if (showHowItWorksDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showHowItWorksDialog = false },
            title = {
                Text(
                    text = "How CycleCare Rewards Works",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "1. Earn Free Daily: Log your period, flow, symptoms, or moods every day to claim free Cycle Coins.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "2. Collect Artifacts: Tap to make your artifacts dance and chime, or drag to orbit them.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "3. Personalize Your Profile: Unlock elegant pearl frames to decorate your avatar in community circles.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "4. Always Free Health Tracking: Your cycle predictions, health logging, and data privacy are always 100% free and equal.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showHowItWorksDialog = false }) {
                    Text("Got It")
                }
            }
        )
    }
}

