//! Synchronous private session callbacks, original Throwable stays pending.
use jni::{JNIEnv,objects::JObject,sys::jint};
use corelib::conflict_storage_executor::{self as driver,Backend};
struct Session<'a,'b,'local>{env:&'a mut JNIEnv<'local>,object:&'b JObject<'local>}
impl Backend for Session<'_, '_, '_>{type Error=jni::errors::Error;
 fn ready(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"ready","()V",&[])?.v()}
 fn matches(&mut self)->jni::errors::Result<bool>{self.env.call_method(self.object,"matches","()Z",&[])?.z()}
 fn latest(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"latest","()V",&[])?.v()}
 fn write(&mut self)->jni::errors::Result<()>{self.env.call_method(self.object,"write","()V",&[])?.v()}
}
pub fn execute<'local>(mut env:JNIEnv<'local>,object:JObject<'local>)->jint{
 match env.exception_check(){Ok(false)=>(),_=>return -1}
 let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||driver::execute(&mut Session{env:&mut env,object:&object})));
 match result{Ok(Ok(value))=>if value{1}else{0},_=>{match env.exception_check(){Ok(false)=>{let _=env.throw_new("java/lang/IllegalStateException","conflict executor failed");},_=>()};-1}}
}
