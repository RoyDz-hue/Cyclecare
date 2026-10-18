package com.example

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CycleCareDatabase
import com.example.data.repository.CycleRepository
import com.example.data.supabase.SupabaseAuthService
import com.example.data.supabase.SupabaseSyncService
import com.example.security.AuthPreferences
import com.example.ui.screens.CareScreen
import com.example.ui.theme.CycleCareTheme
import com.example.ui.viewmodel.CycleCareViewModel
import com.example.ui.viewmodel.CycleUiState
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class CareScreenUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: CycleCareDatabase
    private lateinit var vm: CycleCareViewModel
    @Before fun setup() {
        org.robolectric.util.ReflectionHelpers.callStaticMethod<Void>(org.robolectric.shadows.ShadowTrace::class.java, "setAppTracingAllowed", org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AuthPreferences(context).apply { clearSession(); isLocalOnlyMode = true; isCloudSyncAllowed = false; isAiSharingAllowed = false }
        db = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java).allowMainThreadQueries().build()
        vm = CycleCareViewModel(CycleRepository(db), prefs, SupabaseSyncService(db, prefs), SupabaseAuthService(prefs))
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); db.close() }
    @Test fun composerAndConsentAreExplicitAndEmptySendIsDisabled() {
        compose.setContent { CycleCareTheme { CareScreen(vm, CycleUiState(authMode = "EMAIL")) } }
        compose.onNodeWithTag("care_query_input").assertIsDisplayed()
        compose.onNodeWithTag("care_submit_button").assertIsNotEnabled()
        compose.onNodeWithTag("care_ai_sharing_switch").assertIsOff()
        compose.onNodeWithTag("care_context_switch").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("care_query_input").performTextInput("Why do periods cause cramps?")
        compose.onNodeWithTag("care_submit_button").assertIsEnabled()
    }
    @Test fun decorativePromptHasOwnInputAndGenerationRemainsTierGated() {
        compose.setContent { CycleCareTheme { CareScreen(vm, CycleUiState(authMode = "EMAIL")) } }
        compose.onNodeWithTag("care_open_image_creator").performClick()
        compose.onNodeWithTag("care_custom_image_prompt").performTextInput("Soft blue mountains")
        compose.onNodeWithTag("care_custom_image_prompt").assertTextContains("Soft blue mountains")
        compose.onNodeWithTag("care_generate_image").assertIsNotEnabled()
    }
    @Test fun coreComposerRemainsPresentAtTwoHundredPercentFont() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                CycleCareTheme { CareScreen(vm, CycleUiState(authMode = "EMAIL")) }
            }
        }
        compose.onNodeWithTag("care_query_input").assertIsDisplayed()
        compose.onNodeWithTag("care_submit_button").assertExists()
    }
}
