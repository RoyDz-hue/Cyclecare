package com.cyclecare.app

import org.junit.Test

/** Scratch provenance capture: how does the test JVM resolve cyclecare_core? */
class JniProvenanceCaptureTest {
    @Test fun captureLibraryPathAndLoad() {
        val lp = System.getProperty("java.library.path")
        println("JVM-LIBRARY-PATH: $lp")
        val loaded = runCatching { System.loadLibrary("cyclecare_core") }
        println("LOADLIBRARY-RESULT: ${loaded.isSuccess} ${loaded.exceptionOrNull()?.message ?: "loaded"}")
        // loadLibrary also resolves the running JDK's own lib dir, which is
        // not listed in java.library.path on this launcher, so scan it too.
        val candidates = lp.split(":").map { java.io.File(it, "libcyclecare_core.so") } +
            java.io.File(System.getProperty("java.home"), "lib/libcyclecare_core.so")
        val libFile = candidates.firstOrNull { it.exists() }
        println("RESOLVED-FROM: ${libFile?.absolutePath ?: "not-on-path"}")
        if (libFile != null) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(libFile.readBytes())
            println("RESOLVED-SHA256: " + digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
