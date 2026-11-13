//! Same-thread operation-local callbacks, pending Throwable preserved.
use jni::{JNIEnv,objects::{JObject,JValue},sys::jint};
use corelib::remote_storage_executor::{self as driver,Backend,Error};
struct Session<'a,'b,'local>{env:&'a mut JNIEnv<'local>,object:&'b JObject<'local>}
impl Backend for Session<'_, '_, '_>{type Error=jni::errors::Error;
fn ready(&mut self)->jni::errors::Result<i32>{self.env.call_method(self.object,"ready","()I",&[])?.i()}
fn cursor(&mut self)->jni::errors::Result<i64>{self.env.call_method(self.object,"cursor","()J",&[])?.j()}
fn remote(&mut self,i:i32)->jni::errors::Result<i64>{self.env.call_method(self.object,"remote","(I)J",&[JValue::Int(i)])?.j()}
fn sequence(&mut self,i:i32)->jni::errors::Result<i64>{self.env.call_method(self.object,"sequence","(I)J",&[JValue::Int(i)])?.j()}
fn inspect(&mut self,i:i32)->jni::errors::Result<()>{self.env.call_method(self.object,"inspect","(I)V",&[JValue::Int(i)])?.v()}
fn server(&mut self,i:i32)->jni::errors::Result<i64>{self.env.call_method(self.object,"server","(I)J",&[JValue::Int(i)])?.j()}
fn pending(&mut self,i:i32)->jni::errors::Result<bool>{self.env.call_method(self.object,"pending","(I)Z",&[JValue::Int(i)])?.z()}
fn conflict(&mut self,i:i32)->jni::errors::Result<()>{self.env.call_method(self.object,"conflict","(I)V",&[JValue::Int(i)])?.v()}
fn prepare(&mut self,i:i32)->jni::errors::Result<()>{self.env.call_method(self.object,"prepare","(I)V",&[JValue::Int(i)])?.v()}
fn local(&mut self,i:i32)->jni::errors::Result<i64>{self.env.call_method(self.object,"local","(I)J",&[JValue::Int(i)])?.j()}
fn domain(&mut self,i:i32)->jni::errors::Result<()>{self.env.call_method(self.object,"domain","(I)V",&[JValue::Int(i)])?.v()}
fn revision(&mut self,i:i32,n:i64)->jni::errors::Result<()>{self.env.call_method(self.object,"revision","(IJ)V",&[JValue::Int(i),JValue::Long(n)])?.v()}
fn advance(&mut self,i:i32,n:i64)->jni::errors::Result<()>{self.env.call_method(self.object,"advance","(IJ)V",&[JValue::Int(i),JValue::Long(n)])?.v()}
}
pub fn execute<'local>(mut env:JNIEnv<'local>,object:JObject<'local>)->jint{
 match env.exception_check(){Ok(false)=>(),_=>return -1}
 let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||driver::execute(&mut Session{env:&mut env,object:&object})));
 match result{Ok(Ok(value))=>value,other=>{match env.exception_check(){Ok(false)=>{let(class,message)=match other{Ok(Err(Error::Overflow))=>("java/lang/ArithmeticException","long overflow"),Ok(Err(Error::Invalid))=>("java/lang/IllegalArgumentException","Failed requirement."),_=>("java/lang/IllegalStateException","remote executor failed")};let _=env.throw_new(class,message);},_=>()};-1}}
}
