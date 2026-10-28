package sh.pug.android.internal.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import sh.pug.android.internal.logging.SafeLogger

// Android's own line between phone and tablet layouts.
private const val TABLET_MIN_WIDTH_DP = 600

// PackageManager.FEATURE_TELEVISION, deprecated in favor of FEATURE_LEANBACK but still the only one
// some older TVs report.
private const val FEATURE_TELEVISION = "android.hardware.type.television"

/**
 * The SDK's auto-properties. Device and app details are read once, at construction;
 * screen size, locale, time zone and network type for each event, since they change while the app
 * runs. A value that cannot be read is left out and logged once.
 */
internal class AndroidAutoProperties(
    private val context: Context,
    private val sdkInt: Int,
    private val logger: SafeLogger,
) : AutoProperties {
    private val fixed = LinkedHashMap<String, Any?>()
    private val warned = HashSet<String>()
    private val isTv: Boolean
    private val connectivity: ConnectivityManager?

    override val appVersion: String?

    init {
        fixed["\$os"] = "Android"
        Build.VERSION.RELEASE?.takeIf { it.isNotEmpty() }?.let { fixed["\$osVersion"] = it }
        Build.MODEL?.takeIf { it.isNotEmpty() }?.let {
            fixed["\$device"] = it
            fixed["\$deviceModel"] = it
        }
        Build.MANUFACTURER?.takeIf { it.isNotEmpty() }?.let { fixed["\$deviceManufacturer"] = it }
        isTv = read("device features") {
            val packageManager = context.packageManager
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                packageManager.hasSystemFeature(FEATURE_TELEVISION)
        } == true
        fixed["\$deviceType"] = if (isTv) "tv" else "mobile"
        appVersion = readApp()
        connectivity = read("network state") {
            val granted =
                context.checkPermission(Manifest.permission.ACCESS_NETWORK_STATE, Process.myPid(), Process.myUid()) ==
                    PackageManager.PERMISSION_GRANTED
            if (granted) context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager else null
        }
    }

    override fun current(): Map<String, Any?> {
        val properties = LinkedHashMap(fixed)
        read("locale") { properties["\$locale"] = Locale.getDefault().toLanguageTag() }
        read("time zone") { properties["\$timezone"] = TimeZone.getDefault().id }
        read("display") {
            val resources = context.resources
            val metrics = resources.displayMetrics
            if (metrics.density > 0) {
                properties["\$screenWidth"] = (metrics.widthPixels / metrics.density).roundToInt()
                properties["\$screenHeight"] = (metrics.heightPixels / metrics.density).roundToInt()
                properties["\$screenScale"] = metrics.density
            }
            properties["\$mobile"] = !isTv && resources.configuration.smallestScreenWidthDp < TABLET_MIN_WIDTH_DP
        }
        connectivity?.let { manager -> read("network type") { properties["\$networkType"] = networkType(manager) } }
        return properties
    }

    private fun readApp(): String? {
        val packageName: String = context.packageName ?: return null
        fixed["\$appPackage"] = packageName
        val packageManager = context.packageManager
        read("app name") { context.applicationInfo.loadLabel(packageManager).toString() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { fixed["\$appName"] = it }
        val info = read("package info") { packageInfo(packageManager, packageName) } ?: return null
        fixed["\$appBuild"] = versionCode(info).toString()
        val version = info.versionName?.takeIf { it.isNotEmpty() } ?: return null
        fixed["\$appVersion"] = version
        return version
    }

    // The flags overload is API 33; this one works on every level.
    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo =
        packageManager.getPackageInfo(packageName, 0)

    // sdkInt is Build.VERSION.SDK_INT, injected so tests reach both branches.
    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long {
        if (sdkInt >= 28) return info.longVersionCode
        return info.versionCode.toLong()
    }

    // ACCESS_NETWORK_STATE was found granted at construction; the SDK does not declare it. sdkInt as above.
    @SuppressLint("MissingPermission", "NewApi")
    @Suppress("DEPRECATION")
    private fun networkType(manager: ConnectivityManager): String {
        if (sdkInt >= 23) {
            val network = manager.activeNetwork ?: return "none"
            val capabilities = manager.getNetworkCapabilities(network) ?: return "unknown"
            // A VPN network also carries its underlying transport, which says more (Flutter's order).
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                else -> "other"
            }
        }
        val info = manager.activeNetworkInfo
        if (info == null || !info.isConnected) return "none"
        return when (info.type) {
            ConnectivityManager.TYPE_WIFI -> "wifi"

            ConnectivityManager.TYPE_MOBILE,
            ConnectivityManager.TYPE_MOBILE_DUN,
            ConnectivityManager.TYPE_MOBILE_HIPRI,
            -> "mobile"

            ConnectivityManager.TYPE_ETHERNET -> "ethernet"

            ConnectivityManager.TYPE_VPN -> "vpn"

            ConnectivityManager.TYPE_BLUETOOTH -> "bluetooth"

            else -> "other"
        }
    }

    // Nothing here suspends, so whatever a read throws is that read's failure; catching it whole
    // keeps the other properties and the event.
    private inline fun <T> read(what: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        if (warned.add(what)) logger.warn("Auto-properties are missing the $what: reading it failed", t)
        null
    }
}
