package com.cyclecare.app.data.local.rust
/** Inert strict planner bridge. Unlike legacy read-only UI helpers, never
 * fallback to Kotlin or turn absent native library into a successful plan.
 */
internal object RustTrackNative {
 @Volatile private var loaded=false
 /** Call off main BEFORE publication/locking to avoid slow library load under prefs exclusion. */
 @Synchronized fun warmUp(){if(!loaded){System.loadLibrary("cyclecare_core");loaded=true}}
 @Synchronized fun plan(request:String):String {
  check(loaded){"planner must be warmed before guarded transaction"}
  return nativePlan(request)?:error("native planner unavailable")
 }
 /** Synchronous driver, only after prelock warmUp. This method never falls
  * back after entry, even for null/unknown results or pending callback error. */
 internal fun completionReady():Boolean=loaded
 internal fun completionDriver(session:Any):Int {check(loaded){"completion executor must be warmed before guarded transaction"};return nativeComplete(session)}
 internal fun settingsDriver(session:Any,reads:Int,mutations:Int):Int {check(loaded){"settings executor must be warmed before guarded transaction"};return nativeSettings(session,reads,mutations)}
 internal fun batchDriver(session:Any,reads:Int,mutations:Int):Int {check(loaded){"batch executor must be warmed before guarded transaction"};return nativeBatch(session,reads,mutations)}
 internal fun outboxDriver(session:Any,mode:Int):Int {check(loaded){"outbox executor must be warmed before guarded transaction"};return nativeOutbox(session,mode)}
 internal fun conflictDriver(session:Any):Int {check(loaded){"conflict executor must be warmed before guarded transaction"};return nativeConflict(session)}
 internal fun remoteDriver(session:Any):Int {check(loaded){"remote executor must be warmed before guarded transaction"};return nativeRemote(session)}
 internal fun resolutionDriver(session:Any):Int {check(loaded){"resolution executor must be warmed before guarded transaction"};return nativeResolution(session)}
 internal fun erasureDriver(session:Any):Int {check(loaded){"erasure executor must be warmed before guarded transaction"};return nativeErase(session)}
 private external fun nativeErase(session:Any):Int
 private external fun nativeResolution(session:Any):Int
 private external fun nativeRemote(session:Any):Int
 private external fun nativeConflict(session:Any):Int
 private external fun nativeOutbox(session:Any,mode:Int):Int
 private external fun nativeBatch(session:Any,reads:Int,mutations:Int):Int
 private external fun nativeSettings(session:Any,reads:Int,mutations:Int):Int
 private external fun nativeComplete(session:Any):Int
 private external fun nativePlan(request:String):String?
}
