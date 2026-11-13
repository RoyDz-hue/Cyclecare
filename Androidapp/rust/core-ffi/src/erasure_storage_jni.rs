//! Private same-connection callbacks; preserve exact pending Throwable and cutoff.
use jni::{JNIEnv,objects::{JObject,JValue},sys::jint};
use corelib::erasure_storage_executor::{self as driver,Backend};
struct Session<'a,'b,'local>{env:&'a mut JNIEnv<'local>,object:&'b JObject<'local>}
impl Backend for Session<'_, '_, '_>{type Error=jni::errors::Error;
fn ready(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"ready","()V",&[])?.v()}
fn global(&mut self,i:i32)->jni::errors::Result<()>{self.env.call_method(self.object,"global","(I)V",&[JValue::Int(i)])?.v()}
fn owned(&mut self,i:i32)->jni::errors::Result<()>{self.env.call_method(self.object,"owned","(I)V",&[JValue::Int(i)])?.v()}
fn receipts(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"receipts","()V",&[])?.v()}
fn exists(&mut self,i:i32)->jni::errors::Result<bool>{self.env.call_method(self.object,"exists","(I)Z",&[JValue::Int(i)])?.z()}
fn stage_delete(&mut self,i:i32)->jni::errors::Result<()>{self.env.call_method(self.object,"stageDelete","(I)V",&[JValue::Int(i)])?.v()}
fn defaults(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"defaults","()V",&[])?.v()}
}
pub fn execute<'local>(mut env:JNIEnv<'local>,object:JObject<'local>)->jint{
 match env.exception_check(){Ok(false)=>(),_=>return -1}
 let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||driver::execute(&mut Session{env:&mut env,object:&object})));
 match result{Ok(Ok(()))=>1,_=>{match env.exception_check(){Ok(false)=>{let _=env.throw_new("java/lang/IllegalStateException","erasure executor failed");},_=>()};-1}}
}
