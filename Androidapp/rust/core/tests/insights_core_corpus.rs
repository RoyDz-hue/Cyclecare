//! JVM-executed oracle replay for insights_core (fixtures/insights_core).
use cyclecare_core::insights_core::*;

#[test]
fn insights_rules_match_jvm_on_probe_corpus() {
    let expected = include_str!("fixtures/insights_core/jvm_expected.txt");
    let mut lines = expected.lines();
    let tile_cases: Vec<(i32, i32, f64, i32, i32, i32)> = vec![
        (0, 28, 28.0, 0, 0, 0),
        (1, 28, 27.6, 27, 0, 1),
        (2, 28, 29.4, 29, 3, 2),
        (5, 30, 27.9, 28, 2, 1),
        (3, 21, 32.5, 33, 7, 40),
    ];
    for (obs, sa, ba, med, var, tot) in tile_cases {
        let t = baseline_tiles(obs, sa, ba, med, var, tot);
        let actual = format!(
            "{}|{}|{}|{}|{}",
            t.cycle_label, t.cycle_value, t.median_value, t.variability_value, t.data_points_value
        );
        assert_eq!(actual, lines.next().unwrap(), "tiles {obs}");
    }
    assert_eq!(lines.next(), Some("---"));
    for sa in [false, true] {
        for sz in [0usize, 1, 2, 5] {
            let actual = format!(
                "{}|{}|{}|{}|{}|{}",
                sa,
                sz,
                visible_count(sa, sz),
                observations_toggle_label(sa, sz),
                patterns_toggle_label(sa, sz),
                timeline_toggle_label(sa, sz)
            );
            assert_eq!(actual, lines.next().unwrap(), "toggle {sa} {sz}");
        }
    }
    assert_eq!(lines.next(), Some("---"));
    for (cl, pb) in [(27, 5), (0, 5), (0, 1), (-1, 6)] {
        assert_eq!(timeline_entry_detail(cl, pb), lines.next().unwrap(), "tl {cl} {pb}");
    }
    assert!(lines.next().is_none());
}

#[test]
fn toggle_visibility_rule() {
    assert!(!show_toggle(0));
    assert!(!show_toggle(1));
    assert!(show_toggle(2));
}
