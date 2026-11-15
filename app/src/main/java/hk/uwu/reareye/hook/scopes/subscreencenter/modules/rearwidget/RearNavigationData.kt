package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.graphics.Bitmap
import android.app.Notification
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.content.Context
import android.os.Bundle
import android.text.Html
import org.json.JSONObject

internal data class RearNavigationData(
    val packageName: String,
    val title: String,
    val detail: String,
    val arrival: String,
    val progress: Float?,
    val segments: List<Segment>,
    val stations: List<Float>,
    val arrow: Drawable?,
    val tracker: Drawable?,
) {
    data class Segment(val fraction: Float, val color: Int)

    companion object {
        val packages = setOf("com.google.android.apps.maps", "com.autonavi.minimap", "com.baidu.BaiduMap")
        private val navigationPattern = Regex(
            "导航|转弯|直行|前行|行驶|步行|下车|上车|开往|方向|到站|turn|head |toward|continue|arriv|乗換",
            RegexOption.IGNORE_CASE)
        private val arrivalPattern = Regex("到达|抵达|预计|arrival|arrive|\\bETA\\b|到着", RegexOption.IGNORE_CASE)
        private val arrivalTimePattern = Regex("\\d{1,2}:\\d{2}(\\s*[APap][Mm])?")
        private val lineBreakPattern = Regex("[\\r\\n]+")

        fun read(context: Context, extras: Bundle): RearNavigationData? {
            val live = json(extras.getString("miui.liveupdate.param"))
            val pkg = extras.getString("package_name")?.takeIf { it.isNotBlank() }
                ?: live.optString("lu_package_name")
            if (pkg !in packages) return null
            val focus = json(extras.getString("miui.focus.param"))
            val rear = json(extras.getString("miui.rear.param"))
            val v2 = focus.optJSONObject("param_v2") ?: rear.optJSONObject("param_v2") ?: focus
            val base = v2.optJSONObject("baseInfo") ?: rear.optJSONObject("baseInfo") ?:
                if (rear.has("title")) rear else v2
            val title = clean(live.optString("lu_title").ifBlank { base.optString("title") }
                .ifBlank { extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() })
            val text = clean(live.optString("lu_text").ifBlank { base.optString("content") }
                .ifBlank { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() })
            val sub = clean(live.optString("lu_sub_text").ifBlank { base.optString("subContent") }
                .ifBlank { extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty() })
            val category = live.optString("lu_category")
            val channel = live.optString("lu_channel_id")
            val business = extras.getString("business").orEmpty()
            val navigation = category == "navigation" || channel.contains("navigation", true) ||
                business.contains("navi", true) ||
                navigationPattern.containsMatchIn("$title $text $sub")
            if (!navigation || title.isBlank()) return null
            val arrival = listOf(sub, text).firstOrNull { arrivalPattern.containsMatchIn(it) ||
                arrivalTimePattern.matches(it) }.orEmpty()
            val detail = listOf(text, sub).filter { it.isNotBlank() && it != arrival && it != title }
                .distinct().joinToString(" · ")
            val max = live.optDouble("lu_progress_max", 0.0)
            val progress = if (max > 0 && !live.optBoolean("lu_progress_indeterminate"))
                (live.optDouble("lu_progress", 0.0) / max).toFloat().coerceIn(0f, 1f) else null
            val segments = (0 until live.optInt("lu_progress_segments_count").coerceIn(0, 100)).map { index ->
                Segment(if (max > 0) (live.optDouble("lu_progress_segments_${index}_length") / max).toFloat() else 0f,
                    live.optLong("lu_progress_segments_${index}_color_int", 0xff4285f4).toInt())
            }
            val stations = if (max > 0) (0 until live.optInt("lu_progress_points_count").coerceIn(0, 200))
                .map { (live.optDouble("lu_progress_points_${it}_position") / max).toFloat().coerceIn(0f, 1f) }
                else emptyList()
            val pics = extras.getBundle("miui.rear.pics") ?: extras.getBundle("miui.focus.pics")
            fun picture(key: String): Drawable? = when (val image = pics?.get(key)) {
                is Icon -> runCatching { image.loadDrawable(context) }.getOrNull()
                is Bitmap -> android.graphics.drawable.BitmapDrawable(context.resources, image)
                else -> null
            }
            return RearNavigationData(pkg, title, detail, arrival, progress, segments, stations,
                picture("lu_large_icon") ?: picture("focus_large_icon") ?: picture(focus.optString("tickerPic")),
                picture("lu_progress_tracker_icon"))
        }

        private fun json(value: String?) = runCatching { JSONObject(value.orEmpty()) }.getOrElse { JSONObject() }
        private fun clean(value: String) = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
            .toString().replace(lineBreakPattern, " ").trim().takeUnless { it == "null" }.orEmpty()
    }
}
