package hk.uwu.reareye.ui.config

import android.content.pm.PackageManager

internal object RearNotificationDefaults {
    const val ENABLED = true
    const val FACE_ANIMATION = 1
    private const val ALL_APPS = "*"
    val APPS: Set<String> = setOf(ALL_APPS)

    fun includes(selection: Set<String>, packageName: String): Boolean =
        ALL_APPS in selection || packageName in selection

    fun resolve(selection: Set<String>, packageManager: PackageManager): Set<String> =
        if (ALL_APPS in selection) {
            packageManager.getInstalledApplications(0).mapTo(linkedSetOf()) { it.packageName }
        } else selection
}
