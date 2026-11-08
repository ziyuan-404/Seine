package hk.uwu.reareye.utils

import android.app.Notification
import android.content.Context
import android.provider.Settings

/** One source of truth shared with HyperOS, without a parallel module switch. */
internal object NativeNotificationPrivacy {
    private val instantMessageApps = setOf(
        "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.qqlite", "com.tencent.tim",
        "com.tencent.wework", "com.alibaba.android.rimet", "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.thunderdog.challegram", "org.thoughtcrime.securesms",
        "com.facebook.orca", "com.discord", "com.Slack", "jp.naver.line.android", "com.viber.voip",
        "com.android.mms", "com.google.android.apps.messaging",
    )
    fun isInstantMessageApp(packageName: String): Boolean = packageName in instantMessageApps

    const val DEFAULT_ENABLED = true
    private const val DEFAULT_FACE_DETAILS = 1
    const val FACE_DETAILS = "face_unlock_success_show_message"
    const val REAR_DETAILS = "sub_screen_notification_detail_enable"
    const val ALLOW_PRIVATE = "lock_screen_allow_private_notifications"
    const val REAR_ENABLED = "sub_screen_notification_enable"
    val settings = listOf(FACE_DETAILS, REAR_DETAILS, ALLOW_PRIVATE, REAR_ENABLED, "lock_screen_show_notifications")

    data class Policy(val showNotifications: Boolean, val allowPrivate: Boolean, val hidesDetails: Boolean)
    fun policy(context: Context): Policy {
        val resolver = context.contentResolver
        // Same defaults and dependencies as the OEM notification settings page.
        val allow = Settings.Secure.getInt(resolver, ALLOW_PRIVATE, 1) == 1
        return Policy(Settings.Secure.getInt(resolver, "lock_screen_show_notifications", 1) != 0 &&
            Settings.Secure.getInt(resolver, REAR_ENABLED, 0) == 1, allow,
            faceRequired(context) ||
                Settings.Secure.getInt(resolver, REAR_DETAILS, 0) == 0 || !allow)
    }

    fun faceRequired(context: Context) = Settings.Secure.getInt(context.contentResolver, FACE_DETAILS, DEFAULT_FACE_DETAILS) != 0
    fun hidesDetails(context: Context) = faceRequired(context) ||
        Settings.Secure.getInt(context.contentResolver, REAR_DETAILS, 0) == 0 ||
        Settings.Secure.getInt(context.contentResolver, ALLOW_PRIVATE, 1) != 1

    private val messageCategories = setOf(Notification.CATEGORY_MESSAGE, Notification.CATEGORY_EMAIL, Notification.CATEGORY_SOCIAL)
    private val messageApps = setOf(
        "com.tencent.mm", "com.tencent.mobileqq", "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.thunderdog.challegram", "com.android.mms",
        "com.google.android.apps.messaging", "com.google.android.gm", "com.tencent.androidqqmail",
        "com.microsoft.office.outlook", "com.Slack", "jp.naver.line.android", "com.viber.voip",
    )
    fun isMessage(packageName: String, notification: Notification): Boolean =
        notification.category in messageCategories ||
            notification.extras.getString("android.template").orEmpty().contains("MessagingStyle") ||
            notification.extras.containsKey("android.messages") || packageName in messageApps

    fun placeholder(context: Context): String = runCatching {
        val source = context.createPackageContext("com.xiaomi.subscreencenter", 0)
        source.getString(source.resources.getIdentifier("sub_screen_private_notification", "string", source.packageName))
    }.getOrDefault("有一条新通知")
}
