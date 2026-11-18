// Verifies cold local settings get a bounded redirection budget and service destruction cancels the wait.
package com.fghbuild.gomode.voice

import android.content.ComponentName
import android.net.Uri
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fghbuild.gomode.data.MemoryPreferences
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [CarDialRedirectionServiceTest.RedirectionResponses::class])
class CarDialRedirectionServiceTest {
    private val dispatcher = StandardTestDispatcher()
    private val releaseSettings = CompletableDeferred<Unit>()
    private lateinit var repository: SettingsRepository
    private lateinit var controller: ServiceController<CarDialRedirectionService>
    private val service get() = controller.get()
    private val responses get() = Shadow.extract<RedirectionResponses>(service).values

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val store = MemoryPreferences()
        runBlocking {
            store.edit {
                it[stringPreferencesKey("SERVICES")] = """[{"id":"saved","url":"https://fixture.invalid"}]"""
                it[stringPreferencesKey("CAR_CALLER_SLOTS")] = """{"saved":1}"""
            }
        }
        store.beforeWrite = { releaseSettings.await() }
        repository = SettingsRepository(store)
        // Replace the Application's lazy fixture owner without adding a production injection API.
        ReflectionHelpers.setField(
            RuntimeEnvironment.getApplication(),
            "settingsRepository\$delegate",
            lazyOf(repository),
        )
        controller = Robolectric.buildService(CarDialRedirectionService::class.java).create()
    }

    @After
    fun tearDown() {
        controller.destroy()
        releaseSettings.complete(Unit)
        Dispatchers.resetMain()
    }

    @Test
    fun ordinaryNumbersDoNotWaitForColdSettings() =
        runTest(dispatcher) {
            listOf("5550123", "+18675550099", "+18675550200", "${GO_MODE_CAR_NUMBER}0", "$GO_MODE_CAR_NUMBER,1")
                .forEachIndexed { index, number ->
                    dial(number)
                    assertEquals(List(index + 1) { "unmodified" }, responses)
                    assertEquals(0L, testScheduler.currentTime)
                }
        }

    @Test
    fun coldSettingsCanFinishAfterOneSecondBeforeTheTelecomDeadline() =
        runTest(dispatcher) {
            dial(carCallerNumber(1))
            runCurrent()
            advanceTimeBy(1_500)
            runCurrent()
            assertTrue("Settings are still loading", responses.isEmpty())
            // Block the test scheduler while the repository's real IO scope publishes
            // Ready, so runTest cannot auto-advance the service's timeout during the wait.
            runBlocking(Dispatchers.Default) {
                releaseSettings.complete(Unit)
                withTimeout(5_000) { repository.settings.first { it.loadState == SettingsLoadState.Ready } }
            }
            runCurrent()
            assertEquals(listOf("cancelled"), responses)
            assertTrue("Loaded settings must decide before timeout", testScheduler.currentTime < 4_000)
        }

    @Test
    fun unavailableSettingsAnswerBeforeTelecomTimesOut() =
        runTest(dispatcher) {
            dial(GO_MODE_CAR_NUMBER)
            runCurrent()
            advanceTimeBy(3_999)
            runCurrent()
            assertTrue(responses.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("cancelled"), responses)
        }

    @Test
    fun unavailableSettingsLeaveServiceContactUnmodified() =
        runTest(dispatcher) {
            dial(carCallerNumber(1))
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(listOf("unmodified"), responses)
        }

    @Test
    fun destroyedServiceDoesNotAnswerAfterItsSettingsWait() =
        runTest(dispatcher) {
            dial(GO_MODE_CAR_NUMBER)
            runCurrent()
            service.onDestroy()
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(responses.isEmpty())
        }

    private fun dial(number: String) {
        val account = PhoneAccountHandle(ComponentName(service, CarDialRedirectionService::class.java), "fixture")
        service.onPlaceCall(Uri.fromParts("tel", number, null), account, false)
    }

    @Implements(CallRedirectionService::class)
    class RedirectionResponses : ShadowService() {
        val values = mutableListOf<String>()

        @Implementation
        fun placeCallUnmodified() {
            values += "unmodified"
        }

        @Implementation
        fun cancelCall() {
            values += "cancelled"
        }
    }
}
