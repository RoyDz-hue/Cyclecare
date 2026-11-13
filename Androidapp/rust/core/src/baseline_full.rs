//! Production baseline calculation over the full java.time.LocalDate domain.
//! The JVM supplies only its exact flow uppercase/membership text primitive.
use crate::cycle_math::parse_local_date_iso_full;
use crate::pattern_engine::BaselineMetrics;
use std::collections::BTreeSet;

pub struct Log { pub date: String, pub bleeding: bool }

pub fn compute(average_cycle_length: i32, period_duration: i32, logs: &[Log]) -> BaselineMetrics {
    let dates: BTreeSet<i64> = logs.iter().filter_map(|l| parse_local_date_iso_full(&l.date).epoch_day()).collect();
    let bleeding: BTreeSet<i64> = logs.iter().filter(|l| l.bleeding)
        .filter_map(|l| parse_local_date_iso_full(&l.date).epoch_day()).collect();
    let window = period_duration.clamp(1, 10) as i64;
    let mut starts = Vec::new();
    let mut episode: Option<i64> = None;
    let mut previous: Option<i64> = None;
    for day in bleeding {
        let consecutive = previous.map(|p| day-p==1).unwrap_or(false);
        if episode.map(|start| day-start>window && !consecutive).unwrap_or(true) {
            starts.push(day);episode=Some(day);
        }
        previous=Some(day);
    }
    // Source's configured anchor is appended only when starts is empty. It
    // cannot create an interval or affect these output metrics, so is not input.
    let mut lengths: Vec<i32> = starts.windows(2).map(|w| (w[1]-w[0]) as i32)
        .filter(|n| (1..=120).contains(n)).collect();
    lengths.sort();
    let configured=average_cycle_length.clamp(21,45);
    if lengths.is_empty() { return BaselineMetrics {
        average_cycle_length: configured as f64, median_cycle_length: configured,
        min_cycle_length: configured, max_cycle_length: configured, variability_days: 0,
        is_regular: false, total_logged_days: dates.len() as i32, observed_cycle_intervals: 0,
    }; }
    let n=lengths.len();
    let average=lengths.iter().map(|n| *n as f64).sum::<f64>() / n as f64;
    let median=if n%2==1 {lengths[n/2] as f64} else {(lengths[n/2-1]+lengths[n/2]) as f64/2.0};
    let variance=lengths.iter().map(|n| {let d=*n as f64-average;d*d}).sum::<f64>() / n as f64;
    BaselineMetrics {average_cycle_length:average,median_cycle_length:(median+0.5).floor() as i32,
        min_cycle_length:lengths[0],max_cycle_length:lengths[n-1],variability_days:(variance.sqrt()+0.5).floor() as i32,
        is_regular:n>=2 && lengths.iter().all(|n| (21..=45).contains(n)) && lengths[n-1]-lengths[0]<=7,
        total_logged_days:dates.len() as i32,observed_cycle_intervals:n as i32}
}
