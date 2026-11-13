//! Synchronous operation-local ACK/retry driver, preserves pending Throwable.
use jni::{JNIEnv,objects::{JObject,JValue},sys::jint};
use corelib::outbox_storage_executor::{self as driver,Backend,Error,Mode};
struct Session<'a,'b,'local>{env:&'a mut JNIEnv<'local>,object:&'b JObject<'local>}
impl Backend for Session<'_, '_, '_>{type Error=jni::errors::Error;
 fn ready(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"ready","()V",&[])?.v()}
 fn base(&mut self)->jni::errors::Result<i64>{self.env.call_method(self.object,"base","()J",&[])?.j()}
 fn acknowledged(&mut self)->jni::errors::Result<i64>{self.env.call_method(self.object,"acknowledged","()J",&[])?.j()}
 fn attempts(&mut self)->jni::errors::Result<i32>{self.env.call_method(self.object,"attempts","()I",&[])?.i()}
 fn matches(&mut self)->jni::errors::Result<bool>{self.env.call_method(self.object,"matches","()Z",&[])?.z()}
 fn max_server(&mut self,s:i64)->jni::errors::Result<()>{self.env.call_method(self.object,"maxServer","(J)V",&[JValue::Long(s)])?.v()}
 fn delete_acked(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"deleteAcked","()V",&[])?.v()}
 fn rebase(&mut self,s:i64)->jni::errors::Result<()>{self.env.call_method(self.object,"rebase","(J)V",&[JValue::Long(s)])?.v()}
 fn retry_cas(&mut self,n:i32)->jni::errors::Result<bool>{self.env.call_method(self.object,"retryCas","(I)Z",&[JValue::Int(n)])?.z()}
}
pub fn execute<'local>(mut env:JNIEnv<'local>,object:JObject<'local>,mode:jint)->jint{
 match env.exception_check(){Ok(false)=>(),_=>return -1}
 let overflow_message=if mode==0{"long overflow"}else{"integer overflow"};
 let mode=match mode{0=>Mode::Ack,1=>Mode::Retry,_=>{let _=env.throw_new("java/lang/IllegalArgumentException","invalid outbox mode");return -1}};
 let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||driver::execute(&mut Session{env:&mut env,object:&object},mode)));
 match result{Ok(Ok(value))=>if value{1}else{0},other=>{match env.exception_check(){Ok(false)=>{let(class,message)=match other{Ok(Err(Error::Overflow))=>("java/lang/ArithmeticException",overflow_message),Ok(Err(Error::Invalid))=>("java/lang/IllegalArgumentException","Failed requirement."),_=>("java/lang/IllegalStateException","outbox executor failed")};let _=env.throw_new(class,message);},_=>()};-1}}
}
