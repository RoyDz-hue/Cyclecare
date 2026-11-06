//! Port of util/SoundFeedbackManager.kt (main@31b88f5, 176 lines) -
//! DECISION CORE ONLY: the isSoundEnabled gate (:23) and the three
//! parameter tables (:29-39, :45-59, :65-74), plus the integer
//! numSamples computation (:82, :108: SAMPLE_RATE * durationMs / 1000).
//! The PCM synthesis (attack/decay envelopes, sine mixing, :90-97,
//! :116-127), AudioTrack plumbing and the catch-Throwable-ignores-audio
//! behavior (:99-102, :130-133, :140-171) are host audio glue, not ported.
//! Nothing here proves audible output on a device.

/// :27
pub const SAMPLE_RATE: i32 = 24000;

/// One playback plan: frequencies/notes as the Kotlin float expressions
/// produce them, plus timing. All f32 arithmetic matches Kotlin Float.
#[derive(Debug, Clone, PartialEq)]
pub enum PlayPlan {
    /// playChord(:29-39): simultaneous frequencies with per-voice
    /// amplitudes.
    Chord { frequencies: Vec<f32>, amplitudes: Vec<f32>, duration_ms: i32 },
    /// playArpeggio(:45-59, :65-74): staggered notes.
    Arpeggio { notes_hz: Vec<f32>, note_spacing_ms: i32, total_duration_ms: i32 },
}

fn gate(is_sound_enabled: bool, plan: PlayPlan) -> Option<PlayPlan> {
    // :31/:49/:68 - the isSoundEnabled early return.
    if is_sound_enabled { Some(plan) } else { None }
}

/// :29-39, default baseFreqHz 528f.
pub fn play_artifact_interaction(is_sound_enabled: bool, base_freq_hz: f32) -> Option<PlayPlan> {
    gate(is_sound_enabled, PlayPlan::Chord {
        frequencies: vec![base_freq_hz, base_freq_hz * 1.498f32, base_freq_hz * 2.0f32],
        amplitudes: vec![0.20f32, 0.10f32, 0.05f32],
        duration_ms: 260,
    })
}

/// :45-59.
pub fn play_artifact_acquired(is_sound_enabled: bool, base_freq_hz: f32) -> Option<PlayPlan> {
    gate(is_sound_enabled, PlayPlan::Arpeggio {
        notes_hz: vec![base_freq_hz, base_freq_hz * 1.25f32, base_freq_hz * 1.498f32, base_freq_hz * 2.0f32],
        note_spacing_ms: 65,
        total_duration_ms: 420,
    })
}

/// :65-74 - fixed notes, no base parameter.
pub fn play_gentle_reward_chime(is_sound_enabled: bool) -> Option<PlayPlan> {
    gate(is_sound_enabled, PlayPlan::Arpeggio {
        notes_hz: vec![528f32, 660f32, 792f32],
        note_spacing_ms: 55,
        total_duration_ms: 300,
    })
}

/// :82/:108 - (SAMPLE_RATE * durationMs) / 1000, Kotlin Int arithmetic.
pub fn num_samples(duration_ms: i32) -> i32 {
    (SAMPLE_RATE * duration_ms) / 1000
}
