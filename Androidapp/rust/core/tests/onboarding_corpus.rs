//! Executed-JVM corpus runner for LocalAdaptiveOnboardingBank (verbatim
//! source compiled unchanged, 2026-11-02 14:03 EAT).
use cyclecare_core::onboarding_questions::questions_for;

#[test]
fn onboarding_bank_matches_executed_jvm_corpus() {
    let corpus = include_str!("../fixtures/oracle/corpus_onboarding.tsv");
    let results = include_str!("../fixtures/oracle/results_onboarding.tsv");
    let expected: std::collections::HashMap<&str, &str> = results
        .lines()
        .filter(|l| !l.is_empty())
        .map(|l| {
            let (id, rest) = l.split_once('\t').unwrap();
            (id, rest)
        })
        .collect();
    for line in corpus.lines().filter(|l| !l.is_empty()) {
        let c: Vec<&str> = line.split('\t').collect();
        let id = c[0];
        let step: u32 = id[1..2].parse().unwrap();
        let goals: Vec<&str> = if c[1] == "-" { vec![] } else { c[1].split(',').collect() };
        let prior: Vec<Vec<&str>> = if c[2] == "-" {
            vec![]
        } else {
            c[2]
                .split(';')
                .map(|e| e.split_once(':').unwrap().1.split('+').collect())
                .collect()
        };
        let qs = questions_for(step, &goals, &prior);
        let got = if qs.is_empty() {
            "EMPTY".to_string()
        } else {
            qs.iter()
                .map(|q| format!("{}~{}~{}~{}", q.id, q.prompt, q.options.join(","), q.multi_select))
                .collect::<Vec<_>>()
                .join("|")
        };
        assert_eq!(&got, expected.get(id).unwrap(), "case {id}");
    }
}
