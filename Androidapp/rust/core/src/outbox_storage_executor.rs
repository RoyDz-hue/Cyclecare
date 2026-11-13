//! ACK/retry sequence driver. Same-thread admitted platform session owns exact
//! typed SQL/CAS. Conflict/pull-page remain on the original path.
#[derive(Debug,PartialEq,Eq)]pub enum Error<E>{Callback(E),Overflow,Invalid}
#[derive(Debug,PartialEq,Eq)]pub enum Mode{Ack,Retry}
pub trait Backend{
 type Error;
 fn ready(&mut self)->Result<(),Self::Error>;
 fn base(&mut self)->Result<i64,Self::Error>;
 fn acknowledged(&mut self)->Result<i64,Self::Error>;
 fn attempts(&mut self)->Result<i32,Self::Error>;
 fn matches(&mut self)->Result<bool,Self::Error>;
 fn max_server(&mut self,server:i64)->Result<(),Self::Error>;
 fn delete_acked(&mut self)->Result<(),Self::Error>;
 fn rebase(&mut self,server:i64)->Result<(),Self::Error>;
 fn retry_cas(&mut self,next:i32)->Result<bool,Self::Error>;
}
pub fn execute<B:Backend>(b:&mut B,mode:Mode)->Result<bool,Error<B::Error>>{
 b.ready().map_err(Error::Callback)?;
 match mode{
 Mode::Ack=>{
  let base=b.base().map_err(Error::Callback)?;
  let next=base.checked_add(1).ok_or(Error::Overflow)?;
  let server=b.acknowledged().map_err(Error::Callback)?;
  if server!=next{return Err(Error::Invalid)}
  if !b.matches().map_err(Error::Callback)?{return Ok(false)}
  b.max_server(server).map_err(Error::Callback)?;
  b.delete_acked().map_err(Error::Callback)?;
  b.rebase(server).map_err(Error::Callback)?;
  Ok(true)
 },Mode::Retry=>{
  if !b.matches().map_err(Error::Callback)?{return Ok(false)}
  let attempts=b.attempts().map_err(Error::Callback)?;
  let next=attempts.checked_add(1).ok_or(Error::Overflow)?;
  b.retry_cas(next).map_err(Error::Callback)
 }}
}
#[cfg(test)]mod tests{
 use super::*;
 struct Probe{trace:Vec<&'static str>,cut:usize,base:i64,server:i64,attempts:i32,matches:bool,cas:bool}
 impl Probe{fn new()->Self{Self{trace:vec![],cut:0,base:7,server:8,attempts:2,matches:true,cas:true}}fn hit(&mut self,s:&'static str)->Result<(),usize>{self.trace.push(s);if self.trace.len()==self.cut{Err(self.cut)}else{Ok(())}}}
 impl Backend for Probe{type Error=usize;
 fn ready(&mut self)->Result<(),usize>{self.hit("ready")}
 fn base(&mut self)->Result<i64,usize>{self.hit("base")?;Ok(self.base)}
 fn acknowledged(&mut self)->Result<i64,usize>{self.hit("acknowledged")?;Ok(self.server)}
 fn attempts(&mut self)->Result<i32,usize>{self.hit("attempts")?;Ok(self.attempts)}
 fn matches(&mut self)->Result<bool,usize>{self.hit("matches")?;Ok(self.matches)}
 fn max_server(&mut self,s:i64)->Result<(),usize>{assert_eq!(s,self.server);self.hit("max")}
 fn delete_acked(&mut self)->Result<(),usize>{self.hit("delete")}
 fn rebase(&mut self,s:i64)->Result<(),usize>{assert_eq!(s,self.server);self.hit("rebase")}
 fn retry_cas(&mut self,n:i32)->Result<bool,usize>{assert_eq!(n,self.attempts+1);self.hit("retry")?;Ok(self.cas)}}
 #[test]fn exact_order_and_all_callback_cutoffs(){for(mode,expected)in[(Mode::Ack,vec!["ready","base","acknowledged","matches","max","delete","rebase"]),(Mode::Retry,vec!["ready","matches","attempts","retry"])]{let mut p=Probe::new();assert_eq!(execute(&mut p,mode),Ok(true));assert_eq!(p.trace,expected);for cut in 1..=expected.len(){let mut q=Probe::new();q.cut=cut;let mode=if expected.len()==7{Mode::Ack}else{Mode::Retry};assert_eq!(execute(&mut q,mode),Err(Error::Callback(cut)));assert_eq!(q.trace,expected[..cut]);}}}
 #[test]fn ack_original_checked_add_and_exact_receipt_before_matches(){for base in [i64::MIN,-1,0,i64::MAX-1]{let mut p=Probe::new();p.base=base;p.server=base+1;assert_eq!(execute(&mut p,Mode::Ack),Ok(true));}let mut p=Probe::new();p.base=i64::MAX;assert_eq!(execute(&mut p,Mode::Ack),Err(Error::Overflow));assert_eq!(p.trace,vec!["ready","base"]);let mut p=Probe::new();p.server=9;p.matches=false;assert_eq!(execute(&mut p,Mode::Ack),Err(Error::Invalid));assert_eq!(p.trace,vec!["ready","base","acknowledged"]);let mut p=Probe::new();p.matches=false;assert_eq!(execute(&mut p,Mode::Ack),Ok(false));assert_eq!(p.trace,vec!["ready","base","acknowledged","matches"]);}
 #[test]fn retry_match_before_overflow_then_exact_cas_result(){let mut p=Probe::new();p.matches=false;p.attempts=i32::MAX;assert_eq!(execute(&mut p,Mode::Retry),Ok(false));assert_eq!(p.trace,vec!["ready","matches"]);let mut p=Probe::new();p.attempts=i32::MAX;assert_eq!(execute(&mut p,Mode::Retry),Err(Error::Overflow));assert_eq!(p.trace,vec!["ready","matches","attempts"]);for attempts in [i32::MIN,-1,0,i32::MAX-1]{for cas in [false,true]{let mut p=Probe::new();p.attempts=attempts;p.cas=cas;assert_eq!(execute(&mut p,Mode::Retry),Ok(cas));}}}
}
