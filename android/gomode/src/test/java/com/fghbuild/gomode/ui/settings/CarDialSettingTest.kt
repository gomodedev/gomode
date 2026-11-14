// Tests dial-to-start prerequisites and role-chooser refusal without Android's role dialog.
package com.fghbuild.gomode.ui.settings

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fghbuild.gomode.data.ServiceInstance
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.voice.CarDialRedirectionService
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CarDialSettingTest {
    @get:Rule
    @OptIn(ExperimentalTestApi::class)
    val composeRule = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    private val activity get() = composeRule.activity
    private val component get() = ComponentName(activity, CarDialRedirectionService::class.java)

    @Before
    fun setUp() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(activity.getSystemService(RoleManager::class.java)).addAvailableRole(RoleManager.ROLE_CALL_REDIRECTION)
    }

    @Test
    fun refusingTheRoleChooserTurnsDialingOffAndExplainsWhy() {
        render(SettingsState(services = listOf(ServiceInstance("saved")), loadState = SettingsLoadState.Ready))
        composeRule.onNodeWithText("Enable dial-to-start").performClick()
        // The component must qualify before Android lists Go Mode in its role chooser.
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, componentState())
        val chooser = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Role chooser was not requested", chooser)
        shadowOf(activity).receiveResult(chooser.intent, Activity.RESULT_CANCELED, null)
        composeRule
            .onNodeWithTag("gomode-car-dial-error")
            .assertIsDisplayed()
            .assertTextEquals("Go Mode was not selected. Dial-to-start is off.")
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, componentState())
        composeRule.onNodeWithTag("gomode-car-dial-status").assertTextEquals("Dial-to-start is off.")
    }

    @Test
    fun enablingWaitsForASavedServer() {
        render(SettingsState(loadState = SettingsLoadState.Ready))
        composeRule
            .onNodeWithTag("gomode-car-dial-status")
            .assertTextEquals("Save a server before enabling dial-to-start.")
        composeRule.onNodeWithText("Enable dial-to-start").assertIsNotEnabled()
    }

    private fun render(settings: SettingsState) {
        composeRule.setContent { CarDialSetting(settings, enabled = true) }
    }

    private fun componentState() = activity.packageManager.getComponentEnabledSetting(component)
}
