//! Shared pattern detection. Full Java dates, insertion ties, exact JVM exp bits.
use crate::cycle_math::{parse_local_date_iso_full,CyclePhase};
use crate::pattern_engine::{PatternSettings,EvaluatedPattern,HealthPattern};
use std::collections::{HashSet,HashMap};
pub struct PatternSymptom {pub key:String,pub label:String,pub lower:String}
pub struct PatternFullLog {pub date_string:String,pub symptoms:Vec<PatternSymptom>}
/// Only the platform exponential primitive remains with the JVM. All date,
/// recency, occurrence, score and output decisions are owned by this module.
pub fn weight_offsets(logs:&[PatternFullLog],today:&str)->Option<Vec<i64>> {
    let today=parse_local_date_iso_full(today).epoch_day()?;
    let mut dates=HashSet::new();let mut offsets=std::collections::BTreeSet::new();
    for log in logs {if let Some(d)=parse_local_date_iso_full(&log.date_string).epoch_day() {
        if dates.insert(d) {offsets.insert((today-d).max(0));}
    }}
    Some(offsets.into_iter().collect())
}
fn evaluate_patterns(
    settings: &PatternSettings,
    logs: &[PatternFullLog],
    today: i64,
    weights: &std::collections::BTreeMap<i64,f64>,
) -> Vec<EvaluatedPattern> {
    // distinctBy date keeps the FIRST occurrence in list order.
    let mut seen_dates: HashSet<i64> = HashSet::new();
    let dated_logs: Vec<(i64, &PatternFullLog)> = logs
        .iter()
        .filter_map(|l| parse_local_date_iso_full(&l.date_string).epoch_day().map(|d| (d, l)))
        .filter(|(d, _)| seen_dates.insert(*d))
        .collect();
    if dated_logs.is_empty() {
        return Vec::new();
    }

    // Group by (symptom, phase), preserving first-occurrence insertion order
    // (Kotlin groupBy iteration order; matters for stable-sort ties).
    let mut group_order: Vec<(String, CyclePhase)> = Vec::new();
    let mut groups: HashMap<(String, CyclePhase), Vec<(i64,)>> = HashMap::new();
    for (date, log) in &dated_logs {
        let phase = crate::phase_full::calculate(&log.date_string, &settings.last_period_start_date, settings.average_cycle_length, settings.period_duration, settings.luteal_phase_days).unwrap();
        let mut seen_symptoms: HashSet<String> = HashSet::new();
        for supplied in &log.symptoms {
            let symptom = supplied.key.as_str();
            if !seen_symptoms.insert(symptom.to_string()) {
                continue;
            }
            let key = (symptom.to_string(), phase);
            if !groups.contains_key(&key) {
                group_order.push(key.clone());
                groups.insert(key.clone(), Vec::new());
            }
            groups.get_mut(&key).unwrap().push((*date,));
        }
    }
    if group_order.is_empty() {
        return Vec::new();
    }

    let mut evaluated: Vec<EvaluatedPattern> = Vec::new();
    for key in &group_order {
        let list = &groups[key];
        let total_count = list.len() as i32;
        if total_count < 2 {
            continue; // Minimum 2 occurrences to form a pattern
        }

        // Recency weighting: weight = exp(-0.03 * daysAgo), daysAgo clamped >= 0.
        let weighted_score: f64 = list
            .iter()
            .map(|(d,)| {
                weights[&(today - *d).max(0)]
            })
            .sum();

        // NOTE: no lower clamp here, verbatim - a future-dated occurrence
        // (negative daysAgo) counts as recent, matching the source.
        let recent_count = list
            .iter()
            .filter(|(d,)| (today - *d) <= 90)
            .count() as i32;

        // Anomaly detection via MAD over intervals.
        let mut sorted_dates: Vec<i64> = list.iter().map(|(d,)| *d).collect();
        sorted_dates.sort();
        let intervals: Vec<f64> = sorted_dates
            .windows(2)
            .map(|w| (w[1] - w[0]) as f64)
            .collect();
        let is_anomaly = if intervals.len() >= 3 {
            let median_interval = compute_median(&intervals);
            let deviations: Vec<f64> = intervals.iter().map(|i| (i - median_interval).abs()).collect();
            let mad = compute_median(&deviations);
            let last_interval = (sorted_dates[sorted_dates.len() - 1]
                - sorted_dates[sorted_dates.len() - 2]) as f64;
            mad > 0.0 && (last_interval - median_interval).abs() > 2.5 * mad
        } else {
            false
        };

        let confidence = if total_count >= 5 && recent_count >= 2 {
            "High"
        } else if total_count >= 3 || recent_count >= 2 {
            "Moderate"
        } else {
            "Emerging"
        };

        evaluated.push(EvaluatedPattern {
            symptom_key: key.0.clone(),
            phase: key.1,
            total_count,
            recent_count,
            weighted_score,
            confidence,
            is_anomaly,
        });
    }

    evaluated
}

pub fn detect_patterns(
    settings: &PatternSettings,
    logs: &[PatternFullLog],
    today: &str,
    math: &[(i64,f64)],
) -> Option<Vec<HealthPattern>> {
    let offsets=weight_offsets(logs,today)?;
    if offsets.len()!=math.len() || offsets.iter().zip(math).any(|(d,(k,v))| d!=k || !v.is_finite() || *v<0.0 || *v>1.0) {return None}
    let weights=math.iter().copied().collect();
    let today=parse_local_date_iso_full(today).epoch_day()?;
    let mut evaluated = evaluate_patterns(settings, logs, today, &weights);
    evaluated.sort_by(|a, b| {
        b.weighted_score
            .partial_cmp(&a.weighted_score)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    evaluated.truncate(6);

    Some(evaluated
        .into_iter()
        .map(|ep| {
            let supplied=logs.iter().flat_map(|l|l.symptoms.iter()).find(|s|s.key==ep.symptom_key).unwrap();
            let label=&supplied.label;
            let phase_title = ep.phase.title();

            let explanation = match ep.phase {
                CyclePhase::Luteal => format!("Progesterone fluctuations before menstruation commonly accompany recurring {label} in your luteal phase."),
                CyclePhase::Menstrual => format!("Inflammatory prostaglandins and shedding uterine tissue frequently correlate with {label} during menstrual bleeding."),
                CyclePhase::Ovulation => format!("The rapid LH surge and estrogen peak around ovulation may trigger {label}."),
                CyclePhase::Follicular => format!("Rising follicular estrogen and metabolic activity frequently align with {label}."),
            };
            let care_advice = match ep.phase {
                CyclePhase::Luteal => "Ensure magnesium-rich nourishment, gentle hydration, and warm compresses for comfort.",
                CyclePhase::Menstrual => "Rest, warm herbal infusions, and light restorative pacing support ease during this phase.",
                CyclePhase::Ovulation => "Stay hydrated and observe whether this recurring signal assists your natural body awareness.",
                CyclePhase::Follicular => "Support rising vitality with balanced nutrition, hydration, and active movement.",
            };
            let observations_text = if ep.recent_count > 0 {
                format!(
                    "Observed {} times ({} in past 3 months) during {}",
                    ep.total_count, ep.recent_count, phase_title
                )
            } else {
                format!("Observed {} times historically during {}", ep.total_count, phase_title)
            };

            HealthPattern {
                id: format!(
                    "pattern_{}_{}",
                    supplied.lower,
                    ep.phase.kotlin_name().to_ascii_lowercase()
                ),
                title: format!("{label} during {phase_title}"),
                supporting_observations: observations_text,
                confidence: ep.confidence.to_string(),
                cycle_phase: phase_title.to_string(),
                explanation,
                actionable_care: care_advice.to_string(),
            }
        })
        .collect())
}

fn compute_median(values: &[f64]) -> f64 {
    if values.is_empty() {
        return 0.0;
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let n = sorted.len();
    if n % 2 == 1 {
        sorted[n / 2]
    } else {
        (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn settings()->PatternSettings {PatternSettings{average_cycle_length:28,period_duration:5,luteal_phase_days:14,last_period_start_date:String::new()}}
    fn logs(days:&[u32])->Vec<PatternFullLog> {days.iter().map(|d|PatternFullLog{date_string:format!("1970-01-{:02}",d+1),symptoms:vec![PatternSymptom{key:"x".into(),label:"X".into(),lower:"x".into()}]}).collect()}
    #[test]
    fn full_pattern_mad_zero_and_positive_thresholds() {
        for (days,want) in [(&[1,2,4,14][..],true),(&[1,2,3,13][..],false)] {
            let logs=logs(days);let weights=days.iter().map(|d|(20-*d as i64,1.0)).collect();let out=evaluate_patterns(&settings(),&logs,20,&weights);
            assert_eq!(out.len(),1);assert_eq!(out[0].is_anomaly,want);assert_eq!(out[0].confidence,"Moderate");assert_eq!(out[0].recent_count,4);
        }
    }
    #[test]
    fn full_pattern_sequential_bits_future_recency_and_exact_plan_coverage() {
        let logs=logs(&[1,2,3]);let weights=[(9,0.1),(8,0.2),(7,0.3)].into_iter().collect();let out=evaluate_patterns(&settings(),&logs,10,&weights);
        assert_eq!(out[0].weighted_score.to_bits(),0.6000000000000001f64.to_bits());
        assert_eq!(weight_offsets(&logs,"1970-01-01"),Some(vec![0]));
        assert!(detect_patterns(&settings(),&logs,"1970-01-01",&[(0,1.0)]).is_some());
        assert!(detect_patterns(&settings(),&logs,"1970-01-01",&[(1,1.0)]).is_none());
        assert!(detect_patterns(&settings(),&logs,"1970-01-01",&[(0,f64::NAN)]).is_none());
    }
}
