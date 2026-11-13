package com.cyclecare.app.data.local.rust
/** Inert strict planner bridge. Unlike legacy read-only UI helpers, never
 * fallback to Kotlin or turn absent native library into a successful plan.
 */
internal object RustTrackNative {
 private var loaded=false
 /** Call off main BEFORE publication/locking to avoid slow library load under prefs exclusion. */
 @Synchronized fun warmUp(){if(!loaded){System.loadLibrary("cyclecare_core");loaded=true}}
 @Synchronized fun plan(request:String):String {
  check(loaded){"planner must be warmed before guarded transaction"}
  return nativePlan(request)?:error("native planner unavailable")
 }
 private external fun nativePlan(request:String):String?
}
