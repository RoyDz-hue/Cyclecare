package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.repository.CycleRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tracking-write flow-anchor route through the real
 * CycleRepository.saveDayLogBound funnel (production path of
 * CycleCareViewModel.saveDayLog). Whichever tier decides - the native bridge
 * if loadable, otherwise the Kotlin policy - the settings anchor must move
 * exactly when the Kotlin policy says: flow log strictly after prior+15
 * days, or no parseable prior. The native port's matching decisions are
 * pinned by the rust core-ffi flow_anchor_decision dispatch tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CycleRepositoryFlowAnchorRouteTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun freshRepo(): Pair<CycleRepository, CycleCareDatabase> {
        val db = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries().build()
        return CycleRepository(db) to db
    }

    private fun anchorOf(db: CycleCareDatabase): String = runBlocking {
        db.cycleDao().getSettings()?.lastPeriodStartDate ?: "<no-row>"
    }

    @Test
    fun flowAfterPlus15AdvancesAnchor() {
        val (repo, db) = freshRepo()
        runBlocking {
            db.cycleDao().saveSettings(CycleSettings(lastPeriodStartDate = "2026-10-05"))
            repo.saveDayLogBound(CycleDayLog("2026-10-21", flowLevel = "HEAVY"), repo.beginOperation())
        }
        assertEquals("2026-10-21", anchorOf(db))
    }

    @Test
    fun flowExactlyPlus15KeepsAnchor() {
        val (repo, db) = freshRepo()
        runBlocking {
            db.cycleDao().saveSettings(CycleSettings(lastPeriodStartDate = "2026-10-05"))
            repo.saveDayLogBound(CycleDayLog("2026-10-20", flowLevel = "HEAVY"), repo.beginOperation())
        }
        assertEquals("2026-10-05", anchorOf(db))
    }

    @Test
    fun nonFlowLogKeepsAnchor() {
        val (repo, db) = freshRepo()
        runBlocking {
            db.cycleDao().saveSettings(CycleSettings(lastPeriodStartDate = "2026-10-05"))
            repo.saveDayLogBound(CycleDayLog("2027-01-01", flowLevel = "", symptomsCsv = "headache"), repo.beginOperation())
        }
        assertEquals("2026-10-05", anchorOf(db))
    }

    @Test
    fun blankPriorAnchorsOnFlowLog() {
        val (repo, db) = freshRepo()
        runBlocking {
            db.cycleDao().saveSettings(CycleSettings(lastPeriodStartDate = ""))
            repo.saveDayLogBound(CycleDayLog("2026-10-10", flowLevel = "MODERATE"), repo.beginOperation())
        }
        assertEquals("2026-10-10", anchorOf(db))
    }

    @Test
    fun earlierFlowLogKeepsAnchor() {
        val (repo, db) = freshRepo()
        runBlocking {
            db.cycleDao().saveSettings(CycleSettings(lastPeriodStartDate = "2026-10-05"))
            repo.saveDayLogBound(CycleDayLog("2026-10-10", flowLevel = "LIGHT"), repo.beginOperation())
        }
        assertEquals("2026-10-05", anchorOf(db))
    }
}
