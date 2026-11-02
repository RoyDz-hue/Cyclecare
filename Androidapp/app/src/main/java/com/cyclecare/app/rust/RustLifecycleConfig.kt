package com.cyclecare.app.rust

/**
 * Compile-time switches for the isolated Rust lifecycle wiring. All false:
 * production behavior is byte-for-byte the original Kotlin path. Flip only
 * with device proof on the exact HEAD that enables it.
 */
object RustLifecycleConfig {
    const val STARTUP_GATE_ENABLED = false
}
