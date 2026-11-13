package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.repository.RustPortInstallResolver
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.security.IdentityPublicationAdmission
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage-D resolver ORDER witnesses over the REAL RustPortInstallResolver
 * (reviewer item: identity movement DURING the actual open must be covered
 * at true resolution, not merely before install). The provider seam supplies
 * a lazy plain-Room fixture (production SQLCipher is unloadable on the
 * host); everything else is the production path: snapshot capture, lazy
 * instance, forced actual open, post-open re-verification, capability mint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageDResolverOpenWitnessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val openDbs = mutableListOf<CycleCareDatabase>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stage-d-resolver-it").apply { isDaemon = true } }

    @After fun cleanup() {
        openDbs.forEach { if (it.isOpen) it.close() }
        openDbs.clear()
        pool.shutdownNow()
    }

    private fun prefs(owner: String = "owner-a"): AuthPreferences =
        AuthPreferences(context, context.getSharedPreferences("stage-d-resolver-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
            .apply { userId = owner; attachPublicationAdmission(IdentityPublicationAdmission.forAndroid()) }

    private fun newLazyDb(): CycleCareDatabase =
        Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries()
            .addMigrations(com.cyclecare.app.data.local.rust.RUST_FLOAT_METADATA_DRAFT)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    com.cyclecare.app.data.local.rust.applyRustMetadataDraft(db)
                }
            })
            .build().also(openDbs::add)

    private fun <T> offMain(block: () -> T): T =
        pool.submit(java.util.concurrent.Callable { block() }).get(30, TimeUnit.SECONDS)

    @Test fun cleanResolutionForcesTheActualOpenAndBindsTheCapturedIdentity() {
        val p = prefs("owner-a")
        val db = newLazyDb()
        assertFalse("fixture starts lazy/unopened", db.isOpen)
        val result = offMain { RustPortInstallResolver(context, p) { _, _ -> db }.resolve() }
        val resolved = result as? RustPortInstallResolver.Result.Resolved
            ?: throw AssertionError("expected Resolved, got $result")
        assertTrue("resolver forced the ACTUAL open before binding", db.isOpen)
        assertEquals("owner-a", resolved.resolution.owner)
        assertTrue("captured snapshot still current", resolved.resolution.stillCurrent())
    }

    @Test fun identityMoveInsideTheActualOpenYieldsStale() {
        val p = prefs("owner-a")
        // The mutation runs in Room's onOpen callback - INSIDE the actual
        // writableDatabase open the resolver forces, on the resolver thread.
        // A re-verification placed BEFORE the open cannot observe this move
        // (it has not happened yet at any pre-open point), so passing
        // REQUIRES the check to follow the open: this witness falsifies an
        // early check, unlike a before-open barrier.
        val db = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries()
            .addMigrations(com.cyclecare.app.data.local.rust.RUST_FLOAT_METADATA_DRAFT)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    com.cyclecare.app.data.local.rust.applyRustMetadataDraft(db)
                }
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    p.userId = "owner-b" // off-main open thread: the admission joins synchronously
                }
            })
            .build().also(openDbs::add)
        assertFalse("fixture starts lazy/unopened", db.isOpen)
        val result = offMain { RustPortInstallResolver(context, p) { _, _ -> db }.resolve() }
        assertEquals("post-open re-verification rejects the identity moved DURING the open",
            RustPortInstallResolver.Result.Stale, result)
        assertTrue("the open really happened (the check follows it, not precedes it)", db.isOpen)
        assertEquals("owner-b", p.userId)
        // Recovery: a fresh resolve binds the NEW identity (the fixture is
        // already open; onOpen does not refire).
        val second = offMain { RustPortInstallResolver(context, p) { _, _ -> db }.resolve() }
        val resolved = second as? RustPortInstallResolver.Result.Resolved
            ?: throw AssertionError("expected Resolved after re-resolution, got $second")
        assertEquals("owner-b", resolved.resolution.owner)
    }

    @Test fun resolutionWithoutAdmissionFailsClosed() {
        val p = AuthPreferences(context, context.getSharedPreferences("stage-d-resolver-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
            .apply { userId = "owner-a" } // no attachPublicationAdmission
        val result = offMain { RustPortInstallResolver(context, p) { _, _ -> newLazyDb() }.resolve() }
        assertEquals(RustPortInstallResolver.Result.NoAdmission, result)
    }
}
