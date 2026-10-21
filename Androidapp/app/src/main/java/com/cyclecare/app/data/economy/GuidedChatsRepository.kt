package com.cyclecare.app.data.economy

import java.util.UUID

data class GuidedChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val channelId: String,
    val senderAlias: String,
    val content: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val isSelf: Boolean = false
)

/**
 * Spec §16: Guided Community Circles messaging repository.
 * Provides topic channels, community message streams, and outgoing message dispatch
 * with character constraints and moderation guards.
 */
class GuidedChatsRepository {

    val availableChannels: List<GuidedChatChannel> = listOf(
        GuidedChatChannel(
            id = "circle_luteal_rest",
            title = "Luteal Phase Softness & Reflection",
            topicGuide = "How might you create space for rest when your energy turns inward?",
            minTier = UserEconomyTier.FREE
        ),
        GuidedChatChannel(
            id = "circle_ovulation_vitality",
            title = "Creativity & Mid-Cycle Energy",
            topicGuide = "What activities feel meaningful when your energy is higher?",
            minTier = UserEconomyTier.FREE
        ),
        GuidedChatChannel(
            id = "circle_sovereign_sanctuary",
            title = "Reflection & Wellbeing",
            topicGuide = "What helps you feel supported in your daily wellbeing journey?",
            minTier = UserEconomyTier.FREE
        )
    )

    private val inMemoryMessages = mutableMapOf<String, MutableList<GuidedChatMessage>>(
        "circle_luteal_rest" to mutableListOf(
            GuidedChatMessage(
                channelId = "circle_luteal_rest",
                senderAlias = "Gentle Dawn",
                content = "Setting earlier evening wind-down boundaries this week has made a world of difference for my luteal fatigue.",
                timestampMs = System.currentTimeMillis() - 7200000,
                isSelf = false
            ),
            GuidedChatMessage(
                channelId = "circle_luteal_rest",
                senderAlias = "Quiet Meadow",
                content = "Warm chamomile tea and magnesium before bed really helps ease the physical restlessness.",
                timestampMs = System.currentTimeMillis() - 3600000,
                isSelf = false
            )
        ),
        "circle_ovulation_vitality" to mutableListOf(
            GuidedChatMessage(
                channelId = "circle_ovulation_vitality",
                senderAlias = "Sunlit Amber",
                content = "Taking advantage of this follicular-to-ovulation energy surge to reorganize my studio workspace!",
                timestampMs = System.currentTimeMillis() - 5400000,
                isSelf = false
            )
        ),
        "circle_sovereign_sanctuary" to mutableListOf(
            GuidedChatMessage(
                channelId = "circle_sovereign_sanctuary",
                senderAlias = "Serene Willow",
                content = "Remembering that we do not have to be at 100% capacity every day of the month is so liberating.",
                timestampMs = System.currentTimeMillis() - 8640000,
                isSelf = false
            )
        )
    )

    fun getMessages(channelId: String): List<GuidedChatMessage> {
        return inMemoryMessages[channelId]?.toList() ?: emptyList()
    }

    fun postMessage(channelId: String, content: String): Result<GuidedChatMessage> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Message cannot be empty"))
        if (trimmed.length > 500) return Result.failure(IllegalArgumentException("Message exceeds 500 characters"))

        val newMsg = GuidedChatMessage(
            channelId = channelId,
            senderAlias = "You",
            content = trimmed,
            timestampMs = System.currentTimeMillis(),
            isSelf = true
        )
        val list = inMemoryMessages.getOrPut(channelId) { mutableListOf() }
        list.add(newMsg)
        return Result.success(newMsg)
    }
}
