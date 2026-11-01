package com.cyclecare.app.ui.screens

import com.cyclecare.app.util.CoinFormatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclecare.app.ui.components.InteractiveArtifact3D
import com.cyclecare.app.R

@Composable
fun CoinTransferDialog(
    currentBalance: java.math.BigDecimal,
    onDismiss: () -> Unit,
    onSendTransfer: (recipientEmail: String, amount: java.math.BigDecimal) -> Unit
) {
    var recipientEmail by remember { mutableStateOf("") }
    var transferAmountText by remember { mutableStateOf("50") }
    var giftNote by remember { mutableStateOf("Sending you warmth & light.") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InteractiveArtifact3D(
                    drawableRes = R.drawable.cycle_coin,
                    size = 40.dp,
                    isInteractive = false
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Transfer Cycle Coins",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Share coins with another registered sister",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Available Vault Balance: ${CoinFormatter.format(currentBalance)} Coins",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = recipientEmail,
                    onValueChange = {
                        recipientEmail = it
                        errorMessage = null
                    },
                    label = { Text("Recipient Registered Email") },
                    placeholder = { Text("sister@example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("transfer_recipient_email_input"),
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = transferAmountText,
                    onValueChange = { raw ->
                        // Never silently rewrite a money amount into a different
                        // one: accept only unambiguous input, keep anything else
                        // as typed and say clearly what is wrong.
                        val (accepted, rejection) = sanitizeCoinAmountInput(raw)
                        transferAmountText = accepted
                        errorMessage = rejection
                    },
                    label = { Text("Coins to Send") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("transfer_amount_input"),
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = giftNote,
                    onValueChange = { giftNote = it },
                    label = { Text("Personal Gift Note (Optional)") },
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("transfer_note_input"),
                    shape = RoundedCornerShape(14.dp)
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amountText = transferAmountText.trim()
                    if (recipientEmail.isBlank() || !recipientEmail.contains("@")) {
                        errorMessage = "Please enter a valid registered email address."
                    } else if (!Regex("""^\d+(\.\d{1,3})?$""").matches(amountText)) {
                        errorMessage = "Enter an amount with up to 3 decimal places."
                    } else {
                        val amount = amountText.toBigDecimal()
                        if (amount <= java.math.BigDecimal.ZERO) {
                            errorMessage = "Please enter an amount greater than zero."
                        } else if (amount > currentBalance) {
                            errorMessage = "Amount exceeds your available balance (${CoinFormatter.format(currentBalance)} coins)."
                        } else {
                            onSendTransfer(recipientEmail, amount)
                        }
                    }
                },
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("confirm_transfer_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Confirm Transfer", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("cancel_transfer_button")
            ) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

private val COIN_AMOUNT_SHAPE = Regex("^\\d*(\\.\\d{0,3})?$")

/**
 * Accepts only unambiguous coin amounts: digits with at most one decimal point
 * and up to 3 decimals (leading/trailing spaces trimmed). Anything else -
 * minus signs, commas, letters, a second dot - is kept exactly as typed with a
 * clear rejection instead of being stripped into a different amount.
 * Returns the text to show and a rejection message (null when acceptable).
 */
internal fun sanitizeCoinAmountInput(raw: String): Pair<String, String?> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "" to null
    if (trimmed.length > 16) return trimmed to "Amount is too long."
    if (!COIN_AMOUNT_SHAPE.matches(trimmed)) {
        return trimmed to "Use digits and at most one decimal point, for example 12.5."
    }
    if (trimmed.startsWith(".")) return trimmed to "Start the amount with a digit, for example 0.5."
    return trimmed to null
}
