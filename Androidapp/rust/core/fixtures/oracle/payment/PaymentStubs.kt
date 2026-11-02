package com.cyclecare.app.data.payment

import com.cyclecare.app.data.economy.CoinPurchasePackage
import com.cyclecare.app.data.economy.EconomyProfile

class UnifiedPaymentResult

class SupabasePaymentService {
    fun processCardPayment(cardNumber: String, expiry: String, cvv: String, pkg: CoinPurchasePackage, profile: EconomyProfile) = UnifiedPaymentResult()
    fun createPaypalOrder(pkg: CoinPurchasePackage, profile: EconomyProfile) = UnifiedPaymentResult()
    fun createOkxCryptoOrder(currency: String, pkg: CoinPurchasePackage, profile: EconomyProfile) = UnifiedPaymentResult()
    fun initiateMpesaStkPush(phoneNumber: String, amountKes: Double, pkg: CoinPurchasePackage, profile: EconomyProfile) = UnifiedPaymentResult()
    fun cancelTransaction(extRef: String) {}
    suspend fun checkTransactionStatus(extRef: String): String = "pending"
}
