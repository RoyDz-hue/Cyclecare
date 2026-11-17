package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.supabase.SupabaseAuthService
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Fallback-parity pins for the data_export_json_v1 twin (NEW
 * IMPLEMENTATION, 2026-11-13): the twin is the verbatim pre-wiring
 * org.json assembly, reached by reflection on a Robolectric-built
 * CycleCareViewModel. Every expectation is the AOSP org.json oracle
 * output from the Rust differential corpus (fixtures/oracle/
 * results_exportjson.tsv, 62 cases generated against the real AOSP
 * classes on jdk-17.0.21+9), embedded as base64 so the oracle bytes are
 * reproduced without transcription. Written, NOT executed here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataExportJsonFallbackParityTest {

    private fun viewModel(): CycleCareViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AuthPreferences(
            context,
            context.getSharedPreferences("export-twin-" + java.util.UUID.randomUUID(), Context.MODE_PRIVATE),
            true,
        )
        val database = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries().build()
        return CycleCareViewModel(
            CycleRepository(database), prefs,
            SupabaseSyncService(database, prefs), SupabaseAuthService(prefs),
        )
    }

    private fun twin(
        settings: CycleSettings,
        profile: OnboardingProfile?,
        sortedLogs: List<CycleDayLog>,
        epochMs: Long,
    ): String {
        val m = CycleCareViewModel::class.java.getDeclaredMethod(
            "kotlinBuildDataExportJson",
            CycleSettings::class.java,
            OnboardingProfile::class.java,
            List::class.java,
            Long::class.javaPrimitiveType,
        )
        m.isAccessible = true
        return m.invoke(viewModel(), settings, profile, sortedLogs, epochMs) as String
    }

    private fun oracle(b64: String): String =
        String(Base64.getDecoder().decode(b64), Charsets.UTF_8)

    /** x1: baseline - profile present, one log, integral floats print as longs. */
    @Test
    fun corpusX1Baseline() {
        val settings = CycleSettings(lastPeriodStartDate = "2026-10-01")
        val profile = OnboardingProfile()
        val log = CycleDayLog(dateString = "2026-10-02")
        assertEquals(oracle(X1), twin(settings, profile, listOf(log), 1700000000000L))
    }

    /** x3: AOSP quote() escaping of double quotes in three fields. */
    @Test
    fun corpusX3QuoteEscaping() {
        val settings = CycleSettings(
            selectedGoalsCsv = "has \"quote\"",
            lastPeriodStartDate = "2026-10-01",
        )
        val profile = OnboardingProfile(typicalSymptomsCsv = "has \"quote\"")
        val log = CycleDayLog(dateString = "2026-10-02", notes = "has \"quote\"")
        assertEquals(oracle(X3), twin(settings, profile, listOf(log), 1700000000000L))
    }

    /** x24: non-integral Float prints via Float.toString (0.1f -> 0.1). */
    @Test
    fun corpusX24NonIntegralFloat() {
        val settings = CycleSettings(lastPeriodStartDate = "2026-10-01")
        val profile = OnboardingProfile()
        val log = CycleDayLog(
            dateString = "2026-10-02",
            bbtCelsius = 0.1f,
            sleepHours = 0.1f,
        )
        assertEquals(oracle(X24), twin(settings, profile, listOf(log), 1700000000000L))
    }

    /** x58: absent profile -> empty object, no logs -> empty array. */
    @Test
    fun corpusX58AbsentProfileEmptyLogs() {
        val settings = CycleSettings()
        assertEquals(oracle(X58), twin(settings, null, emptyList(), 1700000000000L))
    }

    private companion object {
        const val X1 = "ewogICJmb3JtYXQiOiAiQ3ljbGVDYXJlIHBlcnNvbmFsIGRhdGEgZXhwb3J0IiwKICAiZm9ybWF0X3ZlcnNpb24iOiAxLAogICJleHBvcnRlZF9hdF9lcG9jaF9tcyI6IDE3MDAwMDAwMDAwMDAsCiAgImN5Y2xlX3NldHRpbmdzIjogewogICAgInNlbGVjdGVkX2dvYWxzIjogIiIsCiAgICAibGFzdF9wZXJpb2Rfc3RhcnRfZGF0ZSI6ICIyMDI2LTEwLTAxIiwKICAgICJhdmVyYWdlX2N5Y2xlX2xlbmd0aF9kYXlzIjogMjgsCiAgICAicGVyaW9kX2R1cmF0aW9uX2RheXMiOiA1LAogICAgInJlbWluZF9wZXJpb2QiOiBmYWxzZSwKICAgICJyZW1pbmRfZmVydGlsZV93aW5kb3ciOiBmYWxzZSwKICAgICJyZW1pbmRfZGFpbHlfbG9nIjogZmFsc2UsCiAgICAicHJpdmF0ZV9sb2NrX3NjcmVlbl9ub3RpZmljYXRpb25zIjogdHJ1ZQogIH0sCiAgIm9uYm9hcmRpbmdfcHJvZmlsZSI6IHsKICAgICJzZWxlY3RlZF9nb2FscyI6ICIiLAogICAgImxhc3RfcGVyaW9kX2RhdGUiOiAiIiwKICAgICJsYXN0X3BlcmlvZF9lbmRfZGF0ZSI6ICIiLAogICAgInBlcmlvZF9kYXRlX3ByZWNpc2lvbiI6ICJFWEFDVCIsCiAgICAiY3ljbGVfbGVuZ3RoX2Nob2ljZSI6ICIiLAogICAgImN5Y2xlX2xlbmd0aF9kYXlzIjogMjgsCiAgICAicGVyaW9kX2R1cmF0aW9uX2RheXMiOiA1LAogICAgImZlcnRpbGl0eV9yZW1pbmRlcnNfZW5hYmxlZCI6IGZhbHNlLAogICAgInR5cGljYWxfc3ltcHRvbXMiOiAiIiwKICAgICJ0eXBpY2FsX2VuZXJneSI6ICIiLAogICAgInR5cGljYWxfc2xlZXBfcXVhbGl0eSI6ICIiCiAgfSwKICAiZGFpbHlfbG9ncyI6IFsKICAgIHsKICAgICAgImRhdGUiOiAiMjAyNi0xMC0wMiIsCiAgICAgICJmbG93IjogIiIsCiAgICAgICJlbmVyZ3kiOiAiIiwKICAgICAgIm1vb2QiOiAiIiwKICAgICAgInN5bXB0b21zIjogIiIsCiAgICAgICJ2YWdpbmFsX2hlYWx0aCI6ICIiLAogICAgICAidXJpbmFyeV9oZWFsdGgiOiAiIiwKICAgICAgImNlcnZpY2FsX211Y3VzIjogIiIsCiAgICAgICJvdnVsYXRpb25fdGVzdCI6ICJOT05FIiwKICAgICAgImJhc2FsX2JvZHlfdGVtcGVyYXR1cmVfY2Vsc2l1cyI6IDAsCiAgICAgICJzbGVlcF9ob3VycyI6IDAsCiAgICAgICJzbGVlcF9xdWFsaXR5IjogIiIsCiAgICAgICJ3YXRlcl9nbGFzc2VzIjogMCwKICAgICAgIm5vdGVzIjogIiIsCiAgICAgICJwYWluX2ludGVuc2l0eSI6IDAKICAgIH0KICBdCn0="
        const val X3 = "ewogICJmb3JtYXQiOiAiQ3ljbGVDYXJlIHBlcnNvbmFsIGRhdGEgZXhwb3J0IiwKICAiZm9ybWF0X3ZlcnNpb24iOiAxLAogICJleHBvcnRlZF9hdF9lcG9jaF9tcyI6IDE3MDAwMDAwMDAwMDAsCiAgImN5Y2xlX3NldHRpbmdzIjogewogICAgInNlbGVjdGVkX2dvYWxzIjogImhhcyBcInF1b3RlXCIiLAogICAgImxhc3RfcGVyaW9kX3N0YXJ0X2RhdGUiOiAiMjAyNi0xMC0wMSIsCiAgICAiYXZlcmFnZV9jeWNsZV9sZW5ndGhfZGF5cyI6IDI4LAogICAgInBlcmlvZF9kdXJhdGlvbl9kYXlzIjogNSwKICAgICJyZW1pbmRfcGVyaW9kIjogZmFsc2UsCiAgICAicmVtaW5kX2ZlcnRpbGVfd2luZG93IjogZmFsc2UsCiAgICAicmVtaW5kX2RhaWx5X2xvZyI6IGZhbHNlLAogICAgInByaXZhdGVfbG9ja19zY3JlZW5fbm90aWZpY2F0aW9ucyI6IHRydWUKICB9LAogICJvbmJvYXJkaW5nX3Byb2ZpbGUiOiB7CiAgICAic2VsZWN0ZWRfZ29hbHMiOiAiIiwKICAgICJsYXN0X3BlcmlvZF9kYXRlIjogIiIsCiAgICAibGFzdF9wZXJpb2RfZW5kX2RhdGUiOiAiIiwKICAgICJwZXJpb2RfZGF0ZV9wcmVjaXNpb24iOiAiRVhBQ1QiLAogICAgImN5Y2xlX2xlbmd0aF9jaG9pY2UiOiAiIiwKICAgICJjeWNsZV9sZW5ndGhfZGF5cyI6IDI4LAogICAgInBlcmlvZF9kdXJhdGlvbl9kYXlzIjogNSwKICAgICJmZXJ0aWxpdHlfcmVtaW5kZXJzX2VuYWJsZWQiOiBmYWxzZSwKICAgICJ0eXBpY2FsX3N5bXB0b21zIjogImhhcyBcInF1b3RlXCIiLAogICAgInR5cGljYWxfZW5lcmd5IjogIiIsCiAgICAidHlwaWNhbF9zbGVlcF9xdWFsaXR5IjogIiIKICB9LAogICJkYWlseV9sb2dzIjogWwogICAgewogICAgICAiZGF0ZSI6ICIyMDI2LTEwLTAyIiwKICAgICAgImZsb3ciOiAiIiwKICAgICAgImVuZXJneSI6ICIiLAogICAgICAibW9vZCI6ICIiLAogICAgICAic3ltcHRvbXMiOiAiIiwKICAgICAgInZhZ2luYWxfaGVhbHRoIjogIiIsCiAgICAgICJ1cmluYXJ5X2hlYWx0aCI6ICIiLAogICAgICAiY2VydmljYWxfbXVjdXMiOiAiIiwKICAgICAgIm92dWxhdGlvbl90ZXN0IjogIk5PTkUiLAogICAgICAiYmFzYWxfYm9keV90ZW1wZXJhdHVyZV9jZWxzaXVzIjogMCwKICAgICAgInNsZWVwX2hvdXJzIjogMCwKICAgICAgInNsZWVwX3F1YWxpdHkiOiAiIiwKICAgICAgIndhdGVyX2dsYXNzZXMiOiAwLAogICAgICAibm90ZXMiOiAiaGFzIFwicXVvdGVcIiIsCiAgICAgICJwYWluX2ludGVuc2l0eSI6IDAKICAgIH0KICBdCn0="
        const val X24 = "ewogICJmb3JtYXQiOiAiQ3ljbGVDYXJlIHBlcnNvbmFsIGRhdGEgZXhwb3J0IiwKICAiZm9ybWF0X3ZlcnNpb24iOiAxLAogICJleHBvcnRlZF9hdF9lcG9jaF9tcyI6IDE3MDAwMDAwMDAwMDAsCiAgImN5Y2xlX3NldHRpbmdzIjogewogICAgInNlbGVjdGVkX2dvYWxzIjogIiIsCiAgICAibGFzdF9wZXJpb2Rfc3RhcnRfZGF0ZSI6ICIyMDI2LTEwLTAxIiwKICAgICJhdmVyYWdlX2N5Y2xlX2xlbmd0aF9kYXlzIjogMjgsCiAgICAicGVyaW9kX2R1cmF0aW9uX2RheXMiOiA1LAogICAgInJlbWluZF9wZXJpb2QiOiBmYWxzZSwKICAgICJyZW1pbmRfZmVydGlsZV93aW5kb3ciOiBmYWxzZSwKICAgICJyZW1pbmRfZGFpbHlfbG9nIjogZmFsc2UsCiAgICAicHJpdmF0ZV9sb2NrX3NjcmVlbl9ub3RpZmljYXRpb25zIjogdHJ1ZQogIH0sCiAgIm9uYm9hcmRpbmdfcHJvZmlsZSI6IHsKICAgICJzZWxlY3RlZF9nb2FscyI6ICIiLAogICAgImxhc3RfcGVyaW9kX2RhdGUiOiAiIiwKICAgICJsYXN0X3BlcmlvZF9lbmRfZGF0ZSI6ICIiLAogICAgInBlcmlvZF9kYXRlX3ByZWNpc2lvbiI6ICJFWEFDVCIsCiAgICAiY3ljbGVfbGVuZ3RoX2Nob2ljZSI6ICIiLAogICAgImN5Y2xlX2xlbmd0aF9kYXlzIjogMjgsCiAgICAicGVyaW9kX2R1cmF0aW9uX2RheXMiOiA1LAogICAgImZlcnRpbGl0eV9yZW1pbmRlcnNfZW5hYmxlZCI6IGZhbHNlLAogICAgInR5cGljYWxfc3ltcHRvbXMiOiAiIiwKICAgICJ0eXBpY2FsX2VuZXJneSI6ICIiLAogICAgInR5cGljYWxfc2xlZXBfcXVhbGl0eSI6ICIiCiAgfSwKICAiZGFpbHlfbG9ncyI6IFsKICAgIHsKICAgICAgImRhdGUiOiAiMjAyNi0xMC0wMiIsCiAgICAgICJmbG93IjogIiIsCiAgICAgICJlbmVyZ3kiOiAiIiwKICAgICAgIm1vb2QiOiAiIiwKICAgICAgInN5bXB0b21zIjogIiIsCiAgICAgICJ2YWdpbmFsX2hlYWx0aCI6ICIiLAogICAgICAidXJpbmFyeV9oZWFsdGgiOiAiIiwKICAgICAgImNlcnZpY2FsX211Y3VzIjogIiIsCiAgICAgICJvdnVsYXRpb25fdGVzdCI6ICJOT05FIiwKICAgICAgImJhc2FsX2JvZHlfdGVtcGVyYXR1cmVfY2Vsc2l1cyI6IDAuMSwKICAgICAgInNsZWVwX2hvdXJzIjogMC4xLAogICAgICAic2xlZXBfcXVhbGl0eSI6ICIiLAogICAgICAid2F0ZXJfZ2xhc3NlcyI6IDAsCiAgICAgICJub3RlcyI6ICIiLAogICAgICAicGFpbl9pbnRlbnNpdHkiOiAwCiAgICB9CiAgXQp9"
        const val X58 = "ewogICJmb3JtYXQiOiAiQ3ljbGVDYXJlIHBlcnNvbmFsIGRhdGEgZXhwb3J0IiwKICAiZm9ybWF0X3ZlcnNpb24iOiAxLAogICJleHBvcnRlZF9hdF9lcG9jaF9tcyI6IDE3MDAwMDAwMDAwMDAsCiAgImN5Y2xlX3NldHRpbmdzIjogewogICAgInNlbGVjdGVkX2dvYWxzIjogIiIsCiAgICAibGFzdF9wZXJpb2Rfc3RhcnRfZGF0ZSI6ICIiLAogICAgImF2ZXJhZ2VfY3ljbGVfbGVuZ3RoX2RheXMiOiAyOCwKICAgICJwZXJpb2RfZHVyYXRpb25fZGF5cyI6IDUsCiAgICAicmVtaW5kX3BlcmlvZCI6IGZhbHNlLAogICAgInJlbWluZF9mZXJ0aWxlX3dpbmRvdyI6IGZhbHNlLAogICAgInJlbWluZF9kYWlseV9sb2ciOiBmYWxzZSwKICAgICJwcml2YXRlX2xvY2tfc2NyZWVuX25vdGlmaWNhdGlvbnMiOiB0cnVlCiAgfSwKICAib25ib2FyZGluZ19wcm9maWxlIjoge30sCiAgImRhaWx5X2xvZ3MiOiBbXQp9"
    }
}
