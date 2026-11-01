package com.cyclecare.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cyclecare.app.R
import com.cyclecare.app.data.economy.CoinPurchasePackage
import com.cyclecare.app.data.payment.PaymentOptionType
import com.cyclecare.app.data.payment.PaymentProvider
import com.cyclecare.app.data.payment.PaymentOperation
import com.cyclecare.app.data.payment.PaymentReleasePolicy
import com.cyclecare.app.data.payment.DisabledPaymentReleasePolicy
import com.cyclecare.app.util.NetworkHelper
import com.cyclecare.app.util.PaymentInputValidator
import kotlinx.coroutines.delay

internal fun releasedCheckoutOptions(
    isEastAfricaRegion: Boolean,
    priceKes: Long,
    policy: PaymentReleasePolicy = DisabledPaymentReleasePolicy
): List<PaymentOptionType> = buildList {
    if (isEastAfricaRegion && priceKes > 0L && policy.allows(PaymentProvider.PAYHERO, PaymentOperation.INITIATE)) add(PaymentOptionType.MPESA)
    if (policy.allows(PaymentProvider.PAYPAL_STANDARD, PaymentOperation.INITIATE)) add(PaymentOptionType.PAYPAL)
}

@Composable
fun UnifiedGamifiedCheckoutDialog(
    pkg: CoinPurchasePackage,
    isEastAfricaRegion: Boolean = true,
    isProcessing: Boolean = false,
    statusMessage: String? = null,
    isSuccess: Boolean = false,
    onDismiss: () -> Unit,
    onProcessMpesa: (phone: String, amountKes: Double) -> Unit,
    onProcessCard: (cardNumber: String, expiry: String, cvv: String) -> Unit,
    onProcessPaypal: () -> Unit,
    onProcessCrypto: (currency: String) -> Unit
) {
    // The screen must not collect inputs or advertise a hosted payment route
    // while the same source policy used by the service keeps it closed.
    val availableOptions = remember(isEastAfricaRegion, pkg.priceKes) {
        releasedCheckoutOptions(isEastAfricaRegion, pkg.priceKes)
    }
    if (availableOptions.isEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("checkout_unavailable"),
            title = { Text("Checkout unavailable") },
            text = { Text("Payments aren't open just yet. Nothing will be charged, and no payment details are needed.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } }
        )
        return
    }
    var selectedOption by remember(availableOptions) { mutableStateOf(availableOptions.first()) }
    var mpesaPhone by remember { mutableStateOf("") }

    val context = LocalContext.current
    var isOnline by remember { mutableStateOf(NetworkHelper.isOnline(context)) }
    LaunchedEffect(context) {
        while (true) {
            isOnline = NetworkHelper.isOnline(context)
            delay(2_000)
        }
    }

    val phoneValidation = remember(mpesaPhone) {
        if (mpesaPhone.isBlank()) null else PaymentInputValidator.validateKenyanPhoneNumber(mpesaPhone)
    }
    val amountKes = pkg.priceKes.toDouble()
    val phoneIsValid = phoneValidation?.isValid == true
    val nextStep = when {
        !isOnline -> "Reconnect to continue securely."
        isProcessing && selectedOption == PaymentOptionType.MPESA -> "Follow the prompt on your phone and enter your PIN there."
        isProcessing -> "Approve the payment in PayPal, then return here."
        statusMessage != null && statusMessage.contains("timeout", ignoreCase = true) ->
            "Do not retry a charge until its status and your wallet have been verified."
        statusMessage != null -> "Review the message above, then try again."
        selectedOption == PaymentOptionType.MPESA && phoneIsValid ->
            "Next: send the prompt, then confirm on your phone."
        selectedOption == PaymentOptionType.MPESA -> "Next: enter your Kenyan mobile number."
        else -> "Next: continue to PayPal to approve your payment."
    }

    Dialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF21191A).copy(alpha = 0.46f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { if (!isProcessing) onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .heightIn(max = 740.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                    .testTag("unified_checkout_surface"),
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 18.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CheckoutHeader(
                        isProcessing = isProcessing,
                        onDismiss = onDismiss
                    )

                    PackageSummary(
                        pkg = pkg,
                        selectedOption = selectedOption,
                        isEastAfricaRegion = isEastAfricaRegion,
                        chargeLine = com.cyclecare.app.data.economy.RemoteLocalPricing
                            .cached(context, com.cyclecare.app.data.economy.CurrencyLocaleManager.detectCountryCode(context))
                            ?.get(pkg.id)?.takeIf { it.approx }?.chargeText
                    )

                    when {
                        isSuccess -> SuccessPanel(
                            pkg = pkg,
                            statusMessage = statusMessage,
                            onDismiss = onDismiss
                        )

                        isProcessing -> ProcessingPanel(
                            selectedOption = selectedOption,
                            statusMessage = statusMessage
                        )

                        else -> {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Choose how to pay",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        if (availableOptions.size == 1) "1 verified option" else "Verified options",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    availableOptions.forEach { option ->
                                        PaymentMethodChoice(
                                            option = option,
                                            selected = selectedOption == option,
                                            onlyOption = availableOptions.size == 1,
                                            onClick = { selectedOption = option }
                                        )
                                    }
                                }
                            }

                            if (!isOnline) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.errorContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.WifiOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            "You’re offline. Payment will be ready when you reconnect.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }

                            if (!statusMessage.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Icon(
                                            Icons.Default.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            statusMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }

                            when (selectedOption) {
                                PaymentOptionType.MPESA -> MpesaForm(
                                    phone = mpesaPhone,
                                    validationMessage = phoneValidation?.takeIf { !it.isValid }?.message,
                                    onPhoneChange = { mpesaPhone = it },
                                    isOnline = isOnline,
                                    amountKes = amountKes,
                                    onSubmit = { onProcessMpesa(mpesaPhone, amountKes) }
                                )

                                PaymentOptionType.PAYPAL -> PaypalForm(
                                    isOnline = isOnline,
                                    price = pkg.priceUsd,
                                    onSubmit = onProcessPaypal
                                )

                                // These paths are deliberately omitted from the verified choices above.
                                PaymentOptionType.DEBIT_CREDIT_CARD,
                                PaymentOptionType.CRYPTO -> Unit
                            }

                            NextStepCallout(nextStep)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (selectedOption == PaymentOptionType.MPESA)
                                        "Your M-Pesa PIN is entered on your phone, never in CycleCare."
                                    else "Payment approval is completed in PayPal’s secure checkout.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckoutHeader(
    isProcessing: Boolean,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "A little extra for your vault",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Secure, one-time checkout",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(
            onClick = onDismiss,
            enabled = !isProcessing,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close checkout",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PackageSummary(
    pkg: CoinPurchasePackage,
    selectedOption: PaymentOptionType,
    isEastAfricaRegion: Boolean,
    chargeLine: String? = null
) {
    val amount = if (selectedOption == PaymentOptionType.MPESA) {
        if (pkg.priceKes > 0L) "KES ${pkg.priceKes}" else "Price unavailable"
    } else pkg.priceUsd

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                            MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    )
                )
                .padding(17.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pkg.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    buildString {
                        append("${pkg.coins + pkg.bonusCoins} coins")
                        if (pkg.bonusCoins > 0) append(" · includes ${pkg.bonusCoins} bonus")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("TOTAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    amount,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                if (selectedOption != PaymentOptionType.MPESA && isEastAfricaRegion && pkg.priceKes > 0L) {
                    Text(
                        "M-Pesa KES ${pkg.priceKes}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (chargeLine != null) {
                    Text(
                        chargeLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodChoice(
    option: PaymentOptionType,
    selected: Boolean,
    onlyOption: Boolean,
    onClick: () -> Unit
) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else MaterialTheme.colorScheme.surface
    val description = when (option) {
        PaymentOptionType.MPESA -> "A payment prompt arrives on your Kenyan phone."
        PaymentOptionType.PAYPAL -> "Approve securely with your PayPal account."
        PaymentOptionType.DEBIT_CREDIT_CARD -> ""
        PaymentOptionType.CRYPTO -> ""
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .clickable(onClick = onClick)
            .testTag("payment_method_${option.name.lowercase()}"),
        shape = RoundedCornerShape(19.dp),
        color = background,
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = outline
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(option.drawableAssetRes),
                        contentDescription = null,
                        modifier = Modifier.size(31.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        option.uiLabel,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (onlyOption) {
                        Text(
                            "AVAILABLE HERE",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = if (selected) "Selected" else "Select ${option.uiLabel}",
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun MpesaForm(
    phone: String,
    validationMessage: String?,
    onPhoneChange: (String) -> Unit,
    isOnline: Boolean,
    amountKes: Double,
    onSubmit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(19.dp))
            Text("Your M-Pesa number", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            "We’ll send a secure payment prompt to this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            label = { Text("Kenyan mobile number") },
            placeholder = { Text("0712 345 678") },
            supportingText = {
                Text(
                    validationMessage ?: "Use 07…, 01… or +254…",
                    color = if (validationMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            singleLine = true,
            isError = validationMessage != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth().testTag("mpesa_phone_input")
        )
        Button(
            onClick = onSubmit,
            enabled = isOnline && amountKes > 0 && validationMessage == null && phone.isNotBlank() &&
                PaymentInputValidator.validateKenyanPhoneNumber(phone).isValid,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("pay_mpesa_stk_button"),
            shape = RoundedCornerShape(17.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text(
                "Send payment prompt · KES ${amountKes.toInt()}",
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PaypalForm(
    isOnline: Boolean,
    price: String,
    onSubmit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PayPal checkout", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "You’ll review the order and approve payment in PayPal. CycleCare never stores your PayPal password.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onSubmit,
            enabled = isOnline,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("pay_paypal_express_button"),
            shape = RoundedCornerShape(17.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF145DA0),
                contentColor = Color.White
            )
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("Continue to PayPal · $price", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NextStepCallout(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.62f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(6.dp).size(15.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "UP NEXT",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.7.sp),
                    color = MaterialTheme.colorScheme.secondary
                )
                AnimatedContent(
                    targetState = message,
                    transitionSpec = {
                        (fadeIn() + slideInVertically { it / 4 }) togetherWith
                            (fadeOut() + slideOutVertically { -it / 4 })
                    },
                    label = "checkout_next_step"
                ) { step ->
                    Text(
                        step,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessingPanel(
    selectedOption: PaymentOptionType,
    statusMessage: String?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (selectedOption == PaymentOptionType.MPESA) "Waiting for your phone" else "Securing your PayPal payment",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    statusMessage ?: if (selectedOption == PaymentOptionType.MPESA)
                        "Approve the prompt on your phone to finish."
                    else "Please wait while we verify your order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SuccessPanel(
    pkg: CoinPurchasePackage,
    statusMessage: String?,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.coin_reward),
            contentDescription = "Coin reward",
            modifier = Modifier.size(88.dp)
        )
        Text(
            "Your vault is topped up",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Text(
            statusMessage ?: "${pkg.coins + pkg.bonusCoins} coins are ready to use.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("payment_success_done_button"),
            shape = RoundedCornerShape(17.dp)
        ) {
            Text("Back to your vault", fontWeight = FontWeight.SemiBold)
        }
    }
}
