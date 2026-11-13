//! Same-thread private callbacks. Original pending Throwable is never replaced.
use jni::{JNIEnv,objects::{JObject,JValue,JLongArray},sys::jint};
use corelib::resolution_storage_executor::{self as driver,Backend,Metadata,Error};
struct Session<'a,'b,'local>{env:&'a mut JNIEnv<'local>,object:&'b JObject<'local>}
impl Backend for Session<'_, '_, '_>{type Error=jni::errors::Error;
fn ready(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"ready","()V",&[])?.v()}
fn metadata(&mut self)->jni::errors::Result<Option<Metadata>>{let object=self.env.call_method(self.object,"metadata","()[J",&[])?.l()?;let array=JLongArray::from(object);let n=self.env.get_array_length(&array)?;if n==0{return Ok(None)}if n!=6{return Err(jni::errors::Error::NullPtr("resolution metadata shape"))}let mut v=[0i64;6];self.env.get_long_array_region(&array,0,&mut v)?;if [v[0],v[4],v[5]].iter().any(|x|!matches!(x,0|1)){return Err(jni::errors::Error::NullPtr("resolution metadata boolean"))}Ok(Some(Metadata{present:v[0]==1,revision:v[1],local:v[2],remote:v[3],tombstone:v[4]==1,retain:v[5]==1}))}
fn hash_matches(&mut self)->jni::errors::Result<bool>{self.env.call_method(self.object,"hashMatches","()Z",&[])?.z()}
fn skip(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"skip","()V",&[])?.v()}
fn prepare(&mut self,n:i64,r:bool)->jni::errors::Result<()>{self.env.call_method(self.object,"prepare","(JZ)V",&[JValue::Long(n),JValue::Bool(r as u8)])?.v()}
fn domain(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"domain","()V",&[])?.v()}
fn delete(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"delete","()V",&[])?.v()}
fn revision(&mut self,n:i64)->jni::errors::Result<()>{self.env.call_method(self.object,"revision","(J)V",&[JValue::Long(n)])?.v()}
fn outbox(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"outbox","()V",&[])?.v()}
fn resolve(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"resolve","()V",&[])?.v()}
}
pub fn execute<'local>(mut env:JNIEnv<'local>,object:JObject<'local>)->jint{
 match env.exception_check(){Ok(false)=>(),_=>return -1}
 let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||driver::execute(&mut Session{env:&mut env,object:&object})));
 match result{Ok(Ok(value))=>if value{1}else{0},other=>{match env.exception_check(){Ok(false)=>{let(class,message)=match other{Ok(Err(Error::Overflow))=>("java/lang/ArithmeticException","long overflow"),Ok(Err(Error::Rejected(message)))=>("com/cyclecare/app/data/local/rust/ConflictRejected",message),_=> ("java/lang/IllegalStateException","resolution executor failed")};let _=env.throw_new(class,message);},_=>()};-1}}
}
