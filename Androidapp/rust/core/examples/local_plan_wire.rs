//! Local synthetic stdin/stdout harness for cross-language contract tests.
use std::io::Read;
fn main(){let mut s=String::new();std::io::stdin().read_to_string(&mut s).unwrap();match cyclecare_core::local_plan_wire::plan(&s){Ok(v)=>println!("{v}"),Err(e)=>{eprintln!("{e}");std::process::exit(1)}}}
