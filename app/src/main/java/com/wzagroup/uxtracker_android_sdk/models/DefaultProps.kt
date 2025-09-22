package com.wzagroup.uxtracker_android_sdk.models

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.wzagroup.uxtracker_android_sdk.utils.apiUrl
import java.util.*

internal object DefaultProperties {

    fun collect(context: Context, sessionId: String, distinctId: String): Map<String, Any> {
        val packageManager = context.packageManager
        val packageName = context.packageName

        var appName = "Unknown"
        var appVersion = "Unknown"
        var appBuildNumber = "Unknown"

        try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            appName = packageManager.getApplicationLabel(applicationInfo).toString()

            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            appVersion = packageInfo.versionName ?: "Unknown"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                appBuildNumber = packageInfo.longVersionCode.toString()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

        val locale = Locale.getDefault()
        val countryCode = locale.country.ifEmpty { "unknown" }
        val country = locale.displayCountry.ifEmpty { "unknown" }

        return mapOf(
            "distinctid" to distinctId,
            "sessionid" to sessionId,
            "apiendpoint" to apiUrl,
            "operatingsystem" to "Android",
            "osversion" to (Build.VERSION.RELEASE ?: "unknown"),
            "devicemodel" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "appname" to appName,
            "appversion" to appVersion,
            "appbuildnumber" to appBuildNumber,
            "platform" to "Android",
            "country" to country,
            "apitimestamp" to Date().toInstant().toString()
        )
    }
}
