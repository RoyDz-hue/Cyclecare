//! Cross-language parity: EarnCoinsUseCase/MiningUseCase delegation +
//! error vocabulary vs adapted-verbatim production Kotlin
//! (/downloads/parity/earn/EarnParity.kt; service type + suspend adapted,
//! bodies verbatim).

use cyclecare_core::earn_coins::*;

struct Fake;
impl EconomyService<String, String, String, String> for Fake {
    fn verify_and_mine_coins(&self, user_id: &str, p: &String, base_reward: i64) -> EconomyResult<String> {
        EconomyResult::Success(p.clone(), format!("SVC:verifyAndMineCoins:{user_id}:{base_reward}"))
    }
    fn reconcile_pending_offline_claims(&self, user_id: &str, p: &String) -> EconomyResult<String> {
        EconomyResult::Success(p.clone(), format!("SVC:reconcile:{user_id}"))
    }
    fn transfer_coins(&self, p: &String, _e: &str, _a: &bigdecimal::BigDecimal) -> EconomyResult<String> {
        EconomyResult::Success(p.clone(), "SVC:transferCoins".to_string())
    }
    fn purchase_artifact(&self, p: &String, _i: &String) -> EconomyResult<String> {
        EconomyResult::Success(p.clone(), "SVC:purchaseArtifact".to_string())
    }
    fn equip_item(&self, p: &String, _i: &String) -> EconomyResult<String> {
        EconomyResult::Success(p.clone(), "SVC:equipItem".to_string())
    }
    fn purchase_coin_package(&self, p: &String, _k: &String) -> EconomyResult<String> {
        EconomyResult::Success(p.clone(), "SVC:purchasePackage".to_string())
    }
    fn upgrade_tier(&self, p: &String, _r: &String) -> EconomyResult<String> {
        EconomyResult::Success(p.clone(), "SVC:upgradeTier".to_string())
    }
}

fn render(r: &EconomyResult<String>) -> String {
    match r {
        EconomyResult::Error(m) => format!("Error:{m}"),
        EconomyResult::Success(_, m) => format!("Success:{m}"),
    }
}

fn table(name: &str) -> Vec<(String, Vec<String>)> {
    let p = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/oracle").join(name);
    std::fs::read_to_string(&p)
        .unwrap_or_else(|e| panic!("read {name}: {e}"))
        .lines()
        .filter(|l| !l.is_empty())
        .map(|l| {
            let mut it = l.split('\t');
            (it.next().unwrap().to_string(), it.map(String::from).collect())
        })
        .collect()
}

#[test]
fn earn_coins_matches_kotlin_outputs() {
    let rs = table("results_earn_coins.tsv");
    for ((case, args), (_, expected)) in table("corpus_earn_coins.tsv").iter().zip(rs.iter()) {
        let fake = Fake;
        let service = if args[1] == "present" { Some(&fake) } else { None };
        let user_id = if args[2] == "<NULL>" { None } else { Some(args[2].as_str()) };
        let uc: EarnCoinsUseCase<'_, Fake, String, String, String, String> =
            EarnCoinsUseCase::new(service, user_id);
        let p = "P".to_string();
        let r = match args[0].as_str() {
            "claimDailyAttunement" => uc.claim_daily_attunement(&p, 30),
            "reconcilePendingOfflineClaims" => uc.reconcile_pending_offline_claims(&p),
            "transferCoins" => uc.transfer_coins(&p, "friend@example.com", &bigdecimal::BigDecimal::from(5)),
            "purchaseArtifact" => uc.purchase_artifact(&p, &"I".to_string()),
            "equipItem" => uc.equip_item(&p, &"I".to_string()),
            "purchasePackage" => uc.purchase_package(&p, &"K".to_string()),
            "upgradeTier" => uc.upgrade_tier(&p, &"R".to_string()),
            "reviveStreak" => revive_streak(),
            other => panic!("{other}"),
        };
        assert_eq!(&vec![render(&r)], expected, "earn case {case} diverged from Kotlin");
    }
}
