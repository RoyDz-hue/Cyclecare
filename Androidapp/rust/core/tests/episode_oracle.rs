//! Cross-language parity: PeriodEpisodeEntry.decide vs verbatim production
//! Kotlin on JVM (/downloads/parity/episode/EpisodeParity.kt).

use chrono::NaiveDate;
use cyclecare_core::cycle_math::decide_episode_storage;
use std::path::PathBuf;

fn dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/oracle")
}

fn table(name: &str) -> Vec<(String, Vec<String>)> {
    std::fs::read_to_string(dir().join(name))
        .unwrap_or_else(|e| panic!("read {name}: {e}"))
        .lines()
        .filter(|l| !l.is_empty())
        .map(|l| {
            let mut it = l.split('\t');
            (it.next().unwrap().to_string(), it.map(String::from).collect())
        })
        .collect()
}

fn tok(t: &str) -> Option<NaiveDate> {
    if t == "<NULL>" {
        None
    } else {
        Some(NaiveDate::parse_from_str(t, "%Y-%m-%d").unwrap())
    }
}

#[test]
fn episode_storage_matches_kotlin_outputs() {
    let rs = table("results_episode.tsv");
    for ((case, args), (_, expected)) in table("corpus_episode.tsv").iter().zip(rs.iter()) {
        let d = decide_episode_storage(
            tok(&args[0]),
            tok(&args[1]),
            args[2].parse().unwrap(),
            args[3].parse().unwrap(),
            NaiveDate::parse_from_str(&args[4], "%Y-%m-%d").unwrap(),
        );
        let actual = vec![
            d.raw_end_date.map(|d| d.to_string()).unwrap_or_else(|| "<NULL>".into()),
            d.typical_duration_days.to_string(),
            d.requires_confirmation.to_string(),
            d.warning_message.clone().unwrap_or_else(|| "<NULL>".into()),
            d.error_message.map(|s| s.to_string()).unwrap_or_else(|| "<NULL>".into()),
        ];
        assert_eq!(&actual, expected, "episode case {case} diverged from Kotlin");
    }
}
