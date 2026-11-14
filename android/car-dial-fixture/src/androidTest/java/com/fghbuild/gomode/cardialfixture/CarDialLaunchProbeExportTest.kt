// Proves another app cannot start the debug microphone probe; it needs none of the car-dial fixture state.
package com.fghbuild.gomode.cardialfixture

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarDialLaunchProbeExportTest {
    @Test
    @LocalEmulatorOnly
    fun externalActivityIntentCannotLaunchMicrophoneProbe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThrows(SecurityException::class.java) {
            context.startActivity(
                Intent()
                    .setComponent(ComponentName(APP, "$APP.voice.CarDialLaunchProbeActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private companion object {
        const val APP = "com.fghbuild.gomode"
    }
}
