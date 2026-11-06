//! Port of domain/economy/EarnCoinsUseCase.kt + MiningUseCase.kt
//! (main@31b88f5): delegation + error vocabulary over the economy service
//! seam. The service itself (SupabaseEconomyService: transport, session,
//! prefs) is the lifecycle-integration surface; this module owns exactly
//! what the use cases decide. EconomyResult shape verbatim:
//! Success(data, message="") / Error(errorMessage).
//!
//! Spec §48 (source comment): streak mechanics forbidden; reviveStreak
//! returns the fixed deprecation error, verbatim.

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EconomyResult<T> {
    Success(T, String),
    Error(String),
}

/// The service seam. Each method mirrors a SupabaseEconomyService entry
/// point; profiles and items pass through opaquely (P, I, K, R).
pub trait EconomyService<P, I, K, R> {
    fn verify_and_mine_coins(&self, user_id: &str, profile: &P, base_reward: i64) -> EconomyResult<P>;
    fn reconcile_pending_offline_claims(&self, user_id: &str, profile: &P) -> EconomyResult<P>;
    fn transfer_coins(&self, profile: &P, recipient_email: &str, amount: &bigdecimal::BigDecimal) -> EconomyResult<P>;
    fn purchase_artifact(&self, profile: &P, item: &I) -> EconomyResult<P>;
    fn equip_item(&self, profile: &P, item: &I) -> EconomyResult<P>;
    fn purchase_coin_package(&self, profile: &P, pkg: &K) -> EconomyResult<P>;
    fn upgrade_tier(&self, profile: &P, tier: &R) -> EconomyResult<P>;
}

/// EarnCoinsUseCase. `effective_user_id` is AuthPreferences.effectiveUserId;
/// None mirrors a null AuthPreferences ("local_user" fallback).
pub struct EarnCoinsUseCase<'a, S: EconomyService<P, I, K, R>, P, I, K, R> {
    pub service: Option<&'a S>,
    pub effective_user_id: Option<&'a str>,
    _marker: std::marker::PhantomData<(P, I, K, R)>,
}

impl<'a, S: EconomyService<P, I, K, R>, P: Clone, I, K, R> EarnCoinsUseCase<'a, S, P, I, K, R> {
    pub fn new(service: Option<&'a S>, effective_user_id: Option<&'a str>) -> Self {
        EarnCoinsUseCase { service, effective_user_id, _marker: std::marker::PhantomData }
    }

    fn user_id(&self) -> &str {
        self.effective_user_id.unwrap_or("local_user")
    }

    pub fn claim_daily_attunement(&self, current_profile: &P, base_reward: i64) -> EconomyResult<P> {
        let Some(service) = self.service else {
            return EconomyResult::Error("Service unavailable".to_string());
        };
        service.verify_and_mine_coins(self.user_id(), current_profile, base_reward)
    }

    pub fn reconcile_pending_offline_claims(&self, current_profile: &P) -> EconomyResult<P> {
        let Some(service) = self.service else {
            return EconomyResult::Success(current_profile.clone(), String::new());
        };
        service.reconcile_pending_offline_claims(self.user_id(), current_profile)
    }

    pub fn transfer_coins(
        &self,
        current_profile: &P,
        recipient_email: &str,
        amount: &bigdecimal::BigDecimal,
    ) -> EconomyResult<P> {
        match self.service {
            Some(s) => s.transfer_coins(current_profile, recipient_email, amount),
            None => EconomyResult::Error("Economy service offline".to_string()),
        }
    }

    pub fn purchase_artifact(&self, current_profile: &P, item: &I) -> EconomyResult<P> {
        match self.service {
            Some(s) => s.purchase_artifact(current_profile, item),
            None => EconomyResult::Error("Economy service offline".to_string()),
        }
    }

    pub fn equip_item(&self, current_profile: &P, item: &I) -> EconomyResult<P> {
        match self.service {
            Some(s) => s.equip_item(current_profile, item),
            None => EconomyResult::Error("Economy service offline".to_string()),
        }
    }

    pub fn purchase_package(&self, current_profile: &P, pkg: &K) -> EconomyResult<P> {
        match self.service {
            Some(s) => s.purchase_coin_package(current_profile, pkg),
            None => EconomyResult::Error("Economy service offline".to_string()),
        }
    }

    pub fn upgrade_tier(&self, current_profile: &P, tier: &R) -> EconomyResult<P> {
        match self.service {
            Some(s) => s.upgrade_tier(current_profile, tier),
            None => EconomyResult::Error("Economy service offline".to_string()),
        }
    }
}

/// MiningUseCase.reviveStreak (deprecated path): fixed error, verbatim.
pub const REVIVE_STREAK_MESSAGE: &str =
    "Streak mechanics are deprecated per spec §48. Your daily rewards are always available in the Hub.";

pub fn revive_streak<P>() -> EconomyResult<P> {
    EconomyResult::Error(REVIVE_STREAK_MESSAGE.to_string())
}
