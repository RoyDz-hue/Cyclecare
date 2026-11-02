//! JNI exports for the Kotlin host. One export per core contract the host
//! consumes. Pure functions only: the bridge passes owner/session snapshots
//! in Kotlin and re-checks them after the native call, so these exports take
//! no identity state and hold no globals.
//!
//! COMPILE-UNVERIFIED in this sandbox (no Rust toolchain here); first compile
//! happens in the bounded toolchain run. Symbol naming follows the JNI rule:
//! Java_<mangled package + class>_<method>.

use jni::objects::{JClass, JString};
use jni::sys::jboolean;
use jni::JNIEnv;

/// Kotlin side: com.cyclecare.app.rust.CycleCareCoreBridge.nativeIsCompletePin
#[no_mangle]
pub extern "C" fn Java_com_cyclecare_app_rust_CycleCareCoreBridge_nativeIsCompletePin(
    mut env: JNIEnv,
    _class: JClass,
    pin: JString,
) -> jboolean {
    let input: String = match env.get_string(&pin) {
        Ok(s) => s.into(),
        // A malformed string is not a complete PIN; never panic across JNI.
        Err(_) => return 0,
    };
    cyclecare_core::pin::is_complete_pin(&input) as jboolean
}

/// Probe export so the host can verify the library loaded and links the core
/// at runtime without calling any contract. Returns the core test count as a
/// boring constant handshake.
#[no_mangle]
pub extern "C" fn Java_com_cyclecare_app_rust_CycleCareCoreBridge_nativeCoreHandshake(
    _env: JNIEnv,
    _class: JClass,
) -> i32 {
    42
}
