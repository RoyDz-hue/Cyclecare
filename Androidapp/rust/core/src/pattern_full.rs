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
