package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.CycleDayLog
import com.example.ui.viewmodel.CycleCareViewModel
import com.example.ui.viewmodel.CycleUiState

enum class AskMode {
    CARE_CHAT,
    LOG_DAY
}

/**
 * Spec §73: Unified conversational & logging interface.
 * Merges natural-language logging & health journaling ("Tell CycleCare")
 * with intelligent care explanations ("Ask CycleCare").
 */
@Composable
fun AskScreen(
    viewModel: CycleCareViewModel,
    uiState: CycleUiState,
    currentLog: CycleDayLog?,
    initialMode: AskMode = AskMode.CARE_CHAT
) {
    var selectedMode by remember(initialMode) { mutableStateOf(initialMode) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Mode Selector Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = selectedMode == AskMode.CARE_CHAT,
                    onClick = { selectedMode = AskMode.CARE_CHAT },
                    label = { Text("Ask CycleCare") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = if (selectedMode == AskMode.CARE_CHAT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )

                FilterChip(
                    selected = selectedMode == AskMode.LOG_DAY,
                    onClick = { selectedMode = AskMode.LOG_DAY },
                    label = { Text("Tell CycleCare (Log)") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = if (selectedMode == AskMode.LOG_DAY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedMode) {
                AskMode.CARE_CHAT -> {
                    CareScreen(
                        viewModel = viewModel,
                        uiState = uiState
                    )
                }
                AskMode.LOG_DAY -> {
                    TrackScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        currentLog = currentLog
                    )
                }
            }
        }
    }
}
