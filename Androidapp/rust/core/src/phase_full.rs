//! Exact PatternEngine phase calculation over Java LocalDate's whole domain.
use crate::cycle_math::{parse_local_date_iso_full,CyclePhase};

pub fn calculate(date: &str, anchor: &str, average: i32, duration: i32, luteal: i32) -> Option<CyclePhase> {
    let day=parse_local_date_iso_full(date).epoch_day()?;
    let Some(start)=parse_local_date_iso_full(anchor).epoch_day() else {return Some(CyclePhase::Follicular)};
    let cycle=average.clamp(21,45);
    let duration=duration.clamp(2,10);
    let luteal=luteal.clamp(10,16);
    let ovulation=(cycle-luteal).clamp(duration+1,cycle-1);
    let fertile_start=(ovulation-5).max(duration+1);
    let fertile_end=(ovulation+1).min(cycle);
    // Kotlin narrows DAYS.between to Int BEFORE floorMod, including overflow.
    let diff=(day-start) as i32;
    let cycle_day=diff.rem_euclid(cycle)+1;
    Some(if cycle_day<=duration {CyclePhase::Menstrual} else if cycle_day<fertile_start {CyclePhase::Follicular}
        else if cycle_day<=fertile_end {CyclePhase::Ovulation} else {CyclePhase::Luteal})
}
