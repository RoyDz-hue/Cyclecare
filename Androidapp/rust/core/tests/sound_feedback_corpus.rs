//! SoundFeedbackManager decision-core pins: gate + parameter tables +
//! integer sample counts. No audio output is claimed.
use cyclecare_core::sound_feedback::*;

#[test]
fn disabled_gate_produces_no_plan() {
    assert_eq!(play_artifact_interaction(false, 528.0), None, ":31");
    assert_eq!(play_artifact_acquired(false, 528.0), None, ":49");
    assert_eq!(play_gentle_reward_chime(false), None, ":68");
}

#[test]
fn artifact_interaction_table() {
    let plan = play_artifact_interaction(true, 528.0).unwrap();
    match plan {
        PlayPlan::Chord { frequencies, amplitudes, duration_ms } => {
            assert_eq!(frequencies, vec![528.0f32, 528.0f32 * 1.498f32, 528.0f32 * 2.0f32], ":33 exact f32 expressions");
            assert_eq!(amplitudes, vec![0.20f32, 0.10f32, 0.05f32], ":34");
            assert_eq!(duration_ms, 260, ":35");
        }
        _ => panic!("wrong plan kind"),
    }
}

#[test]
fn artifact_acquired_and_chime_tables() {
    match play_artifact_acquired(true, 528.0).unwrap() {
        PlayPlan::Arpeggio { notes_hz, note_spacing_ms, total_duration_ms } => {
            assert_eq!(notes_hz, vec![528.0f32, 528.0f32 * 1.25f32, 528.0f32 * 1.498f32, 528.0f32 * 2.0f32], ":48-53");
            assert_eq!(note_spacing_ms, 65);
            assert_eq!(total_duration_ms, 420);
        }
        _ => panic!("wrong plan kind"),
    }
    match play_gentle_reward_chime(true).unwrap() {
        PlayPlan::Arpeggio { notes_hz, note_spacing_ms, total_duration_ms } => {
            assert_eq!(notes_hz, vec![528f32, 660f32, 792f32], ":71 fixed notes");
            assert_eq!(note_spacing_ms, 55);
            assert_eq!(total_duration_ms, 300);
        }
        _ => panic!("wrong plan kind"),
    }
}

#[test]
fn num_samples_int_math() {
    assert_eq!(num_samples(260), 6240, ":82 (24000*260)/1000");
    assert_eq!(num_samples(420), 10080);
    assert_eq!(num_samples(300), 7200);
}
