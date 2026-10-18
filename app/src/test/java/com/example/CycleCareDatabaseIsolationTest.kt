package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CycleCareDatabase
import com.example.data.local.OwnerDataGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class CycleCareDatabaseIsolationTest {
    @Test
    fun ownerNamespacesAreStableDistinctAndDoNotExposeRawIdentity() {
        val a = "user-a@example.test"
        val b = "user-b@example.test"
        val aName = CycleCareDatabase.namespaceFor(a)
        assertTrue(aName.startsWith("cycle_care_owner_"))
        assertTrue(aName == CycleCareDatabase.namespaceFor(a))
        assertNotEquals(aName, CycleCareDatabase.namespaceFor(b))
        assertFalse(aName.contains(a))
    }

    @Test
    fun wipeTombstoneSurvivesNewGateInstanceAndIsOwnerScoped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ownerA = "owner-a-${System.nanoTime()}"
        val ownerB = "owner-b-${System.nanoTime()}"
        assertFalse(OwnerDataGate.isWiped(context, ownerA))
        OwnerDataGate.markWiped(context, ownerA)
        assertTrue(OwnerDataGate.isWiped(context, ownerA))
        assertFalse(OwnerDataGate.isWiped(context, ownerB))
        // Recovery is explicit, never an automatic reconnect side effect.
        OwnerDataGate.resumeAfterExplicitUserAction(context, ownerA)
        assertFalse(OwnerDataGate.isWiped(context, ownerA))
    }
}
