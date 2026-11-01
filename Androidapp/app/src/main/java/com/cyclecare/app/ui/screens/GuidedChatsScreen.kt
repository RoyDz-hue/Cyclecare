package com.cyclecare.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclecare.app.data.economy.CommunityGuidesService
import com.cyclecare.app.data.economy.CommunityPulsePinger
import com.cyclecare.app.data.economy.CosmeticAssets
import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.economy.GuidedChatMessage
import com.cyclecare.app.data.economy.GuidedChatsRepository
import com.cyclecare.app.data.economy.UserEconomyTier
import kotlinx.coroutines.launch

@Composable
fun GuidedChatsScreen(
    profile: EconomyProfile,
    repository: GuidedChatsRepository,
    onNavigateBack: () -> Unit,
    onUpgradeTierClicked: (UserEconomyTier) -> Unit,
    isAdmin: Boolean = false,
    communityGuidesService: CommunityGuidesService? = null,
    embedded: Boolean = false
) {
    var selectedChannelId by remember { mutableStateOf<String?>(null) }
    var channelsVersion by remember { mutableStateOf(0) }

    // Load live server-backed circles once when a session is available; the
    // built-in sample circles stay in place when the cloud cannot be reached.
    LaunchedEffect(communityGuidesService) {
        val service = communityGuidesService ?: return@LaunchedEffect
        if (repository.refreshChannels(service)) channelsVersion++
    }

    val channels = remember(channelsVersion) { repository.availableChannels }
    val selectedChannel = channels.find { it.id == selectedChannelId }

    val isUserAllowedAccess = isAdmin || profile.tier == UserEconomyTier.SUPPORTER || profile.tier == UserEconomyTier.PREMIUM || true

    Scaffold(
        topBar = {
            // Embedded inside the Space tab the tab row names the section, so the
            // directory level needs no chrome; a compact header remains inside a channel.
            if (!(embedded && selectedChannelId == null)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (embedded) Modifier else Modifier.statusBarsPadding())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (selectedChannelId != null) selectedChannelId = null else onNavigateBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = selectedChannel?.title ?: "Community Circles",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = if (selectedChannel != null) "Live Circle Reflection" else "Supportive, guided discussion spaces",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(if (embedded) Modifier else Modifier.navigationBarsPadding())
                .imePadding()
        ) {
            if (selectedChannel == null) {
                // Topic Directory
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    // Bottom clearance for the floating Ask bubble.
                    contentPadding = PaddingValues(bottom = 92.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Welcome to Community Circles",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "A calm space to share gentle reflections and lived experiences across cycle phases. We keep all conversations supportive and peaceful.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    items(channels) { channel ->
                        Card(
                            onClick = { selectedChannelId = channel.id },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("guided_channel_${channel.id}")
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = channel.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = channel.topicGuide,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(10.dp))
                                // Single clear call-to-action (the whole card opens the circle).
                                Text(
                                    text = "Join Discussion",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            } else {
                // Live Connected Topic Room
                var draftText by remember { mutableStateOf("") }
                var sendFailed by remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()
                val messages = remember(selectedChannel.id) {
                    mutableStateListOf<GuidedChatMessage>().apply {
                        addAll(repository.getMessages(selectedChannel.id))
                    }
                }

                // Load the live stream for server-backed circles and swap it in.
                LaunchedEffect(selectedChannel.id) {
                    val service = communityGuidesService ?: return@LaunchedEffect
                    if (selectedChannel.serverTopicId == null) return@LaunchedEffect
                    val live = repository.loadLiveMessages(service, selectedChannel)
                    if (live.isNotEmpty()) {
                        messages.clear()
                        messages.addAll(live)
                    }
                }

                var messagePendingFlag by remember { mutableStateOf<GuidedChatMessage?>(null) }
                var flagOutcome by remember { mutableStateOf<String?>(null) }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Topic Guide Header Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                    ) {
                        Text(
                            text = "Guided Topic: ${selectedChannel.topicGuide}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(14.dp)
                        )
                    }

                    // Live Messages List
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages) { msg ->
                            GuidedMessageRow(
                                message = msg,
                                onLongPress = {
                                    if (!msg.isSelf && msg.remoteId != null && !msg.isFlagged) {
                                        messagePendingFlag = msg
                                    }
                                }
                            )
                        }
                    }

                    flagOutcome?.let { outcome ->
                        Text(
                            text = outcome,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                        )
                    }
                    if (sendFailed) {
                        Text(
                            text = "Your reflection could not reach the circle just now. Please try again in a moment.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                        )
                    }

                    // Message Composer
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = draftText,
                                onValueChange = { if (it.length <= 500) draftText = it },
                                placeholder = { Text("Message the circle (max 500 chars)…", style = MaterialTheme.typography.bodySmall) },
                                modifier = Modifier.weight(1f),
                                minLines = 1,
                                maxLines = 6,
                                shape = RoundedCornerShape(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    val trimmed = draftText.trim()
                                    if (trimmed.isNotEmpty()) {
                                        sendFailed = false
                                        val service = communityGuidesService
                                        if (selectedChannel.serverTopicId != null && service != null) {
                                            coroutineScope.launch {
                                                val posted = repository.postLiveMessage(service, selectedChannel, trimmed)
                                                if (posted != null) {
                                                    messages.add(posted)
                                                    draftText = ""
                                                    // Give the circle's Community Guides a chance to
                                                    // answer soon. Server-side lease, cooldowns and
                                                    // budgets gate everything; failure is ignored.
                                                    try { CommunityPulsePinger.ping(null) } catch (_: Exception) { }
                                                } else {
                                                    sendFailed = true
                                                }
                                            }
                                        } else {
                                            val result = repository.postMessage(selectedChannel.id, trimmed)
                                            if (result.isSuccess) {
                                                messages.add(result.getOrThrow())
                                                draftText = ""
                                            }
                                        }
                                    }
                                },
                                enabled = draftText.isNotBlank()
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = if (draftText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }

                // Report-for-review confirmation
                messagePendingFlag?.let { pending ->
                    AlertDialog(
                        onDismissRequest = { messagePendingFlag = null },
                        title = { Text("Report this message?") },
                        text = { Text("A moderator will review this message. The author is not told who reported it.") },
                        confirmButton = {
                            TextButton(onClick = {
                                val target = pending
                                messagePendingFlag = null
                                val service = communityGuidesService
                                val remoteId = target.remoteId
                                if (service != null && remoteId != null) {
                                    coroutineScope.launch {
                                        val ok = service.flagMessage(remoteId)
                                        flagOutcome = if (ok) {
                                            val idx = messages.indexOfFirst { it.id == target.id }
                                            if (idx >= 0) messages[idx] = messages[idx].copy(isFlagged = true)
                                            "Thank you. This message has been sent for review."
                                        } else {
                                            "The report could not be sent just now. Please try again in a moment."
                                        }
                                    }
                                }
                            }) { Text("Report") }
                        },
                        dismissButton = {
                            TextButton(onClick = { messagePendingFlag = null }) { Text("Cancel") }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuidedMessageRow(
    message: GuidedChatMessage,
    onLongPress: () -> Unit
) {
    val isMe = message.isSelf
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        val guideAvatar = CosmeticAssets.personaAvatarRes(message.personaId)
        if (!isMe && message.isCommunityGuide && guideAvatar != null) {
            Image(
                painter = painterResource(guideAvatar),
                contentDescription = "${message.senderAlias}, Community Guide",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMe) 16.dp else 4.dp,
                bottomEnd = if (isMe) 4.dp else 16.dp
            ),
            color = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.senderAlias,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                    if (message.isCommunityGuide) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = "Community Guide",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                com.cyclecare.app.ui.components.ChatText(text = message.content)
            }
        }
    }
}
