package com.cyclecare.app.data.notification

data class UserNotification(
    val id: String = "",
    val messageId: String? = null,
    val targetIdentifier: String = "",
    val title: String = "",
    val body: String = "",
    val type: String = "SYSTEM", // SYSTEM, ALERT, CARE, REWARD, GIFT, WARNING, PENALTY, OFFICIAL_DISPATCH, SECURITY
    val priority: String = "NORMAL", // URGENT, HIGH, NORMAL
    val sender: String = "CYCLECARE_OFFICIAL",
    val actionUrl: String? = null,
    val isRead: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class InHouseMessage(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val messageType: String = "SYSTEM_ANNOUNCEMENT",
    val targetAudience: String = "ALL",
    val actionUrl: String? = null,
    val sentBy: String = "CycleCare Official Care Team",
    val createdAt: Long = System.currentTimeMillis()
)
