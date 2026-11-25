// Tests dial-to-start prerequisites, role selection, and role loss without Android's dialogs.
package com.fghbuild.gomode.ui.settings

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowRoleManager

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
        // An empty holder makes the role available but held by no app, as Robolectric's addAvailableRole did.
        ShadowRoleManager.addRoleHolder(RoleManager.ROLE_CALL_REDIRECTION, "", Process.myUserHandle())
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

    @Test
    fun deniedMicrophoneKeepsDialingDisabledAndExplainsWhereToGrantIt() {
        shadowOf(activity.application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        render(savedServer)
        status("Allow microphone access before enabling dial-to-start.")
        composeRule.onNodeWithText("Enable dial-to-start").assertIsNotEnabled()
        answerMicrophoneRequest(PackageManager.PERMISSION_DENIED)
        composeRule
            .onNodeWithTag("gomode-car-dial-error")
            .assertTextEquals("Allow microphone permission in Android app settings to continue.")
        composeRule.onNodeWithText("Enable dial-to-start").assertIsNotEnabled()
    }

    @Test
    fun grantedMicrophoneAllowsEnabling() {
        shadowOf(activity.application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        render(savedServer)
        answerMicrophoneRequest(PackageManager.PERMISSION_GRANTED)
        composeRule.onNodeWithTag("gomode-car-dial-error").assertDoesNotExist()
        composeRule.onNodeWithText("Allow microphone").assertDoesNotExist()
        status("Dial-to-start is off.")
        composeRule.onNodeWithText("Enable dial-to-start").assertIsEnabled()
    }

    private fun answerMicrophoneRequest(result: Int) {
        composeRule.onNodeWithText("Allow microphone").performClick()
        val request = requireNotNull(shadowOf(activity).lastRequestedPermission) { "Microphone was not requested" }
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), request.requestedPermissions.toList())
        // The user's answer in Android's dialog updates the permission before the result arrives.
        if (result == PackageManager.PERMISSION_GRANTED) {
            shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        }
        @Suppress("DEPRECATION") // Android delivers the answer here; the Activity Result registry reads it.
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(result))
    }

    @Test
    fun selectingTheRoleTurnsDialingOnAndDisablingTurnsItOff() {
        render(savedServer)
        composeRule.onNodeWithText("Enable dial-to-start").performClick()
        val chooser = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Role chooser was not requested", chooser)
        ShadowRoleManager.addRoleHolder(RoleManager.ROLE_CALL_REDIRECTION, activity.packageName, Process.myUserHandle())
        shadowOf(activity).receiveResult(chooser.intent, Activity.RESULT_OK, null)
        status("Dial-to-start is on.")
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, componentState())
        composeRule.onNodeWithText("Disable dial-to-start").performClick()
        status("Dial-to-start is off.")
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, componentState())
        // The role survives opting out, so enabling again needs no chooser.
        composeRule.onNodeWithText("Enable dial-to-start").performClick()
        status("Dial-to-start is on.")
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test
    fun optedInWithoutTheRoleOffersReselection() {
        // Another app took the role after Go Mode opted in.
        activity.packageManager.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        render(savedServer)
        status("Select Go Mode again to enable dial-to-start.")
        composeRule.onNodeWithText("Disable dial-to-start").assertIsEnabled()
        composeRule.onNodeWithText("Select Go Mode again").performClick()
        assertNotNull("Role chooser was not requested", shadowOf(activity).nextStartedActivityForResult)
    }

    private val savedServer =
        SettingsState(services = listOf(ServiceInstance("saved")), loadState = SettingsLoadState.Ready)

    private fun status(text: String) {
        composeRule.onNodeWithTag("gomode-car-dial-status").assertTextEquals(text)
    }

    private fun render(settings: SettingsState) {
        // The settings screen's Column lays out the setting's siblings.
        composeRule.setContent { Column { CarDialSetting(settings, enabled = true) } }
    }

    private fun componentState() = activity.packageManager.getComponentEnabledSetting(component)
}
