//! Layout decision pins for DataExportPdf.kt over a recording surface.
//! No real PDF bytes are produced or claimed.
use cyclecare_core::data_export_pdf::*;
use cyclecare_core::sync_codec::{CycleDayLog, CycleSettings, OnboardingProfile};

#[derive(Default)]
struct Rec { draws: Vec<(String, f32, f32, PaintKind)>, pages: u32 }
impl PdfSurface for Rec {
    fn draw_text(&mut self, t: &str, x: f32, y: f32, p: PaintKind) { self.draws.push((t.to_string(), x, y, p)); }
    fn start_new_page(&mut self) { self.pages += 1; }
}
fn rec() -> Rec { Rec { draws: vec![], pages: 1 } }

#[test]
fn short_line_draws_once_at_margin() {
    let mut r = rec();
    Layout::new(&mut r).line("hello", PaintKind::Body, LINE_HEIGHT);
    assert_eq!(r.draws, vec![("hello".to_string(), 48.0, 48.0, PaintKind::Body)]);
}

#[test]
fn empty_line_still_advances() {
    // do-while: line("") draws "" once and advances y by gap.
    let mut r = rec();
    let mut l = Layout::new(&mut r);
    l.line("", PaintKind::Body, LINE_HEIGHT);
    l.line("x", PaintKind::Body, LINE_HEIGHT);
    assert_eq!(r.draws[0].0, "");
    assert_eq!(r.draws[1], ("x".to_string(), 48.0, 64.0, PaintKind::Body));
}

#[test]
fn wrap_at_88_units_with_space_cut() {
    // 90 units total, last space at index 40 -> chunk "a"*39... build:
    // 40 chars, space, then 50 chars = 91 units.
    let text = format!("{} {}", "a".repeat(40), "b".repeat(50));
    let mut r = rec();
    Layout::new(&mut r).line(&text, PaintKind::Body, LINE_HEIGHT);
    assert_eq!(r.draws.len(), 2);
    assert_eq!(r.draws[0].0, "a".repeat(40), "cut at the space (index 40), space excluded");
    assert_eq!(r.draws[1].0, format!("    {}", "b".repeat(50)), "continuation indented 4 spaces");
}

#[test]
fn space_at_index_88_is_inclusive() {
    // java lastIndexOf(' ', 88) checks index 88 INCLUSIVE.
    let text = format!("{} {}", "a".repeat(88), "b".repeat(10)); // space at 88
    let mut r = rec();
    Layout::new(&mut r).line(&text, PaintKind::Body, LINE_HEIGHT);
    assert_eq!(r.draws[0].0, "a".repeat(88), "space found at index 88 -> cut there");
    assert_eq!(r.draws[1].0, format!("    {}", "b".repeat(10)));
}

#[test]
fn no_space_hard_cut_at_88() {
    let text = "a".repeat(100);
    let mut r = rec();
    Layout::new(&mut r).line(&text, PaintKind::Body, LINE_HEIGHT);
    assert_eq!(r.draws[0].0, "a".repeat(88), "no space -> substring(0, 88)");
    assert_eq!(r.draws[1].0, format!("    {}", "a".repeat(12)));
}

#[test]
fn space_at_index_zero_falls_back_to_hard_cut() {
    // Space at index 0: takeIf{it>0} fails -> cut=88.
    let text = format!(" {}", "a".repeat(100));
    let mut r = rec();
    Layout::new(&mut r).line(&text, PaintKind::Body, LINE_HEIGHT);
    assert_eq!(r.draws[0].0.chars().count(), 88);
}

#[test]
fn astral_chars_count_two_units() {
    // 44 astral chars = 88 units; one more astral = 90 units -> wrap.
    let text = "🌸".repeat(45);
    let mut r = rec();
    Layout::new(&mut r).line(&text, PaintKind::Body, LINE_HEIGHT);
    assert_eq!(r.draws.len(), 2, "45 astral chars = 90 UTF-16 units > 88");
}

#[test]
fn page_break_when_y_exceeds_bottom_margin() {
    let mut r = rec();
    let mut l = Layout::new(&mut r);
    // Each line gap 16: y starts 48; break when y > 794. Lines at y=48..;
    // after 46 lines y = 48+46*16 = 784 <=794; line 48 would be at 800.
    for _ in 0..50 {
        l.line("x", PaintKind::Body, LINE_HEIGHT);
    }
    assert!(r.pages > 1, "must have started a new page");
    // First draw on page 2 is at y=MARGIN.
    let first_p2 = r.draws.iter().find(|d| d.2 == 48.0 && d.0 == "x").unwrap();
    assert_eq!(first_p2.2, 48.0);
}

#[test]
fn field_blank_shows_em_dash_and_bool_words() {
    let mut r = rec();
    let mut l = Layout::new(&mut r);
    l.field("Goals", None);
    l.field("Energy", Some("  "));
    l.field("Flow", Some("light"));
    l.bool_field("Period reminders", true);
    l.bool_field("Daily log reminders", false);
    assert_eq!(r.draws[0].0, "Goals: —", ":74-77 blank -> em dash");
    assert_eq!(r.draws[1].0, "Energy: —");
    assert_eq!(r.draws[2].0, "Flow: light");
    assert_eq!(r.draws[3].0, "Period reminders: Yes", ":79");
    assert_eq!(r.draws[4].0, "Daily log reminders: No");
}

fn settings() -> CycleSettings {
    CycleSettings { id: 1, is_onboarding_completed: true, selected_goals_csv: "Track cycle".into(), last_period_start_date: "2026-10-01".into(), average_cycle_length: 28, period_duration: 5, luteal_phase_days: 14, is_app_lock_enabled: false, lock_timeout_minutes: 0, is_discreet_notification_mode: false, is_pregnancy_mode: false, pregnancy_due_date: "".into(), remind_period: true, remind_fertile: false, remind_daily_log: true }
}
fn log() -> CycleDayLog {
    CycleDayLog { date: "2026-10-15".into(), flow: "medium".into(), energy: "".into(), mood: "calm".into(), symptoms: "".into(), vaginal_health: "".into(), urinary_health: "".into(), cervical_mucus: "".into(), ovulation_test: "NONE".into(), bbt_celsius: 36.6, sleep_hours: 7.5, sleep_quality: "good".into(), water_glasses: 8, notes: "felt fine".into(), pain_intensity: 3 }
}

#[test]
fn build_full_structure_and_conditionals() {
    let mut r = rec();
    build(&mut r, &settings(), None, &[log()], "2026-11-03 04:10");
    let texts: Vec<&str> = r.draws.iter().map(|d| d.0.as_str()).collect();
    assert_eq!(texts[0], "CycleCare - My Data Export");
    assert_eq!(r.draws[0].3, PaintKind::Title);
    assert_eq!(texts[1], "Exported: 2026-11-03 04:10", ":86 injected timestamp");
    assert!(texts.contains(&"Cycle settings"));
    assert!(texts.contains(&"Goals: Track cycle"));
    assert!(texts.contains(&"Average cycle length: 28 days"));
    assert!(texts.contains(&"Period reminders: Yes"));
    assert!(!texts.iter().any(|t| t.starts_with("Profile baseline")), "no profile -> section skipped");
    assert!(texts.contains(&"Daily logs (1 entries)"));
    assert!(texts.contains(&"2026-10-15"));
    assert!(texts.contains(&"Flow: medium"));
    assert!(!texts.iter().any(|t| t.starts_with("Energy:")), "blank field skipped in log section");
    assert!(!texts.iter().any(|t| t.starts_with("Ovulation test:")), "NONE is skipped (:126)");
    assert!(texts.contains(&"Basal body temperature: 36.6 °C"), "Float.toString 36.6f");
    assert!(texts.contains(&"Sleep: 7.5 h (good)"));
    assert!(texts.contains(&"Water: 8 glasses"));
    assert!(texts.contains(&"Pain intensity: 3/10"));
    assert!(texts.contains(&"Notes: felt fine"));
}

#[test]
fn integral_float_gets_dot_zero() {
    let mut l2 = log();
    l2.bbt_celsius = 37.0;
    l2.sleep_hours = 8.0;
    l2.sleep_quality = "".into();
    let mut r = rec();
    build(&mut r, &settings(), None, &[l2], "t");
    let texts: Vec<&str> = r.draws.iter().map(|d| d.0.as_str()).collect();
    assert!(texts.contains(&"Basal body temperature: 37.0 °C"), "Java Float.toString(37f) = 37.0");
    assert!(texts.contains(&"Sleep: 8.0 h"), "no quality suffix when blank");
}

#[test]
fn per_log_prebreak_uses_six_line_allowance() {
    let mut r = rec();
    let mut l = Layout::new(&mut r);
    l.ensure_room_for_log(); // y=48, no break
    // Push y past 842-48-96 = 698: 48 + 41*16 = 704 > 698
    for _ in 0..41 { l.line("x", PaintKind::Body, LINE_HEIGHT); }
    l.ensure_room_for_log();
    drop(l);
    assert_eq!(r.pages, 2, ":117 pre-break fired");
}
