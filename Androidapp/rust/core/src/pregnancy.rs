//! Port of PregnancyScreen.kt's inline week/trimester math and its verbatim
//! milestone table (repo main@31b88f5). All values are calendar arithmetic on
//! a user-entered due date - general weekly information only, never a
//! clinical assessment (the screen's own notice says the same).
//!
//! Recorded parity quirks / safety-review concerns (source preserved 1:1,
//! flagged for review, NOT silently redesigned):
//! 1. Activation with no stored due date auto-assigns today + 6 months. That
//!    is source parity, not a clinically established due date and not an
//!    approved activation behavior - flagged for safety review.
//! 2. The milestone table and kick-counter guidance ("aim for 10 kicks within
//!    2 hours") are unvalidated clinical content carried verbatim from source.
//! 3. Source/visual mismatch: week 12 milestone uses the lemon emoji 🍋 but
//!    labels the fruit "Lime" (verbatim from source; recorded, not corrected).
//! 4. The screen has no caller at main@31b88f5 (unwired surface).

use chrono::{Months, NaiveDate};

/// Verbatim WeekMilestone rows from PregnancyScreen.kt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WeekMilestone {
    pub week: i32,
    pub fruit: &'static str,
    pub emoji: &'static str,
    pub length: &'static str,
    pub weight: &'static str,
    pub description: &'static str,
}

pub const MILESTONES: [WeekMilestone; 10] = [
    WeekMilestone { week: 4, fruit: "Poppy Seed", emoji: "\u{1F331}", length: "1 mm", weight: "< 1 g", description: "Blastocyst implants into the uterine lining." },
    WeekMilestone { week: 8, fruit: "Raspberry", emoji: "\u{1F347}", length: "1.6 cm", weight: "1 g", description: "Tiny fingers and toes begin developing." },
    WeekMilestone { week: 12, fruit: "Lime", emoji: "\u{1F34B}", length: "5.4 cm", weight: "14 g", description: "All major organs and reflexes are formed." },
    WeekMilestone { week: 16, fruit: "Avocado", emoji: "\u{1F951}", length: "11.6 cm", weight: "100 g", description: "Baby can hear your voice and make facial expressions." },
    WeekMilestone { week: 20, fruit: "Banana", emoji: "\u{1F34C}", length: "25.6 cm", weight: "300 g", description: "Halfway mark! Baby kicks may now be felt clearly." },
    WeekMilestone { week: 24, fruit: "Corn", emoji: "\u{1F33D}", length: "30 cm", weight: "600 g", description: "Lungs develop surfactant and taste buds form." },
    WeekMilestone { week: 28, fruit: "Eggplant", emoji: "\u{1F346}", length: "37 cm", weight: "1.0 kg", description: "Eyes can open and sense changing light." },
    WeekMilestone { week: 32, fruit: "Pineapple", emoji: "\u{1F34D}", length: "42 cm", weight: "1.7 kg", description: "Bones continue hardening and fingernails grow." },
    WeekMilestone { week: 36, fruit: "Papaya", emoji: "\u{1F348}", length: "47 cm", weight: "2.6 kg", description: "Rapid brain growth and fat layer accumulation." },
    WeekMilestone { week: 40, fruit: "Watermelon", emoji: "\u{1F349}", length: "51 cm", weight: "3.4 kg", description: "Full term! Ready to welcome your little one into the world." },
];

/// Verbatim CALENDAR_FERTILITY_NOTICE (inactive-state teaser text).
pub const CALENDAR_FERTILITY_NOTICE: &str = "CycleCare shows calendar-based estimates of fertile timing. These estimates cannot confirm ovulation or guarantee conception days, and must not be used as contraception. If you become pregnant, Pregnancy Mode offers general weekly information; confirm dates and care with a qualified clinician.";

pub const TOTAL_PREGNANCY_DAYS: i64 = 280;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PregnancyState {
    pub days_remaining: i64,
    pub days_completed: i64,
    pub current_week: i32,
    pub current_trimester: i32,
    pub milestone: WeekMilestone,
}

/// Java LocalDate.plusMonths semantics: calendar-month addition clamping the
/// day to the target month's last day (JVM-probed 2026-11-02:
/// 2026-08-31 + 6 = 2027-02-28; 2023-08-31 + 6 = 2024-02-29).
pub fn plus_months_clamped(d: NaiveDate, months: u32) -> NaiveDate {
    d.checked_add_months(Months::new(months)).unwrap_or(d)
}

/// Verbatim branches: daysRemaining = between(now, due).coerceAtLeast(0);
/// daysCompleted = (280 - remaining).coerceIn(0, 280);
/// week = (completed / 7).coerceIn(1, 40); trimester <=13 -> 1, <=26 -> 2 else 3;
/// milestone = findLast { week <= currentWeek } ?: first.
pub fn compute_pregnancy_state(today: NaiveDate, due: NaiveDate) -> PregnancyState {
    let days_remaining = (due - today).num_days().max(0);
    let days_completed = (TOTAL_PREGNANCY_DAYS - days_remaining).clamp(0, TOTAL_PREGNANCY_DAYS);
    let current_week = ((days_completed / 7) as i32).clamp(1, 40);
    let current_trimester = if current_week <= 13 { 1 } else if current_week <= 26 { 2 } else { 3 };
    let milestone = MILESTONES.iter().rev().find(|m| m.week <= current_week).copied().unwrap_or(MILESTONES[0]);
    PregnancyState { days_remaining, days_completed, current_week, current_trimester, milestone }
}
