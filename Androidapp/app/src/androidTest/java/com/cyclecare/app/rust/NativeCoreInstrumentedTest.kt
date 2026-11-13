package com.cyclecare.app.rust

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.rust.RustTrackNative
import com.cyclecare.app.data.local.rust.RustTrackWire
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the packaged native core actually LOADS and answers on a
 * real ARM Android runtime. The Stage-E CI gates prove static composition only
 * (ELF alignment/exports, APK bytes, DEX classes); this class is the runtime
 * tier: System.loadLibrary must resolve the 16 KB-aligned AArch64 cdylib and
 * the exact JNI exports the host calls must link and answer.
 *
 * Synthetic only: throwaway identity UUIDs, default settings, one synthetic
 * day log. No real user health data, no auth credentials, no network.
 */
@RunWith(AndroidJUnit4::class)
class NativeCoreInstrumentedTest {

    @Test
    fun nativeLibraryLoadsOnDevice() {
        // Throws UnsatisfiedLinkError on any load failure (missing library,
        // wrong ABI, or a device rejecting non-16 KB-aligned segments).
        System.loadLibrary("cyclecare_core")
    }

    @Test
    fun warmUpAndPlannedRoundtripValidates() {
        // Production guarded-planner path, exercised end to end with the
        // production request builder and the production strict validator:
        // RustTrackWire.request -> RustTrackNative.plan -> RustTrackWire.validate.
        RustTrackNative.warmUp()
        val identity = RustTrackWire.Identity(
            namespace = "cycle_care_database",
            effective = "synthetic-instrumented-${UUID.randomUUID()}",
            instance = UUID.randomUUID().toString(),
            epoch = 1L,
            session = 1L,
            generation = 1L,
            local = true,
        )
        val settings = CycleSettings() // production defaults; synthetic row
        val log = CycleDayLog(
            dateString = "2026-11-06",
            energyLevel = "MEDIUM",
            notes = "synthetic instrumented row - not user data",
        )
        val operation = UUID.randomUUID().toString()
        val request = RustTrackWire.request(
            identity = identity,
            operation = operation,
            date = log.dateString,
            revision = 0L,
            settingsRevision = 0L,
            log = log,
            settings = settings,
        )
        val reply = RustTrackNative.plan(request)
        assertTrue("native planner reply must be non-empty", reply.isNotEmpty())
        // validate() recomputes the canonical form and digest and throws on any
        // malformed or mismatched native reply; a returned Plan is exact proof.
        val plan = RustTrackWire.validate(
            reply = reply,
            identity = identity,
            operation = operation,
            date = log.dateString,
            revision = 0L,
            settingsRevision = 0L,
            log = log,
            settings = settings,
        )
        assertTrue("plan digest must be non-empty", plan.digest.isNotEmpty())
    }
}
