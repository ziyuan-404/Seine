package hk.uwu.reareye.utils.other

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import hk.uwu.reareye.utils.RootHelper.executeRootCommand
import hk.uwu.reareye.utils.RootHelper.hasRootAccess

object DeviceConfigTools {

    // Avoid running blocking root shell commands at class initialization.
    val deviceName: String by lazy {
        if (hasRootAccess()) {
            val result = executeRootCommand("getprop persist.private.device_name")
            if (result.first == 0) result.second else "No Root Permission"
        } else {
            "No Root Permission"
        }
    }


    val androidVersion: String = getSystemProperties("ro.build.version.release")

    val marketName by lazy {

        val marketName: String = getSystemProperties("ro.product.marketname")

        if (marketName.isNotEmpty()) titleFirstChar(marketName) else titleFirstChar(Build.BRAND) + " " + Build.MODEL

    }

    fun titleFirstChar(st: String): String {
        val formattedBrand = st.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        return formattedBrand
    }

    @SuppressLint("PrivateApi")

    fun getSystemProperties(key: String): String {
        val ret: String = try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java).invoke(null, key) as String
        } catch (iAE: IllegalArgumentException) {
            throw iAE
        } catch (_: Exception) {
            ""
        }
        return ret
    }

    fun getPackageVersion(context: Context, packageName: String): String? =
        try {
            context.packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
}
