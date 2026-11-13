//! Generic bounded batch same-connection synchronous driver. Platform owns the
//! admitted transaction/capability and exact typed SQL primitives.
#[derive(Debug,PartialEq,Eq)]pub enum Error<E>{Callback(E),Overflow,Invalid}
#[derive(Debug,PartialEq,Eq)]pub enum Outcome{Committed,Replay,InvalidReuse,Conflict}
pub trait Backend {
 type Error;
 fn receipt(&mut self)->Result<i32,Self::Error>;
 fn valid_read(&mut self,index:i32)->Result<bool,Self::Error>;
 fn valid(&mut self,index:i32)->Result<bool,Self::Error>;
 fn revision(&mut self,index:i32)->Result<i64,Self::Error>;
 fn server(&mut self,index:i32)->Result<i64,Self::Error>;
 fn domain(&mut self,index:i32)->Result<(),Self::Error>;
 fn revision_write(&mut self,index:i32,next:i64,server:i64)->Result<(),Self::Error>;
 fn outbox_write(&mut self,index:i32,next:i64,server:i64)->Result<(),Self::Error>;
 fn finish(&mut self)->Result<(),Self::Error>;
}
pub fn execute<B:Backend>(b:&mut B,reads:i32,mutations:i32)->Result<Outcome,Error<B::Error>> {
 if !(0..=16).contains(&reads)||!(1..=16).contains(&mutations){return Err(Error::Invalid)}
 match b.receipt().map_err(Error::Callback)? {0=>(),1=>return Ok(Outcome::Replay),2=>return Ok(Outcome::InvalidReuse),_=>return Err(Error::Invalid)}
 for i in 0..reads {if !b.valid_read(i).map_err(Error::Callback)?{return Ok(Outcome::Conflict)}}
 for i in 0..mutations {if !b.valid(i).map_err(Error::Callback)?{return Ok(Outcome::Conflict)}}
 let mut next=[0;16];for i in 0..mutations {let v=b.revision(i).map_err(Error::Callback)?;if v<0{return Err(Error::Invalid)}next[i as usize]=v.checked_add(1).ok_or(Error::Overflow)?;}
 for i in 0..mutations {let server=b.server(i).map_err(Error::Callback)?;if server<0{return Err(Error::Invalid)}b.domain(i).map_err(Error::Callback)?;b.revision_write(i,next[i as usize],server).map_err(Error::Callback)?;b.outbox_write(i,next[i as usize],server).map_err(Error::Callback)?;}
 b.finish().map_err(Error::Callback)?;Ok(Outcome::Committed)
}
#[cfg(test)] mod tests {
 use super::*;
 struct Probe{trace:Vec<String>,cut:usize,receipt:i32,invalid:i32,overflow:i32}
 impl Probe{fn new()->Self{Self{trace:vec![],cut:0,receipt:0,invalid:-1,overflow:-1}}fn hit(&mut self,s:String)->Result<(),usize>{self.trace.push(s);if self.trace.len()==self.cut{Err(self.cut)}else{Ok(())}}}
 impl Backend for Probe{type Error=usize;
 fn receipt(&mut self)->Result<i32,usize>{self.hit("receipt".into())?;Ok(self.receipt)}
 fn valid_read(&mut self,i:i32)->Result<bool,usize>{self.hit(format!("read{i}"))?;Ok(i!=self.invalid)}
 fn valid(&mut self,i:i32)->Result<bool,usize>{self.hit(format!("valid{i}"))?;Ok(i!=self.invalid)}
 fn revision(&mut self,i:i32)->Result<i64,usize>{self.hit(format!("revision{i}"))?;Ok(if i==self.overflow{i64::MAX}else{i as i64})}
 fn server(&mut self,i:i32)->Result<i64,usize>{self.hit(format!("server{i}"))?;Ok(7+i as i64)}
 fn domain(&mut self,i:i32)->Result<(),usize>{self.hit(format!("domain{i}"))}
 fn revision_write(&mut self,i:i32,n:i64,s:i64)->Result<(),usize>{assert_eq!(n,i as i64+1);assert_eq!(s,i as i64+7);self.hit(format!("write{i}"))}
 fn outbox_write(&mut self,i:i32,n:i64,s:i64)->Result<(),usize>{assert_eq!(n,i as i64+1);assert_eq!(s,i as i64+7);self.hit(format!("outbox{i}"))}
 fn finish(&mut self)->Result<(),usize>{self.hit("finish".into())}}
 #[test]fn all_272_shapes_exact_order_and_each_error_cutoff(){for reads in 0..=16{for count in 1..=16{let mut p=Probe::new();assert_eq!(execute(&mut p,reads,count),Ok(Outcome::Committed));let mut expected=vec!["receipt".to_string()];for i in 0..reads{expected.push(format!("read{i}"));}for i in 0..count{expected.push(format!("valid{i}"));}for i in 0..count{expected.push(format!("revision{i}"));}for i in 0..count{for name in ["server","domain","write","outbox"]{expected.push(format!("{name}{i}"));}}expected.push("finish".into());assert_eq!(p.trace,expected);for cut in 1..=expected.len(){let mut q=Probe::new();q.cut=cut;assert_eq!(execute(&mut q,reads,count),Err(Error::Callback(cut)));assert_eq!(q.trace,expected[..cut]);}}}}
 #[test]fn invalid_counts_no_callback_and_receipt_shortcircuit(){for(reads,count)in[(-1,1),(17,1),(0,0),(0,17)]{let mut p=Probe::new();assert_eq!(execute(&mut p,reads,count),Err(Error::Invalid));assert!(p.trace.is_empty());}for(code,expected)in[(1,Ok(Outcome::Replay)),(2,Ok(Outcome::InvalidReuse)),(3,Err(Error::Invalid))]{let mut p=Probe::new();p.receipt=code;assert_eq!(execute(&mut p,16,16),expected);assert_eq!(p.trace,vec!["receipt"]);}}
 #[test]fn every_dependency_and_mutation_conflict_or_overflow_stops_before_effects(){for i in 0..16{for reads in [0,16]{let mut p=Probe::new();p.invalid=i;assert_eq!(execute(&mut p,reads,16),Ok(Outcome::Conflict));assert!(!p.trace.iter().any(|s|s.starts_with("revision")||s.starts_with("server")));}let mut p=Probe::new();p.overflow=i;assert_eq!(execute(&mut p,16,16),Err(Error::Overflow));assert!(!p.trace.iter().any(|s|s.starts_with("server")||s.starts_with("domain")));}}
}
