package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** A transparent contact image is absent in both the popup and pager card. */
internal object RearNotificationImages {
    fun hasPixels(bitmap: Bitmap): Boolean = runCatching {
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        (0..15).any { y -> (0..15).any { x ->
            readable.getPixel(x * (readable.width - 1) / 15, y * (readable.height - 1) / 15) ushr 24 > 8
        } }
    }.getOrDefault(false)

    fun avatar(notification: Notification, context: Context): Drawable? =
        runCatching { notification.getLargeIcon()?.loadDrawable(context) }.getOrNull()
            ?.takeIf { it !is BitmapDrawable || hasPixels(it.bitmap) }
}
