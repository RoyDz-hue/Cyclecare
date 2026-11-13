//! Pure post-pass reducer for the active RustPortRecovery coordinator.
//! JVM retains locks, generation capabilities, coroutine cancellation and effects.
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum Action {Stop,Prompt,Delayed}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub struct Decision {pub action:Action,pub round:i32,pub pending:bool,pub in_flight:bool}
/// Inputs are the counters AFTER this pass's wrapping increments, matching JVM.
pub fn after_pass(round:i32,absolute:i32,max_rounds:i32,hard_budget:i32,pending:bool,recoverable:bool)->Decision {
 if absolute>=hard_budget {Decision{action:Action::Stop,round,pending:false,in_flight:false}}
 else if pending {Decision{action:Action::Prompt,round:0,pending:false,in_flight:true}}
 else if recoverable && round<max_rounds {Decision{action:Action::Delayed,round,pending:false,in_flight:true}}
 else {Decision{action:Action::Stop,round,pending:false,in_flight:false}}
}
#[cfg(test)]
mod tests {
 use super::*;
 #[test] fn hard_budget_preempts_pending_and_recovery(){let d=after_pass(1,20,5,20,true,true);assert_eq!(d.action,Action::Stop);assert!(!d.pending && !d.in_flight);assert_eq!(d.round,1);}
 #[test] fn pending_resets_episode_only(){let d=after_pass(5,19,5,20,true,false);assert_eq!(d,Decision{action:Action::Prompt,round:0,pending:false,in_flight:true});}
 #[test] fn recovery_bounded_and_terminal_settles(){assert_eq!(after_pass(4,4,5,20,false,true).action,Action::Delayed);assert_eq!(after_pass(5,5,5,20,false,true).action,Action::Stop);assert_eq!(after_pass(1,1,5,20,false,false).action,Action::Stop);}
 #[test] fn full_i32_boundaries_preserve_original_comparisons(){for round in [i32::MIN,-1,0,1,i32::MAX] {for absolute in [i32::MIN,-1,0,1,i32::MAX] {for max in [i32::MIN,0,5,i32::MAX] {for budget in [i32::MIN,0,20,i32::MAX] {for pending in [false,true] {for recoverable in [false,true] {let d=after_pass(round,absolute,max,budget,pending,recoverable);assert_eq!(d.action,if absolute>=budget {Action::Stop} else if pending {Action::Prompt} else if recoverable&&round<max {Action::Delayed} else {Action::Stop});}}}}}}}
}
