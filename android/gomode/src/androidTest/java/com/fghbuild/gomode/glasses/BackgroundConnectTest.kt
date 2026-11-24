// Characterizes the platform Bluetooth stack: background connections to absent glasses outlast its direct-connection deadline.
package com.fghbuild.gomode.glasses

import android.Manifest
import android.bluetooth.BluetoothManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fghbuild.eveng2.G2Arm
import com.fghbuild.eveng2.G2GattConnector
import com.fghbuild.eveng2.G2LinkLostException
import com.fghbuild.eveng2.G2Side
import com.fghbuild.gomode.LocalEmulatorOnly
import com.fghbuild.halo.ble.HaloConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Documentation-range addresses that no glasses answer to.
private const val ABSENT_HALO = "00:00:5E:00:53:11"
private val ABSENT_ARM = G2Arm(G2Side.RIGHT, "00:00:5E:00:53:12", "Even G2_8_R_005312")
private val ABSENT_DIRECT_ARM = G2Arm(G2Side.LEFT, "00:00:5E:00:53:13", "Even G2_8_L_005313")

// AOSP's connection manager ends a direct connection after 30 s; this waits past it.
private const val PAST_DIRECT_DEADLINE_MS = 40_000L

@RunWith(AndroidJUnit4::class)
class BackgroundConnectTest {
    @get:Rule
    val bluetoothPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_CONNECT)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @LocalEmulatorOnly // Waits past the stack's real direct-connection deadline.
    @Test
    fun backgroundConnectionsToAbsentGlassesWaitPastTheDirectDeadlineUntilCancelled() =
        runBlocking(Dispatchers.Default) {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            val connector = G2GattConnector(context)
            val halo =
                async {
                    HaloConnection(context).connect(adapter.getRemoteDevice(ABSENT_HALO), background = true)
                }
            val arm = async { connector.connect(ABSENT_ARM, background = true) }
            // The same stack ends a direct connection to an absent arm on its own.
            val direct = async { runCatching { connector.connect(ABSENT_DIRECT_ARM, background = false) } }

            delay(PAST_DIRECT_DEADLINE_MS)
            val directFailure = withTimeout(1) { direct.await() }.exceptionOrNull()
            assertTrue("$directFailure", directFailure is G2LinkLostException)
            assertTrue(halo.isActive)
            assertTrue(arm.isActive)

            // Cancelling releases both pending GATT clients without waiting for the glasses.
            withTimeout(5_000) {
                halo.cancelAndJoin()
                arm.cancelAndJoin()
            }
        }
}
