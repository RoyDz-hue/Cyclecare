package com.cyclecare.app.data.economy

import java.util.UUID

data class GuidedChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val channelId: String,
    val senderAlias: String,
    val content: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val isSelf: Boolean = false,
    /** Set when a Community Guide (persona) authored this message. */
    val personaId: String? = null,
    /** Server row id, present for messages loaded from or written to the cloud circle. */
    val remoteId: String? = null,
    val isFlagged: Boolean = false
) {
    val isCommunityGuide: Boolean get() = personaId != null
}

/**
 * Spec §16: Guided Community Circles messaging repository.
 * Server-backed circles (chat_topics/chat_messages) are used when a session is
 * available; the built-in sample circles remain as a calm offline fallback.
 */
class GuidedChatsRepository {

    /** Offline fallback circles, shown when cloud topics cannot be loaded. */
    private val fallbackChannels: List<GuidedChatChannel> = listOf(
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

    @Volatile
    private var liveChannels: List<GuidedChatChannel>? = null

    /** Channels to display: live server circles when loaded, otherwise the fallback set. */
    val availableChannels: List<GuidedChatChannel>
        get() = liveChannels ?: fallbackChannels

    /** Refreshes the circle directory from the server. Returns true when live topics are in use. */
    suspend fun refreshChannels(service: CommunityGuidesService): Boolean {
        val topics = try { service.fetchTopics() } catch (_: Exception) { emptyList() }
        if (topics.isEmpty()) return false
        liveChannels = topics.map { topic ->
            GuidedChatChannel(
                id = "server_${topic.id}",
                title = topic.title,
                topicGuide = topic.description.ifBlank { "A gentle space to share and support each other." },
                minTier = UserEconomyTier.FREE,
                serverTopicId = topic.id
            )
        }
        return true
    }

    /** Joins a live circle and loads its full message stream. Empty list on failure. */
    suspend fun loadLiveMessages(service: CommunityGuidesService, channel: GuidedChatChannel): List<GuidedChatMessage> {
        val topicId = channel.serverTopicId ?: return emptyList()
        return try {
            service.ensureMembership(topicId)
            service.fetchMessages(topicId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Posts to a live circle. Null on failure so the caller can fall back gracefully. */
    suspend fun postLiveMessage(service: CommunityGuidesService, channel: GuidedChatChannel, content: String): GuidedChatMessage? {
        val topicId = channel.serverTopicId ?: return null
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.length > 500) return null
        return try {
            service.ensureMembership(topicId)
            service.postMessage(topicId, trimmed)
        } catch (_: Exception) {
            null
        }
    }

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
