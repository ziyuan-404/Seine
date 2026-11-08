package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.service.notification.StatusBarNotification
import android.view.View
import java.util.WeakHashMap

/** App colours belong to the card; the OEM pager still owns clipping and transitions. */
internal object NativeRearCardTheme {
    private class Theme(val original: Drawable?) {
        var packageName = ""
        var colour: Int? = null
        var asleep = false
        var appliedColour: Int? = null
        var appliedAsleep = false
        val background = GradientDrawable().apply { orientation = GradientDrawable.Orientation.TOP_BOTTOM }
    }
    private val cards = WeakHashMap<View, Theme>()
    private val palette = mutableMapOf<String, Int?>()
    private val pending = mutableSetOf<String>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var generation = 0

    fun bind(card: View, sbn: StatusBarNotification) = bind(card, sbn.packageName,
        !sbn.notification.extras.getString("miui.focus.param").isNullOrBlank())

    fun bind(card: View, packageName: String, islandAdapted: Boolean = false) {
        val theme = cards.getOrPut(card) { Theme(card.background) }
        theme.packageName = packageName
        val colour = when (packageName) {
            "com.miui.securitycenter", "com.tencent.mm" -> 0xff009c50.toInt()
            "com.tencent.mobileqq" -> 0xff12b7f5.toInt()
            "com.android.deskclock" -> 0xff3678e5.toInt()
            "com.android.soundrecorder" -> 0xffcd3508.toInt()
            else -> palette[packageName]
        }
        theme.colour = colour
        apply(card, theme)
        // Only island-adapted sources opt into the ROM's palette. Ordinary
        // notifications retain their original host background, including when
        // the reusable popup previously belonged to QQ.
        if (colour == null && !palette.containsKey(packageName) &&
            islandAdapted && pending.add(packageName)) {
            val context = card.context.applicationContext
            val expected = generation
            android.os.AsyncTask.THREAD_POOL_EXECUTOR.execute {
                val resolved = runCatching {
                    val loader = context.classLoader
                    loader.loadClass("miuix.mipalette.MiPalette").getMethod("init").invoke(null)
                    val icon = context.packageManager.getApplicationIcon(packageName)
                    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                    try {
                        icon.setBounds(0, 0, 96, 96); icon.draw(Canvas(bitmap))
                        val calculator = loader.loadClass("com.miui.maml.data.palette.PaletteCalculator")
                        val seed = calculator.getMethod("getMainColor", Bitmap::class.java, Rect::class.java)
                            .invoke(null, bitmap, null) as Int
                        check(seed != 0)
                        calculator.getMethod("getPaletteColor", Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                            .invoke(null, seed, "primary", 10) as Int
                    } finally { bitmap.recycle() }
                }.getOrNull()
                main.post {
                    if (generation != expected) return@post
                    pending.remove(packageName); palette[packageName] = resolved
                    cards.forEach { (view, current) ->
                        if (current.packageName == packageName) { current.colour = resolved; apply(view, current) }
                    }
                }
            }
        }
    }

    fun aod(card: View, asleep: Boolean) {
        if (NativeRearTimerScene.aod(card, asleep)) {
            card.background = null
            return
        }
        cards[card]?.let { if (it.asleep != asleep) { it.asleep = asleep; apply(card, it) } }
    }

    private fun apply(card: View, theme: Theme) {
        val colour = theme.colour ?: run { card.background = theme.original; return }
        if (theme.appliedColour == colour && theme.appliedAsleep == theme.asleep && card.background === theme.background) return
        theme.appliedColour = colour; theme.appliedAsleep = theme.asleep
        theme.background.colors = if (theme.asleep) intArrayOf(Color.BLACK, Color.rgb(
            Color.red(colour) / 5, Color.green(colour) / 5, Color.blue(colour) / 5)) else intArrayOf(Color.BLACK, colour)
        if (card.background !== theme.background) card.background = theme.background
    }

    fun forget(card: View) {
        cards.remove(card)?.let { card.background = it.original }
    }

    fun clear() {
        cards.keys.toList().forEach(::forget)
        palette.clear(); pending.clear(); generation++
    }
}
