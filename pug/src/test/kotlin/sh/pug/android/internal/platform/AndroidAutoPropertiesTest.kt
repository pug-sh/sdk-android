// NetworkInfo and its constants are the pre-API 23 path under test.
@file:Suppress("DEPRECATION")

package sh.pug.android.internal.platform

import android.Manifest
import android.app.Application
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import sh.pug.android.internal.RecordingLogger

@RunWith(RobolectricTestRunner::class)
class AndroidAutoPropertiesTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val logger = RecordingLogger()
    private val locale = Locale.getDefault()
    private val timeZone = TimeZone.getDefault()

    @AfterTest
    fun restoreDefaults() {
        Locale.setDefault(locale)
        TimeZone.setDefault(timeZone)
    }

    @Test
    fun `device, OS and app details`() {
        ShadowBuild.setModel("Pixel 8")
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setVersionRelease("16")
        application.applicationInfo.nonLocalizedLabel = "Acme"
        packageInfo().apply {
            versionName = "2.4.0"
            longVersionCode = (1L shl 32) + 7
        }

        val auto = properties()
        val current = auto.current()

        assertEquals("2.4.0", auto.appVersion)
        val expected =
            mapOf(
                "\$os" to "Android",
                "\$osVersion" to "16",
                "\$device" to "Pixel 8",
                "\$deviceModel" to "Pixel 8",
                "\$deviceManufacturer" to "Google",
                "\$deviceType" to "mobile",
                "\$appName" to "Acme",
                "\$appPackage" to application.packageName,
                "\$appVersion" to "2.4.0",
                "\$appBuild" to "4294967303",
            )
        assertEquals(expected, current.filterKeys { it in expected })
        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun `below API 28 the build number is the 32-bit version code`() {
        packageInfo().longVersionCode = (1L shl 32) + 7

        assertEquals("7", properties(sdkInt = 27).current()["\$appBuild"])
    }

    @Test
    fun `an app without a version name has no version to compare`() {
        packageInfo().versionName = null

        val auto = properties()

        assertNull(auto.appVersion)
        assertFalse("\$appVersion" in auto.current())
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the screen in density-independent pixels`() {
        val current = properties().current()

        assertEquals(411, current["\$screenWidth"])
        assertEquals(891, current["\$screenHeight"])
        assertEquals(3.0f, current["\$screenScale"])
        assertEquals(true, current["\$mobile"])
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun `a tablet is not mobile`() {
        val current = properties().current()

        assertEquals("mobile", current["\$deviceType"])
        assertEquals(false, current["\$mobile"])
    }

    @Test
    fun `a TV is a tv, and not mobile`() {
        shadowOf(application.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)

        val current = properties().current()

        assertEquals("tv", current["\$deviceType"])
        assertEquals(false, current["\$mobile"])
    }

    @Test
    fun `locale and time zone are read for each event`() {
        val auto = properties()

        Locale.setDefault(Locale.CANADA_FRENCH)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        val before = auto.current()
        Locale.setDefault(Locale.JAPAN)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val after = auto.current()

        assertEquals("fr-CA", before["\$locale"])
        assertEquals("America/Toronto", before["\$timezone"])
        assertEquals("ja-JP", after["\$locale"])
        assertEquals("Asia/Tokyo", after["\$timezone"])
    }

    @Test
    fun `the network type is left out without ACCESS_NETWORK_STATE`() {
        assertFalse("\$networkType" in properties().current())
    }

    @Test
    fun `the network type prefers the transport under a VPN`() {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        val auto = properties()

        val types =
            listOf(
                listOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI),
                listOf(NetworkCapabilities.TRANSPORT_CELLULAR),
                listOf(NetworkCapabilities.TRANSPORT_ETHERNET),
                listOf(NetworkCapabilities.TRANSPORT_VPN),
                listOf(NetworkCapabilities.TRANSPORT_BLUETOOTH),
                listOf(NetworkCapabilities.TRANSPORT_USB),
            ).map { transports ->
                useTransports(transports)
                auto.current()["\$networkType"]
            }

        assertEquals(listOf("wifi", "mobile", "ethernet", "vpn", "bluetooth", "other"), types)
    }

    @Test
    fun `below API 23 the network type comes from the active network info`() {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        val auto = properties(sdkInt = 22)
        val connectivity = shadowOf(connectivityManager())

        val types =
            listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIMAX).map {
                connectivity.setActiveNetworkInfo(networkInfo(it, NetworkInfo.State.CONNECTED))
                auto.current()["\$networkType"]
            }
        connectivity.setActiveNetworkInfo(networkInfo(ConnectivityManager.TYPE_WIFI, NetworkInfo.State.DISCONNECTED))
        val disconnected = auto.current()["\$networkType"]
        connectivity.setActiveNetworkInfo(null)
        val offline = auto.current()["\$networkType"]

        assertEquals(listOf("wifi", "mobile", "other"), types)
        assertEquals("none", disconnected)
        assertEquals("none", offline)
    }

    @Test
    fun `a value that cannot be read is left out and logged once`() {
        val broken =
            object : ContextWrapper(application) {
                override fun getResources(): Resources = throw IllegalStateException("no resources")
            }
        val auto = AndroidAutoProperties(broken, sdkInt = 36, logger.safe())

        auto.current()
        val current = auto.current()

        assertFalse("\$screenWidth" in current)
        assertFalse("\$mobile" in current)
        assertEquals("Android", current["\$os"])
        assertEquals(listOf("Auto-properties are missing the display: reading it failed"), logger.warnings)
    }

    private fun properties(sdkInt: Int = 36) = AndroidAutoProperties(application, sdkInt, logger.safe())

    private fun packageInfo() =
        shadowOf(application.packageManager).getInternalMutablePackageInfo(application.packageName)

    private fun connectivityManager() = application.getSystemService(ConnectivityManager::class.java)

    private fun useTransports(transports: List<Int>) {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        transports.forEach { shadowOf(capabilities).addTransportType(it) }
        val manager = connectivityManager()
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
    }

    private fun networkInfo(type: Int, state: NetworkInfo.State): NetworkInfo {
        val detailed =
            if (state ==
                NetworkInfo.State.CONNECTED
            ) {
                NetworkInfo.DetailedState.CONNECTED
            } else {
                NetworkInfo.DetailedState.DISCONNECTED
            }
        return ShadowNetworkInfo.newInstance(detailed, type, 0, true, state)
    }
}
