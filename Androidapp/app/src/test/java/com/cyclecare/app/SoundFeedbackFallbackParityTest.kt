package com.cyclecare.app

import com.cyclecare.app.util.SoundFeedbackManager
import org.junit.Test

/**
 * Twin smoke pins for the sound_feedback_v1 fallback (NEW IMPLEMENTATION,
 * 2026-11-13): the twins are the verbatim pre-wiring bodies, reached by
 * reflection on the SoundFeedbackManager object. Decision-level parity
 * (the isSoundEnabled gate, the three parameter tables, num_samples int
 * math) is pinned in the Rust corpus (sound_feedback_corpus.rs, 4 pins);
 * the twins' coroutine/AudioTrack effects are not observable in a unit
 * test, so these pins assert only that the twins are intact and honor
 * the gate without throwing. Written, NOT executed in this workspace.
 */
class SoundFeedbackFallbackParityTest {

    private fun twin(name: String, enabled: Boolean, baseFreqHz: Float?) {
        val m = if (baseFreqHz != null) {
            SoundFeedbackManager::class.java.getDeclaredMethod(name, Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        } else {
            SoundFeedbackManager::class.java.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
        }
        m.isAccessible = true
        if (baseFreqHz != null) m.invoke(SoundFeedbackManager, enabled, baseFreqHz)
        else m.invoke(SoundFeedbackManager, enabled)
    }

    @Test
    fun disabledGateReturnsQuietly() {
        twin("kotlinPlayArtifactInteraction", false, 528f)
        twin("kotlinPlayArtifactAcquired", false, 528f)
        twin("kotlinPlayGentleRewardChime", false, null)
    }

    @Test
    fun enabledTwinsLaunchWithoutThrowing() {
        twin("kotlinPlayArtifactInteraction", true, 528f)
        twin("kotlinPlayArtifactAcquired", true, 432f)
        twin("kotlinPlayGentleRewardChime", true, null)
    }
}
