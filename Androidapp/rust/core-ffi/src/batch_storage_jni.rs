//! Operation-local synchronous JNI callback adapter. On JavaException the
//! original pending Throwable is left intact and no further callback is made.
use jni::{JNIEnv,objects::{JObject,JValue},sys::jint};
use corelib::batch_storage_executor::{self as driver,Backend,Error,Outcome};
struct Session<'a,'b,'local>{env:&'a mut JNIEnv<'local>,object:&'b JObject<'local>}
impl Session<'_, '_, '_>{
 fn int(&mut self,name:&str,args:&[JValue])->jni::errors::Result<i32>{self.env.call_method(self.object,name,"()I",args)?.i()}
 fn indexed_bool(&mut self,name:&str,index:i32)->jni::errors::Result<bool>{self.env.call_method(self.object,name,"(I)Z",&[JValue::Int(index)])?.z()}
 fn indexed_long(&mut self,name:&str,index:i32)->jni::errors::Result<i64>{self.env.call_method(self.object,name,"(I)J",&[JValue::Int(index)])?.j()}
}
impl Backend for Session<'_, '_, '_>{type Error=jni::errors::Error;
 fn receipt(&mut self)->jni::errors::Result<i32>{self.int("receipt",&[])}
 fn valid_read(&mut self,i:i32)->jni::errors::Result<bool>{self.indexed_bool("validRead",i)}
 fn valid(&mut self,i:i32)->jni::errors::Result<bool>{self.indexed_bool("valid",i)}
 fn revision(&mut self,i:i32)->jni::errors::Result<i64>{self.indexed_long("revision",i)}
 fn server(&mut self,i:i32)->jni::errors::Result<i64>{self.indexed_long("server",i)}
 fn domain(&mut self,i:i32)->jni::errors::Result<()>{self.env.call_method(self.object,"domain","(I)V",&[JValue::Int(i)])?.v()}
 fn revision_write(&mut self,i:i32,n:i64,s:i64)->jni::errors::Result<()>{self.env.call_method(self.object,"revisionWrite","(IJJ)V",&[JValue::Int(i),JValue::Long(n),JValue::Long(s)])?.v()}
 fn outbox_write(&mut self,i:i32,n:i64,s:i64)->jni::errors::Result<()>{self.env.call_method(self.object,"outboxWrite","(IJJ)V",&[JValue::Int(i),JValue::Long(n),JValue::Long(s)])?.v()}
 fn finish(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"finish","()V",&[])?.v()}
}
pub fn execute<'local>(mut env:JNIEnv<'local>,object:JObject<'local>,reads:jint,mutations:jint)->jint {
 // No user code runs if a Throwable is already pending.
 match env.exception_check(){Ok(false)=>(),_=>return -1}
 let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||driver::execute(&mut Session{env:&mut env,object:&object},reads,mutations)));
 match result {
  Ok(Ok(Outcome::Committed))=>0,Ok(Ok(Outcome::Replay))=>1,Ok(Ok(Outcome::InvalidReuse))=>2,Ok(Ok(Outcome::Conflict))=>3,
  other=>{
   // Do not replace or clear a callback Throwable. Error checking is not a
   // callback and never performs domain work. All other errors throw and are
   // rolled back by the existing admitted host transaction.
   match env.exception_check(){Ok(false)=>{let (class,message)=match other{Ok(Err(Error::Overflow))=>("java/lang/ArithmeticException","long overflow"),_=>("java/lang/IllegalStateException","batch native executor failed")};let _=env.throw_new(class,message);},_=>()};-1
  }
 }
}
