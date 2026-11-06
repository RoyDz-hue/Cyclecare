//! Guard-chain and event-reducer pins for NetworkHelper.kt.
use cyclecare_core::network_helper::{is_online, NetworkEvent, NetworkObservation, NetworkQuery};

#[test]
fn is_online_guard_chain_matrix() {
    assert!(!is_online(NetworkQuery::NoConnectivityManager), ":16-17");
    assert!(!is_online(NetworkQuery::NoActiveNetwork), ":18");
    assert!(!is_online(NetworkQuery::NoCapabilities), ":19");
    assert!(is_online(NetworkQuery::Caps { internet: true, validated: true }), ":21-22 both required");
    assert!(!is_online(NetworkQuery::Caps { internet: true, validated: false }));
    assert!(!is_online(NetworkQuery::Caps { internet: false, validated: true }));
    assert!(!is_online(NetworkQuery::Caps { internet: false, validated: false }));
}

#[test]
fn distinct_until_changed_dedups_repeats() {
    let mut o = NetworkObservation::new();
    let online = NetworkQuery::Caps { internet: true, validated: true };
    assert_eq!(o.initial(online), Some(true), "initial trySend(isOnline) :34");
    assert_eq!(o.on_event(NetworkEvent::Available), None, "true again - deduped by :63");
    assert_eq!(o.on_event(NetworkEvent::Available), None);
    assert_eq!(o.on_event(NetworkEvent::Lost(NetworkQuery::NoActiveNetwork)), Some(false), "onLost re-evaluates the FULL guard chain :40-42");
    assert_eq!(o.on_event(NetworkEvent::Lost(NetworkQuery::NoActiveNetwork)), None);
}

#[test]
fn capabilities_changed_uses_only_the_given_flags() {
    // :44-48 asymmetry: onCapabilitiesChanged does NOT re-check
    // activeNetwork - a caps event carrying both flags emits true even
    // right after a Lost that evaluated false.
    let mut o = NetworkObservation::new();
    assert_eq!(o.on_event(NetworkEvent::Lost(NetworkQuery::NoActiveNetwork)), Some(false));
    assert_eq!(o.on_event(NetworkEvent::CapabilitiesChanged { internet: true, validated: true }), Some(true));
    assert_eq!(o.on_event(NetworkEvent::CapabilitiesChanged { internet: true, validated: false }), Some(false), "VALIDATED missing -> false");
    assert_eq!(o.on_event(NetworkEvent::CapabilitiesChanged { internet: false, validated: true }), None, "INTERNET missing -> also false, but deduped against the just-emitted false (:63)");
}

#[test]
fn lost_reevaluation_can_emit_true() {
    // onLost fires when A network was lost; another may still be validated.
    let mut o = NetworkObservation::new();
    assert_eq!(o.initial(NetworkQuery::Caps { internet: true, validated: true }), Some(true));
    assert_eq!(o.on_event(NetworkEvent::Lost(NetworkQuery::Caps { internet: true, validated: true })), None, "still online via the remaining network - deduped");
    assert_eq!(o.on_event(NetworkEvent::Lost(NetworkQuery::NoCapabilities)), Some(false));
}
