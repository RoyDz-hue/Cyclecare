package com.cyclecare.app

import com.cyclecare.app.data.economy.EconomyCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fallback-parity pins for the economy_catalog_find_item_v1 twin (NEW
 * IMPLEMENTATION, 2026-11-13): the twin is the verbatim pre-wiring
 * findItem body, reached by reflection on the EconomyCatalog object.
 * Pure JVM (string compare over a static table), no Android runtime
 * needed. The Unicode-folding pin ("ımperial pearl halo" matching
 * frame_elegant) is grounded in Java Character.toUpperCase(U+0131) ==
 * 'I' - the exact case the native side declines to this twin.
 * Written, NOT executed in this workspace.
 */
class EconomyCatalogFindItemFallbackParityTest {

    private fun twin(idOrName: String?) =
        EconomyCatalog::class.java.getDeclaredMethod("kotlinFindItem", String::class.java).let {
            it.isAccessible = true
            it.invoke(EconomyCatalog, idOrName)
        }

    @Test
    fun idNameAndAliasResolution() {
        // By id, exact and case-insensitive.
        assertEquals("cycle_coin", twin("cycle_coin")?.let { (it as com.cyclecare.app.data.economy.EconomyItem).id })
        // By name, case-insensitive.
        assertEquals("artifact_bloom", (twin("golden blossom") as com.cyclecare.app.data.economy.EconomyItem).id)
        // Legacy aliases.
        assertEquals("artifact_bloom", (twin("Blooming Lotus") as com.cyclecare.app.data.economy.EconomyItem).id)
        assertEquals("artifact_crystal", (twin("Rose Quartz Crystal") as com.cyclecare.app.data.economy.EconomyItem).id)
        assertEquals("artifact_orbit", (twin("Dual Eclipse") as com.cyclecare.app.data.economy.EconomyItem).id)
        assertEquals("artifact_orbit", (twin("Celestial Orbit") as com.cyclecare.app.data.economy.EconomyItem).id)
        assertEquals("frame_elegant", (twin("Celestial Tiara") as com.cyclecare.app.data.economy.EconomyItem).id)
        assertEquals("frame_elegant", (twin("Golden Pearl Frame") as com.cyclecare.app.data.economy.EconomyItem).id)
    }

    @Test
    fun trimAndNoMatch() {
        assertEquals("cycle_coin", (twin("  cycle_coin  ") as com.cyclecare.app.data.economy.EconomyItem).id)
        assertNull(twin(null))
        assertNull(twin(""))
        assertNull(twin("   "))
        assertNull(twin("not a catalog item"))
    }

    @Test
    fun unicodeCaseFolding() {
        // Kotlin equals(ignoreCase=true) folds U+0131 (dotless i) to 'I'
        // via Character.toUpperCase; the port declines this input to the
        // twin, and the twin must still match.
        assertEquals("frame_elegant", (twin("ımperial pearl halo") as com.cyclecare.app.data.economy.EconomyItem).id)
    }
}
