//! Completion-only same-connection synchronous driver. Platform owns the
//! admitted transaction/capability and exact typed SQL primitives.
#[derive(Debug,PartialEq,Eq)]pub enum Error<E>{Callback(E),Overflow,Invalid}
#[derive(Debug,PartialEq,Eq)]pub enum Outcome{Committed,Replay,InvalidReuse,Conflict}
pub trait Backend {
 type Error;
 fn receipt(&mut self)->Result<i32,Self::Error>;
 fn valid(&mut self,index:i32)->Result<bool,Self::Error>;
 fn revision(&mut self,index:i32)->Result<i64,Self::Error>;
 fn server(&mut self,index:i32)->Result<i64,Self::Error>;
 fn domain(&mut self,index:i32)->Result<(),Self::Error>;
 fn revision_write(&mut self,index:i32,next:i64,server:i64)->Result<(),Self::Error>;
 fn outbox_write(&mut self,index:i32,next:i64,server:i64)->Result<(),Self::Error>;
 fn finish(&mut self,a:i64,b:i64)->Result<(),Self::Error>;
}
pub fn execute<B:Backend>(b:&mut B)->Result<Outcome,Error<B::Error>> {
 match b.receipt().map_err(Error::Callback)? {0=>(),1=>return Ok(Outcome::Replay),2=>return Ok(Outcome::InvalidReuse),_=>return Err(Error::Invalid)}
 for i in 0..2 {if !b.valid(i).map_err(Error::Callback)?{return Ok(Outcome::Conflict)}}
 let mut next=[0;2];for i in 0..2 {let v=b.revision(i).map_err(Error::Callback)?;if v<0{return Err(Error::Invalid)}next[i as usize]=v.checked_add(1).ok_or(Error::Overflow)?;}
 for i in 0..2 {let server=b.server(i).map_err(Error::Callback)?;if server<0{return Err(Error::Invalid)}b.domain(i).map_err(Error::Callback)?;b.revision_write(i,next[i as usize],server).map_err(Error::Callback)?;b.outbox_write(i,next[i as usize],server).map_err(Error::Callback)?;}
 b.finish(next[0],next[1]).map_err(Error::Callback)?;Ok(Outcome::Committed)
}
#[cfg(test)]mod tests {
 use super::*;
 struct B{trace:Vec<String>,fail:Option<usize>,receipt:i32,valid:[bool;2],rev:[i64;2]}
 impl B{fn new()->Self{Self{trace:vec![],fail:None,receipt:0,valid:[true,true],rev:[4,5]}}fn hit(&mut self,s:String)->Result<(),usize>{self.trace.push(s);if self.fail==Some(self.trace.len()){Err(self.trace.len())}else{Ok(())}}}
 impl Backend for B{type Error=usize;
 fn receipt(&mut self)->Result<i32,usize>{self.hit("receipt".into())?;Ok(self.receipt)}
 fn valid(&mut self,i:i32)->Result<bool,usize>{self.hit(format!("valid{i}"))?;Ok(self.valid[i as usize])}
 fn revision(&mut self,i:i32)->Result<i64,usize>{self.hit(format!("revision{i}"))?;Ok(self.rev[i as usize])}
 fn server(&mut self,i:i32)->Result<i64,usize>{self.hit(format!("server{i}"))?;Ok(13+i as i64)}
 fn domain(&mut self,i:i32)->Result<(),usize>{self.hit(format!("domain{i}"))}
 fn revision_write(&mut self,i:i32,n:i64,s:i64)->Result<(),usize>{self.hit(format!("writeRevision{i}:{n}:{s}"))}
 fn outbox_write(&mut self,i:i32,n:i64,s:i64)->Result<(),usize>{self.hit(format!("outbox{i}:{n}:{s}"))}
 fn finish(&mut self,a:i64,b:i64)->Result<(),usize>{self.hit(format!("finish:{a}:{b}"))}}
 #[test]fn exact_driver_order_and_each_callback_error_stops_without_later_callback(){let mut b=B::new();assert_eq!(execute(&mut b),Ok(Outcome::Committed));let expected=["receipt","valid0","valid1","revision0","revision1","server0","domain0","writeRevision0:5:13","outbox0:5:13","server1","domain1","writeRevision1:6:14","outbox1:6:14","finish:5:6"].map(String::from);assert_eq!(b.trace,expected);for cut in 1..=expected.len(){let mut b=B::new();b.fail=Some(cut);assert_eq!(execute(&mut b),Err(Error::Callback(cut)));assert_eq!(b.trace,expected[..cut]);}}
 #[test]fn replay_invalid_conflict_and_all_overflow_zero_write_paths(){for code in [1,2,3]{let mut b=B::new();b.receipt=code;let result=execute(&mut b);assert_eq!(b.trace,["receipt"]);assert_eq!(result,match code{1=>Ok(Outcome::Replay),2=>Ok(Outcome::InvalidReuse),_=>Err(Error::Invalid)});}for i in 0..2{let mut b=B::new();b.valid[i]=false;assert_eq!(execute(&mut b),Ok(Outcome::Conflict));assert_eq!(b.trace.len(),2+i);let mut b=B::new();b.rev[i]=i64::MAX;assert_eq!(execute(&mut b),Err(Error::Overflow));assert!(b.trace.iter().all(|s|!s.starts_with("server")&&!s.starts_with("domain")));}}
}
