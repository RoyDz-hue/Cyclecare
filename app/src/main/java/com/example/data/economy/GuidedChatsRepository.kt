package com.example.data.economy

/**
 * Curated topic previews shown while live Community Circles messaging is not connected.
 * No participant counts, activity timestamps, or member messages are synthesized here.
 */
class GuidedChatsRepository {

    val availableChannels: List<GuidedChatChannel> = listOf(
        GuidedChatChannel(
            id = "circle_luteal_rest",
            title = "Luteal Phase Softness & Reflection",
            topicGuide = "How might you create space for rest when your energy turns inward?",
            minTier = UserEconomyTier.SUPPORTER
        ),
        GuidedChatChannel(
            id = "circle_ovulation_vitality",
            title = "Creativity & Mid-Cycle Energy",
            topicGuide = "What activities feel meaningful when your energy is higher?",
            minTier = UserEconomyTier.SUPPORTER
        ),
        GuidedChatChannel(
            id = "circle_sovereign_sanctuary",
            title = "Reflection & Wellbeing",
            topicGuide = "What helps you feel supported in your wellbeing journey?",
            minTier = UserEconomyTier.PREMIUM
        )
    )
}
