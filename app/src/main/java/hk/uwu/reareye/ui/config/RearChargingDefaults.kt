package hk.uwu.reareye.ui.config

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Shared defaults for rear-screen charging animation settings (UI and Hook). */
internal object RearChargingDefaults {
    const val ENABLED = true
    const val PERSISTENT = true
    const val SCREEN_OFF = true

    const val STYLE_MRSS = 0
    const val STYLE_GLOW = 1
    const val STYLE_LEICA_RED = 2
    const val STYLE_LEICA_GREEN = 3

    private const val LEICA_CAMERA_ADAPTER_PACKAGE = "com.kobbin.leicaflavor"
    private val LEICA_CAMERA_PROPS = arrayOf(
        "sys.leica_camera.profile",
        "sys.leica_camera.runtime",
        "sys.leica_camera.guard",
    )

    fun isLeicaCameraModuleInstalled(context: Context? = null): Boolean {
        if (hasLeicaCameraSystemProperty()) return true
        if (context != null && isPackageInstalled(context.packageManager, LEICA_CAMERA_ADAPTER_PACKAGE)) {
            return true
        }
        return false
    }

    fun defaultStyle(context: Context? = null): Int =
        if (isLeicaCameraModuleInstalled(context)) STYLE_LEICA_RED else STYLE_MRSS

    private fun hasLeicaCameraSystemProperty(): Boolean = runCatching {
        val spClass = Class.forName("android.os.SystemProperties")
        val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
        LEICA_CAMERA_PROPS.any { prop ->
            (getMethod.invoke(null, prop, "") as? String)?.isNotBlank() == true
        }
    }.getOrDefault(false)

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        true
    }.getOrDefault(false)
}
