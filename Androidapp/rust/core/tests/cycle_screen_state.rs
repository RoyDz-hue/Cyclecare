//! Structural tests for the cycle screen state composition
//! (cycle_screen_state.rs). Primitives are corpus-locked elsewhere; these
//! tests lock the COMPOSITION wiring to the named source lines and the
//! verbatim strings to CycleModels.kt / CycleScreen.kt.

use cyclecare_core::cycle_math::{parse_local_date_iso, CyclePhase};
use cyclecare_core::cycle_screen_state::*;

fn input(last_start: &str, today: &str, selected: &str) -> CycleScreenInput {
    CycleScreenInput {
        last_period_start_date: last_start.into(),
        average_cycle_length: 28,
        period_duration: 5,
        luteal_phase_days: 14,
        date_choice: Some("exact".into()),
        cycle_length_choice: Some("28".into()),
        today: parse_local_date_iso(today).unwrap(),
        selected_date: parse_local_date_iso(selected).unwrap(),
    }
}

#[test]
fn phase_strings_verbatim_from_cycle_models() {
    assert_eq!(CyclePhase::Menstrual.kotlin_name(), "MENSTRUAL");
    assert_eq!(CyclePhase::Follicular.kotlin_name(), "FOLLICULAR");
    assert_eq!(CyclePhase::Ovulation.kotlin_name(), "OVULATION");
    assert_eq!(CyclePhase::Luteal.kotlin_name(), "LUTEAL");
    assert_eq!(CyclePhase::Menstrual.kotlin_subtitle(), "Rest & Reset");
    assert_eq!(CyclePhase::Follicular.kotlin_subtitle(), "Energy Rising");
    assert_eq!(CyclePhase::Ovulation.kotlin_subtitle(), "Peak Fertility");
    assert_eq!(CyclePhase::Luteal.kotlin_subtitle(), "Nurture & Balance");
    assert_eq!(CyclePhase::Menstrual.kotlin_description(), "Uterine lining sheds as hormone levels reach baseline.");
    assert_eq!(CyclePhase::Follicular.kotlin_description(), "Estrogen gradually rises, prompting egg follicles to develop.");
    assert_eq!(CyclePhase::Ovulation.kotlin_description(), "Luteinizing hormone surge triggers the release of a mature ovum.");
    assert_eq!(CyclePhase::Luteal.kotlin_description(), "Progesterone peaks to support the uterine lining, then declines if unfertilized.");
    assert_eq!(CyclePhase::Menstrual.kotlin_advice(), "Prioritize restorative rest, gentle stretching, and iron-rich nourishing foods.");
    assert_eq!(CyclePhase::Follicular.kotlin_advice(), "Embrace rising stamina with creative projects, social connection, and active workouts.");
    assert_eq!(CyclePhase::Ovulation.kotlin_advice(), "Highest fertility window. Energy and mood vary from person to person; notice what is true for you.");
    assert_eq!(CyclePhase::Luteal.kotlin_advice(), "Incorporate magnesium, complex carbohydrates, warm herbal infusions, and mindful pacing.");
}

#[test]
fn disclaimer_chain_matches_source_strings_and_order() {
    assert_eq!(disclaimer_text(false, false), DISCLAIMER_NO_ANCHOR);
    assert_eq!(disclaimer_text(false, true), DISCLAIMER_NO_ANCHOR, "no-anchor wins over approximate");
    assert_eq!(disclaimer_text(true, true), DISCLAIMER_APPROXIMATE);
    assert_eq!(disclaimer_text(true, false), DISCLAIMER_STANDARD);
    assert_eq!(DISCLAIMER_NO_ANCHOR, "No cycle start date is saved, so calendar predictions are unavailable. Settings below are not measured history.");
    assert_eq!(DISCLAIMER_APPROXIMATE, "These are approximate calendar estimates from a date range you selected. They can shift, do not confirm ovulation, and should not be used as contraception.");
    assert_eq!(DISCLAIMER_STANDARD, "Calendar dates are estimates from the date you entered and can shift. They do not confirm ovulation and should not be used as contraception.");
}

#[test]
fn anchored_state_composes_predictions_countdowns_and_chrome() {
    let s = build_cycle_screen_state(&input("2026-10-05", "2026-10-19", "2026-10-26"));
    assert!(s.has_cycle_anchor);
    assert!(!s.cycle_start_is_approximate);
    assert_eq!(s.disclaimer, DISCLAIMER_STANDARD);
    // last start 10-05, cycle 28 -> next start 11-02; today 10-19 -> 14 days
    assert_eq!(s.days_until_next_period, 14);
    assert_eq!(s.next_period_date.unwrap().to_string(), "2026-11-02");
    // today = day 15; ovulation day = 28-14=14 -> after ovulation: 28-15+14=27
    assert_eq!(s.days_until_ovulation, 27);
    assert!(!s.is_period_active_today);
    let today_p = s.today_prediction.as_ref().unwrap();
    assert_eq!(today_p.day_of_cycle, 15);
    // fertile window = 10..=15, so day 15 is still the Ovulation phase
    assert_eq!(today_p.phase, CyclePhase::Ovulation);
    // selected 10-26 = day 22 -> luteal; chip 28-22=6
    let sel = s.selected_prediction.as_ref().unwrap();
    assert_eq!(sel.day_of_cycle, 22);
    assert_eq!(s.selected_day_of_cycle_text, "22");
    assert_eq!(s.days_until_period_from_selected, Some(6));
    assert_eq!(s.selected_day_title, "Luteal Phase");
}

#[test]
fn no_anchor_nulls_predictions_and_shows_em_dash_and_fallback_titles() {
    // unknown cycle length choice kills the anchor even with a stored date
    let mut i = input("2026-10-05", "2026-10-19", "2026-10-26");
    i.cycle_length_choice = Some("It changes a lot".into());
    let s = build_cycle_screen_state(&i);
    assert!(!s.has_cycle_anchor);
    assert!(s.today_prediction.is_none());
    assert!(s.selected_prediction.is_none());
    assert_eq!(s.selected_day_of_cycle_text, "\u{2014}", "em dash like :149");
    assert_eq!(s.days_until_period_from_selected, None);
    assert_eq!(s.selected_day_title, "Add a start date for estimates");
    assert_eq!(s.days_until_next_period, 0);
    assert_eq!(s.days_until_ovulation, 0);
    assert_eq!(s.disclaimer, DISCLAIMER_NO_ANCHOR);

    // anchor present but selected date has no prediction cannot happen via
    // this API (anchor => Some); the "No estimate" fallback needs anchor
    // with a None prediction - reachable only when calculateDayPrediction
    // returns None (unparseable stored date slips past isNotBlank):
    let mut i2 = input("not-a-date", "2026-10-19", "2026-10-26");
    i2.cycle_length_choice = Some("28".into());
    let s2 = build_cycle_screen_state(&i2);
    assert!(s2.has_cycle_anchor, "isNotBlank + known choices = anchor");
    assert!(s2.selected_prediction.is_none(), "parse failure -> None");
    assert_eq!(s2.selected_day_title, "No estimate for this date");
}

#[test]
fn period_today_chip_is_zero_and_approximate_disclaimer_selected() {
    let mut i = input("2026-10-17", "2026-10-19", "2026-10-18");
    i.date_choice = Some("about a week ago".into());
    let s = build_cycle_screen_state(&i);
    assert!(s.cycle_start_is_approximate);
    assert_eq!(s.disclaimer, DISCLAIMER_APPROXIMATE);
    assert!(s.is_period_active_today, "day 3 of 5-day period");
    let sel = s.selected_prediction.as_ref().unwrap();
    assert!(sel.is_period, "selected day 2 is period");
    assert_eq!(s.days_until_period_from_selected, Some(0), "chip is 0 during period");
}

#[test]
fn chip_uses_raw_average_cycle_length_quirk() {
    // Stored out-of-range setting (no 21..45 clamp on the chip, :90-91):
    // raw 50 minus day 10 = 40 while calculateDayPrediction clamps to 45.
    let mut i = input("2026-10-10", "2026-10-19", "2026-10-19");
    i.average_cycle_length = 50;
    let s = build_cycle_screen_state(&i);
    assert_eq!(s.selected_prediction.as_ref().unwrap().day_of_cycle, 10);
    assert_eq!(s.days_until_period_from_selected, Some(40), "raw setting, no clamp");
}
