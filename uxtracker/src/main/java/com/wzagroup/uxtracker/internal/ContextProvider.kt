package com.wzagroup.uxtracker.internal

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

internal data class LibraryInfo(val name: String, val version: String, val core: String?) {
    val header: String get() = "$name/$version"
}

internal data class AppVersion(val version: String?, val build: String?)

/** Automatically collected `context` (§4.4) and device state the SDK needs. */
internal interface ContextProvider {
    /** A fresh copy each call: events keep the context of the moment they were created. */
    fun context(): JSONObject
    fun appVersion(): AppVersion
    fun isOnline(): Boolean
}

internal class AndroidContextProvider(context: Context, private val library: LibraryInfo) : ContextProvider {

    private val appContext = context.applicationContext
    private val appVersion: AppVersion
    private val staticContext: JSONObject

    init {
        val packageInfo = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val build = packageInfo?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else it.versionCode.toString() }
        appVersion = AppVersion(packageInfo?.versionName, build)

        staticContext = JSONObject().apply {
            put("library", JSONObject().apply {
                put("name", library.name)
                put("version", library.version)
                library.core?.let { put("core", it) }
            })
            put("app", JSONObject().apply {
                put("name", runCatching { appContext.applicationInfo.loadLabel(appContext.packageManager).toString() }.getOrNull())
                put("version", appVersion.version)
                put("build", appVersion.build)
                put("namespace", appContext.packageName)
            })
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("type", deviceType())
            })
            put("os", JSONObject().apply {
                put("name", "Android")
                put("version", Build.VERSION.RELEASE)
            })
            val metrics = appContext.resources.displayMetrics
            put("screen", JSONObject().apply {
                put("width", metrics.widthPixels)
                put("height", metrics.heightPixels)
                put("density", metrics.density.toDouble())
            })
        }
    }

    override fun context(): JSONObject {
        val context = JSONObject(staticContext.toString())
        context.put("locale", Locale.getDefault().toLanguageTag())
        context.put("timezone", TimeZone.getDefault().id)
        network()?.let { context.put("network", it) }
        return context
    }

    override fun appVersion(): AppVersion = appVersion

    override fun isOnline(): Boolean {
        val manager = connectivityManager() ?: return true
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 23) {
            manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            manager.activeNetworkInfo?.isConnected == true
        }
    }

    /** Only when the host app already holds ACCESS_NETWORK_STATE; the SDK never asks for permissions (§4.4). */
    private fun network(): JSONObject? {
        val manager = connectivityManager() ?: return null
        @Suppress("DEPRECATION")
        return runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching null
                JSONObject()
                    .put("wifi", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                    .put("cellular", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            } else {
                val info = manager.activeNetworkInfo ?: return@runCatching null
                JSONObject()
                    .put("wifi", info.type == ConnectivityManager.TYPE_WIFI)
                    .put("cellular", info.type == ConnectivityManager.TYPE_MOBILE)
            }
        }.getOrNull()
    }

    private fun connectivityManager(): ConnectivityManager? {
        val granted = appContext.checkCallingOrSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED
        return if (granted) appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager else null
    }

    private fun deviceType(): String {
        val uiMode = appContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return when {
            uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
            Build.VERSION.SDK_INT >= 20 && uiMode?.currentModeType == Configuration.UI_MODE_TYPE_WATCH -> "watch"
            appContext.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
            else -> "phone"
        }
    }
}
