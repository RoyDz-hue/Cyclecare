//! Settings-only same-connection synchronous driver. Platform owns the
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
 if !(0..=1).contains(&reads)||!(1..=2).contains(&mutations){return Err(Error::Invalid)}
 match b.receipt().map_err(Error::Callback)? {0=>(),1=>return Ok(Outcome::Replay),2=>return Ok(Outcome::InvalidReuse),_=>return Err(Error::Invalid)}
 for i in 0..reads {if !b.valid_read(i).map_err(Error::Callback)?{return Ok(Outcome::Conflict)}}
 for i in 0..mutations {if !b.valid(i).map_err(Error::Callback)?{return Ok(Outcome::Conflict)}}
 let mut next=[0;2];for i in 0..mutations {let v=b.revision(i).map_err(Error::Callback)?;if v<0{return Err(Error::Invalid)}next[i as usize]=v.checked_add(1).ok_or(Error::Overflow)?;}
 for i in 0..mutations {let server=b.server(i).map_err(Error::Callback)?;if server<0{return Err(Error::Invalid)}b.domain(i).map_err(Error::Callback)?;b.revision_write(i,next[i as usize],server).map_err(Error::Callback)?;b.outbox_write(i,next[i as usize],server).map_err(Error::Callback)?;}
 b.finish().map_err(Error::Callback)?;Ok(Outcome::Committed)
}
