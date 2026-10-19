package com.example.data.payment

import androidx.annotation.DrawableRes
import com.example.R
import java.util.UUID

enum class PaymentOptionType(
    val uiLabel: String,
    val description: String,
    val isEastAfricaOnly: Boolean,
    @DrawableRes val drawableAssetRes: Int
) {
    MPESA(
        uiLabel = "M-Pesa",
        description = "Instant Mobile Money (East Africa)",
        isEastAfricaOnly = true,
        drawableAssetRes = R.drawable.artifact_mpesa
    ),
    DEBIT_CREDIT_CARD(
        uiLabel = "Debit/Credit Card",
        description = "Visa, Mastercard, Amex (PayPal ACDC)",
        isEastAfricaOnly = false,
        drawableAssetRes = R.drawable.artifact_card
    ),
    PAYPAL(
        uiLabel = "PayPal",
        description = "Express PayPal Wallet Checkout",
        isEastAfricaOnly = false,
        drawableAssetRes = R.drawable.artifact_paypal
    ),
    CRYPTO(
        uiLabel = "Crypto",
        description = "USDT, BTC, ETH via OKX Pay",
        isEastAfricaOnly = false,
        drawableAssetRes = R.drawable.artifact_crypto
    )
}

enum class PaymentProvider {
    PAYHERO,
    PAYPAL_ACDC,
    PAYPAL_STANDARD,
    OKX
}

enum class PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    CANCELLED,
    REFUNDED,
    REVERSED,
    AMBIGUOUS;

    val isAuthoritativeTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED ||
            this == REFUNDED || this == REVERSED
}

data class PaymentTransaction(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val amountUsd: Double = 6.99,
    val amountKes: Double = 900.0,
    val optionType: PaymentOptionType = PaymentOptionType.DEBIT_CREDIT_CARD,
    val providerUsed: PaymentProvider = PaymentProvider.PAYPAL_ACDC,
    val providerReference: String? = null,
    val phoneNumber: String? = null,
    val cardNumberMasked: String? = null,
    val cryptoAddress: String? = null,
    val packageId: String? = null,
    val coinsToCredit: Long = 0L,
    val status: PaymentStatus = PaymentStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val failureReason: String? = null
)

data class UnifiedPaymentResult(
    val isSuccess: Boolean,
    val orderId: String? = null,
    val externalReference: String? = null,
    val checkoutUrl: String? = null,
    val cryptoAddress: String? = null,
    val qrCodeData: String? = null,
    val message: String,
    val transaction: PaymentTransaction? = null
)
