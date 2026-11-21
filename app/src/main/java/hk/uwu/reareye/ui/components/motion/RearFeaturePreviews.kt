package hk.uwu.reareye.ui.components.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.config.ConfigCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import ui.feice.mochi.theme.MochiTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private fun easeInOutCubic(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
}

private fun easeOutCubic(t: Float): Float {
    val x = 1f - t.coerceIn(0f, 1f)
    return 1f - x * x * x
}

private fun smoothstepRange(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpFloat(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * x,
        green = a.green + (b.green - a.green) * x,
        blue = a.blue + (b.blue - a.blue) * x,
        alpha = a.alpha + (b.alpha - a.alpha) * x,
    )
}

/**
 * Cached Android Paint & RectF helpers to draw crisp native text/bitmaps inside Compose Canvas
 * without per-frame allocations.
 */
private class PreviewTextPaints {
    val bold: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    val medium: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val regular: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    val monoBold: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }
    val leicaBrand: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        letterSpacing = 0.14f
    }
    val bitmapPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val shaderPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    val dstRect: RectF = RectF()
}

private fun DrawScope.drawCenteredText(
    paint: Paint,
    text: String,
    cx: Float,
    cy: Float,
    textSizePx: Float,
    color: Color,
    align: Paint.Align = Paint.Align.CENTER,
) {
    if (color.alpha <= 0.005f || textSizePx <= 0.5f) return
    paint.textSize = textSizePx
    paint.color = color.toArgb()
    paint.textAlign = align
    val fm = paint.fontMetrics
    val baseline = cy - (fm.ascent + fm.descent) * 0.5f
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(text, cx, baseline, paint)
    }
}

// =========================================================================
// Exact AGSL RuntimeShaders from RearFaceFeedback.kt & RearChargingLiquidView.kt
// =========================================================================

private object FaceHaloShaderSpec {
    val innerHeads = floatArrayOf(
        -45f, -17f, 9f, 22f, 42f, 57f, 70f, 90f, 105f, 120f, 140f, 153f, 179f, 210f, 233f, 259f,
        286f, 311f, 336f, 358f, 370f, 390f, 404f, 422f, 441f, 456f, 462f, 480f, 488f, 504f, 519f, 530f,
    )
    val innerTails = floatArrayOf(
        -76f, -60f, -59f, -53f, -43f, -36f, -23f, -9f, 9f, 23f, 49f, 74f, 93f, 112f, 137f, 141f,
        150f, 160f, 164f, 170f, 170f, 170f, 170f, 170f, 170f, 170f, 170f, 170f, 170f, 170f, 170f, 170f,
    )
    val outerHeads = floatArrayOf(
        -60f, -30f, 1f, 24f, 44f, 64f, 79f, 104f, 117f, 139f, 157f, 183f, 201f, 221f, 245f, 269f,
        286f, 308f, 332f, 351f, 371f, 393f, 411f, 435f, 448f, 468f, 479f, 500f, 510f, 510f, 510f, 510f,
    )
    val outerTails = floatArrayOf(
        -90f, -72f, -56f, -53f, -44f, -35f, -23f, -11f, 11f, 25f, 46f, 65f, 81f, 99f, 112f, 121f,
        130f, 135f, 142f, 147f, 150f, 150f, 150f, 150f, 150f, 150f, 150f, 150f, 150f, 150f, 150f, 150f,
    )
    val travelInner = floatArrayOf(0f, 11f, 21f, 30f, 39f, 45f, 48f, 51f, 54f, 57f, 59f, 60f, 60f, 60f, 60f, 60f)
    val travelOuter = floatArrayOf(0f, 16f, 24f, 29f, 36f, 41f, 47f, 51f, 57f, 58f, 59f, 59f, 59f, 59f, 59f, 59f)
    val remainingInner = floatArrayOf(1f, .933f, .872f, .783f, .689f, .592f, .481f, .419f, .364f, .339f, .286f, .264f, .214f, .183f, .05f, 0f)
    val remainingOuter = floatArrayOf(1f, .956f, .875f, .775f, .70f, .622f, .561f, .489f, .45f, .411f, .35f, .292f, .231f, .106f, .047f, 0f)

    fun sample(values: FloatArray, progress: Float): Float {
        val x = progress.coerceIn(0f, 1f) * (values.size - 1)
        val i = x.toInt().coerceAtMost(values.lastIndex - 1)
        val t = x - i
        val start = values[i]
        val end = values[i + 1]
        val step = end - start
        fun tangent(before: Float, after: Float): Float =
            if (before * after <= 0f) 0f else 2f * before * after / (before + after)
        val startSlope = if (i == 0) step else tangent(start - values[i - 1], step)
        val endSlope = if (i + 1 == values.lastIndex) step else tangent(step, values[i + 2] - end)
        val t2 = t * t
        return start + t2 * (3f - 2f * t) * step +
            t * (1f - t) * ((1f - t) * startSlope - t * endSlope)
    }

    const val AGSL_SOURCE = """
        uniform float2 center;
        uniform float radius;
        uniform float feather;
        uniform float4 arcs;
        float arc(float degrees, float tail, float head) {
            float extent = clamp(head - tail, 0.0, 360.0);
            if (extent < 0.1) return 0.0;
            if (extent > 359.5) return 1.0;
            float p = mod(degrees - tail + 1080.0, 360.0);
            float soft = min(24.0, extent * 0.28);
            return smoothstep(0.0, max(0.1, soft), p)
                * (1.0 - smoothstep(max(0.0, extent-soft), extent, p));
        }
        half4 main(float2 p) {
            float2 delta = p - center;
            float distance = length(delta) - radius;
            float inner = exp(-0.5 * pow((distance - feather * 0.22) / (feather * 0.43), 2.0));
            float outer = exp(-0.5 * pow((distance - feather * 0.70) / feather, 2.0));
            float degrees = atan(delta.y, delta.x) * 57.2957795;
            float core = inner * arc(degrees, arcs.x, arcs.y) * 0.83;
            float halo = outer * arc(degrees, arcs.z, arcs.w) * 0.48;
            float noise = fract(sin(dot(p, float2(12.9898,78.233))) * 43758.5453) - 0.5;
            float a = clamp(core + halo + noise / 255.0, 0.0, 1.0);
            half3 tint = mix(half3(0.78, 1.0, 0.91), half3(1.0), half(core / max(0.001, core+halo)));
            return half4(tint * half(a), half(a));
        }
    """
}

private object GlowChargingShaderSpec {
    val ringRadius = floatArrayOf(.10f, .11f, .13f, .16f, .19f, .22f, .28f, .35f, .44f, .56f, .69f, .81f, .94f, 1.06f, 1.15f, 1.22f, 1.25f)
    val ringOpacity = floatArrayOf(0f, .025f, .05f, .08f, .13f, .20f, .28f, .34f, .34f, .29f, .24f, .19f, .13f, .08f, .04f, .01f, 0f)

    fun sample(values: FloatArray, progress: Float): Float {
        val x = progress.coerceIn(0f, 1f) * values.lastIndex
        val i = x.toInt()
        val t = x - i
        fun at(n: Int) = values[n.coerceIn(0, values.lastIndex)]
        val a = at(i - 1); val b = at(i); val c = at(i + 1); val d = at(i + 2)
        return .5f * (2f * b + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t * t + (-a + 3f * b - 3f * c + d) * t * t * t)
    }

    const val LIQUID_AGSL = """
        uniform float2 size;
        uniform float surface;
        uniform float phase;
        uniform float reveal;
        uniform float2 textCenter;
        uniform float2 textRadius;
        half4 main(float2 p) {
            float line = surface + sin(p.x / size.x * 6.283185 + phase) * size.y * 0.028
                + sin(p.x / size.x * 3.141593 - phase * 0.5) * size.y * 0.009;
            float depth = p.y - line;
            float water = smoothstep(-0.8, 0.8, depth);
            float haze = 0.72 * exp(min(depth, 0.0) / (size.y * 0.15));
            float alpha = mix(haze, 1.0, water) * reveal;
            half3 colour = mix(half3(0.035, 0.40, 0.21), half3(0.0, 0.98, 0.73), half(water));
            float2 textOffset = (p - textCenter) / textRadius;
            colour *= half(1.0 - 0.58 * exp(-dot(textOffset, textOffset)));
            return half4(colour * half(alpha), half(alpha));
        }
    """

    const val HALO_AGSL = """
        uniform float2 center;
        uniform float radius;
        uniform float3 tint;
        half4 main(float2 p) {
            float d = length(p - center) / radius;
            float noise = fract(sin(dot(p,float2(12.9898,78.233))) * 43758.5453) - 0.5;
            float a = clamp(exp(-d*d*2.5) * 0.54 + noise / 255.0, 0.0, 1.0);
            return half4(half3(tint) * half(a), half(a));
        }
    """

    const val RIPPLE_AGSL = """
        uniform float2 center;
        uniform float radius;
        uniform float feather;
        half4 main(float2 p) {
            float d = (length(p - center) - radius) / feather;
            float a = exp(-d*d*0.5);
            return half4(half3(0.88, 1.0, 0.95) * half(a), half(a));
        }
    """
}

private class GlowChargingShaders(
    val liquid: Any?,
    val halo: Any?,
    val ripple: Any?,
)

@Composable
private fun rememberGlowChargingShaders(): GlowChargingShaders = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GlowChargingShaders(
            liquid = runCatching { RuntimeShader(GlowChargingShaderSpec.LIQUID_AGSL) }.getOrNull(),
            halo = runCatching { RuntimeShader(GlowChargingShaderSpec.HALO_AGSL) }.getOrNull(),
            ripple = runCatching { RuntimeShader(GlowChargingShaderSpec.RIPPLE_AGSL) }.getOrNull(),
        )
    } else {
        GlowChargingShaders(null, null, null)
    }
}

/** Real Leica charging artwork loaded directly from assets/charging/leica/. */
private class LeicaChargingAssets(
    val glowRed: AnimatedImageDrawable?,
    val glowGreen: AnimatedImageDrawable?,
    val glyphs: List<Bitmap>,
    val icons: List<Bitmap>,
)

private val LeicaGreenTintFilter = ColorMatrixColorFilter(
    floatArrayOf(
        0f, .75f, .25f, 0f, 0f,
        1f, 0f, 0f, 0f, 0f,
        .58f, 0f, .42f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

private fun loadLeicaChargingAssets(context: Context): LeicaChargingAssets {
    val assets = context.assets
    fun decodeGlow(green: Boolean): AnimatedImageDrawable? = runCatching {
        val source = ImageDecoder.createSource(assets, "charging/leica/glow.webp")
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
            decoder.setTargetSize(488, 298)
        } as? AnimatedImageDrawable
        drawable?.apply {
            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            if (green) colorFilter = LeicaGreenTintFilter
        }
    }.getOrNull()

    fun decodePng(name: String): Bitmap? = runCatching {
        assets.open("charging/leica/$name.png").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    val glyphs = "0123456789b".mapNotNull { decodePng("num_$it") }
    val icons = listOfNotNull(decodePng("icon_0"), decodePng("icon_1"))
    return LeicaChargingAssets(
        glowRed = decodeGlow(green = false),
        glowGreen = decodeGlow(green = true),
        glyphs = glyphs,
        icons = icons,
    )
}

@Composable
private fun rememberLeicaChargingAssets(): LeicaChargingAssets? {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf<LeicaChargingAssets?>(null) }

    LaunchedEffect(context) {
        val assets = withContext(Dispatchers.IO) { loadLeicaChargingAssets(context) }
        assets.glowRed?.start()
        assets.glowGreen?.start()
        loaded = assets
    }

    DisposableEffect(Unit) {
        onDispose {
            loaded?.glowRed?.stop()
            loaded?.glowGreen?.stop()
        }
    }
    return loaded
}

private data class SmoothGlowState(
    val innerTail: Float,
    val innerHead: Float,
    val outerTail: Float,
    val outerHead: Float,
    val alpha: Float,
)

private fun computeFaceHaloState(
    t: Float,
    scanStart: Float,
    exitStart: Float,
    exitEnd: Float,
): SmoothGlowState {
    if (t <= scanStart || t >= exitEnd) {
        return SmoothGlowState(0f, 0f, 0f, 0f, 0f)
    }
    if (t < exitStart) {
        val u = ((t - scanStart) / (exitStart - scanStart)).coerceIn(0f, 1f)
        val entryFade = smoothstepRange(0f, 0.22f, u)
        val innerH = FaceHaloShaderSpec.sample(FaceHaloShaderSpec.innerHeads, u)
        val innerT = FaceHaloShaderSpec.sample(FaceHaloShaderSpec.innerTails, u)
        val outerH = FaceHaloShaderSpec.sample(FaceHaloShaderSpec.outerHeads, u)
        val outerT = FaceHaloShaderSpec.sample(FaceHaloShaderSpec.outerTails, u)
        return SmoothGlowState(innerT, innerH, outerT, outerH, entryFade)
    }
    val e = ((t - exitStart) / (exitEnd - exitStart)).coerceIn(0f, 1f)
    val endInnerH = FaceHaloShaderSpec.innerHeads.last()
    val endInnerLen = endInnerH - FaceHaloShaderSpec.innerTails.last()
    val endOuterH = FaceHaloShaderSpec.outerHeads.last()
    val endOuterLen = endOuterH - FaceHaloShaderSpec.outerTails.last()

    val curInnerH = endInnerH + FaceHaloShaderSpec.sample(FaceHaloShaderSpec.travelInner, e)
    val curInnerLen = endInnerLen * FaceHaloShaderSpec.sample(FaceHaloShaderSpec.remainingInner, e).coerceIn(0f, 1f)
    val curOuterH = endOuterH + FaceHaloShaderSpec.sample(FaceHaloShaderSpec.travelOuter, e)
    val curOuterLen = endOuterLen * FaceHaloShaderSpec.sample(FaceHaloShaderSpec.remainingOuter, e).coerceIn(0f, 1f)
    val exitAlpha = 1f - smoothstepRange(0.45f, 1f, e)
    return SmoothGlowState(
        innerTail = curInnerH - curInnerLen,
        innerHead = curInnerH,
        outerTail = curOuterH - curOuterLen,
        outerHead = curOuterH,
        alpha = exitAlpha,
    )
}

/**
 * Focused white/silver Xiaomi 17 Pro Max upper phone back:
 * - Clean white/light backdrop
 * - Sleek thin silver-grey metallic outer frame (no dark spots or antenna blotches)
 * - Concentric top corners matching the real phone
 * - Pearl-white backplate
 * - Recessed rear screen island + clean double camera lenses (no grey overlay)
 * - Lower backplate details: black pill with small 3rd lens, circular silver flash, LEICA wordmark
 */
private fun DrawScope.drawFocusedPhoneBackShell(
    paints: PreviewTextPaints,
    isDarkSurface: Boolean,
    haloState: SmoothGlowState = SmoothGlowState(0f, 0f, 0f, 0f, 0f),
    runtimeShader: Any? = null,
    nativePaint: Paint? = null,
    content: DrawScope.(sLeft: Float, sTop: Float, sW: Float, sH: Float, uLeft: Float, uW: Float) -> Unit,
) {
    val canvasW = size.width
    val canvasH = size.height

    val stageBg = if (isDarkSurface) Color(0xFF1D2026) else Color(0xFFF7F8FA)
    drawRect(stageBg)

    // Sleek thinner silver-grey metallic frame
    val phoneW = canvasW * 0.85f
    val phoneLeft = (canvasW - phoneW) * 0.5f
    val phoneTop = canvasH * 0.055f
    val phoneH = canvasH * 1.18f
    val outerCorner = phoneW * 0.168f
    val frameThick = phoneW * 0.017f

    // Subtle left-side hardware button
    drawRoundRect(
        color = Color(0xFFC8CDD6),
        topLeft = Offset(phoneLeft - 1.8.dp.toPx(), phoneTop + canvasH * 0.56f),
        size = Size(2.4.dp.toPx(), canvasH * 0.15f),
        cornerRadius = CornerRadius(1.2.dp.toPx(), 1.2.dp.toPx()),
    )

    // Soft contact shadow around the silver phone frame
    drawRoundRect(
        color = Color.Black.copy(alpha = if (isDarkSurface) 0.34f else 0.07f),
        topLeft = Offset(phoneLeft - 1.5.dp.toPx(), phoneTop + 2.5.dp.toPx()),
        size = Size(phoneW + 3.dp.toPx(), phoneH),
        cornerRadius = CornerRadius(outerCorner + 1.5.dp.toPx(), outerCorner + 1.5.dp.toPx()),
    )

    // Clean, uniform light silver-grey metallic frame (no dark patches)
    val outerPath = Path().apply {
        addRoundRect(
            RoundRect(
                Rect(phoneLeft, phoneTop, phoneLeft + phoneW, phoneTop + phoneH),
                CornerRadius(outerCorner, outerCorner),
            ),
        )
    }
    clipPath(outerPath) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFE6E9EE),
                    Color(0xFFD9DEE5),
                    Color(0xFFCFD4DC),
                ),
                startY = phoneTop,
                endY = canvasH,
            ),
        )
    }
    drawRoundRect(
        color = Color(0xFFC0C6D0),
        topLeft = Offset(phoneLeft, phoneTop),
        size = Size(phoneW, phoneH),
        cornerRadius = CornerRadius(outerCorner, outerCorner),
        style = Stroke(width = 0.9.dp.toPx()),
    )

    // Pearl-white backplate inset inside metallic frame (exact concentric corner)
    val backLeft = phoneLeft + frameThick
    val backTop = phoneTop + frameThick
    val backW = phoneW - frameThick * 2f
    val backH = phoneH - frameThick
    val backCorner = (outerCorner - frameThick).coerceAtLeast(10f)
    val backPath = Path().apply {
        addRoundRect(
            RoundRect(
                Rect(backLeft, backTop, backLeft + backW, backTop + backH),
                CornerRadius(backCorner, backCorner),
            ),
        )
    }
    clipPath(backPath) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFFAFAFC),
                    Color(0xFFF2F4F7),
                    Color(0xFFEAECEF),
                ),
                startY = backTop,
                endY = canvasH,
            ),
        )
    }
    drawRoundRect(
        color = Color.White.copy(alpha = 0.85f),
        topLeft = Offset(backLeft, backTop),
        size = Size(backW, backH),
        cornerRadius = CornerRadius(backCorner, backCorner),
        style = Stroke(width = 0.8.dp.toPx()),
    )

    // Recessed rear screen island (concentric corners with phone body)
    val islandInsetX = phoneW * 0.062f
    val islandInsetTop = phoneW * 0.058f
    val islandLeft = phoneLeft + islandInsetX
    val islandTop = phoneTop + islandInsetTop
    val islandW = phoneW - islandInsetX * 2f
    val islandH = (islandW / 1.638f).coerceAtMost(canvasH * 0.64f)
    val islandCorner = (outerCorner - islandInsetX).coerceAtLeast(islandW * 0.132f)

    // Thin silver rim around the rear screen island
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFDFE3E9), Color(0xFFB5BCC7), Color(0xFFE7EAEF)),
            startY = islandTop - 1.8.dp.toPx(),
            endY = islandTop + islandH + 1.8.dp.toPx(),
        ),
        topLeft = Offset(islandLeft - 1.6.dp.toPx(), islandTop - 1.6.dp.toPx()),
        size = Size(islandW + 3.2.dp.toPx(), islandH + 3.2.dp.toPx()),
        cornerRadius = CornerRadius(islandCorner + 1.6.dp.toPx(), islandCorner + 1.6.dp.toPx()),
    )

    // Pure black OLED glass bezel
    drawRoundRect(
        color = Color(0xFF030406),
        topLeft = Offset(islandLeft, islandTop),
        size = Size(islandW, islandH),
        cornerRadius = CornerRadius(islandCorner, islandCorner),
    )

    // Active rear display area inside glass bezel
    val bezel = islandW * 0.018f
    val sLeft = islandLeft + bezel
    val sTop = islandTop + bezel
    val sW = islandW - bezel * 2f
    val sH = islandH - bezel * 2f
    val sCorner = (islandCorner - bezel).coerceAtLeast(8f)

    val screenPath = Path().apply {
        addRoundRect(
            RoundRect(
                Rect(sLeft, sTop, sLeft + sW, sTop + sH),
                CornerRadius(sCorner, sCorner),
            ),
        )
    }

    // Two large vertical camera lenses on the left side of the rear display
    val lensCx = sLeft + sW * 0.175f
    val lensR = sH * 0.210f
    val topLensCy = sTop + sH * 0.270f
    val botLensCy = sTop + sH * 0.730f
    val uLeft = sLeft + sW * 0.355f
    val uW = (sLeft + sW) - uLeft

    clipPath(screenPath) {
        content(sLeft, sTop, sW, sH, uLeft, uW)

        // Authentic device `smoothGlow` face-auth halo around the top camera lens
        if (haloState.alpha > 0.005f) {
            drawAuthenticFaceHalo(
                center = Offset(lensCx, topLensCy),
                radius = lensR * 1.02f,
                screenHeight = sH,
                state = haloState,
                runtimeShader = runtimeShader,
                nativePaint = nativePaint,
            )
        }

        // Clean camera lenses drawn directly on top of screen content (NO grey cutout)
        drawCameraLens(Offset(lensCx, topLensCy), lensR)
        drawCameraLens(Offset(lensCx, botLensCy), lensR)
    }

    // Lower white backplate details (3rd small lens pill + circular silver flash + LEICA)
    val belowTop = islandTop + islandH
    val belowH = (canvasH - belowTop).coerceAtLeast(16f)
    val detailCy = belowTop + belowH * 0.48f

    // 1. Black rounded capsule pill with small 3rd camera lens on its left end
    val pillLeft = islandLeft + islandW * 0.055f
    val pillW = islandW * 0.228f
    val pillH = min(belowH * 0.54f, islandH * 0.160f)
    val pillTop = detailCy - pillH * 0.5f
    drawRoundRect(
        color = Color(0xFF0B0D11),
        topLeft = Offset(pillLeft, pillTop),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(pillH * 0.5f, pillH * 0.5f),
    )
    val smallLensCx = pillLeft + pillH * 0.50f
    val smallLensR = pillH * 0.31f
    drawCircle(Color(0xFF1D222B), smallLensR, Offset(smallLensCx, detailCy))
    drawCircle(Color(0xFF0A0D12), smallLensR * 0.64f, Offset(smallLensCx, detailCy))
    drawCircle(
        Color(0xFF5D88B8).copy(alpha = 0.60f),
        smallLensR * 0.24f,
        Offset(smallLensCx - smallLensR * 0.18f, detailCy - smallLensR * 0.18f),
    )

    // 2. Circular textured silver flash module
    val flashR = pillH * 0.44f
    val flashCx = pillLeft + pillW + islandW * 0.068f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFF5F7FA), Color(0xFFD5DAE2), Color(0xFFC6CCD6)),
            center = Offset(flashCx - flashR * 0.2f, detailCy - flashR * 0.2f),
            radius = flashR * 1.4f,
        ),
        radius = flashR,
        center = Offset(flashCx, detailCy),
    )
    drawCircle(
        color = Color(0xFFB4BBC6),
        radius = flashR,
        center = Offset(flashCx, detailCy),
        style = Stroke(width = 0.8.dp.toPx()),
    )
    drawCircle(Color(0xFFECEAE2), flashR * 0.22f, Offset(flashCx - flashR * 0.24f, detailCy))
    drawCircle(Color(0xFFE3E7EE), flashR * 0.22f, Offset(flashCx + flashR * 0.24f, detailCy))

    // 3. LEICA wordmark + tiny VARIO-SUMMILUX caption
    val leicaX = flashCx + flashR + islandW * 0.044f
    drawCenteredText(
        paint = paints.leicaBrand,
        text = "LEICA",
        cx = leicaX,
        cy = detailCy - pillH * 0.16f,
        textSizePx = (pillH * 0.44f).coerceAtLeast(8f),
        color = Color(0xFF8B919C),
        align = Paint.Align.LEFT,
    )
    drawCenteredText(
        paint = paints.regular,
        text = "VARIO-SUMMILUX",
        cx = leicaX,
        cy = detailCy + pillH * 0.24f,
        textSizePx = (pillH * 0.18f).coerceAtLeast(4.5f),
        color = Color(0xFFA4AAB5),
        align = Paint.Align.LEFT,
    )
}

private fun DrawScope.drawCameraLens(center: Offset, radius: Float) {
    drawCircle(Color(0xFF07090C), radius, center)
    drawCircle(
        color = Color.White.copy(alpha = 0.22f),
        radius = radius,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
    drawCircle(Color(0xFF151920), radius * 0.72f, center)
    drawCircle(
        color = Color.White.copy(alpha = 0.10f),
        radius = radius * 0.72f,
        center = center,
        style = Stroke(width = 0.8.dp.toPx()),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF1E2B3D), Color(0xFF0A0E14), Color(0xFF040507)),
            center = Offset(center.x - radius * 0.12f, center.y - radius * 0.14f),
            radius = radius * 0.46f,
        ),
        radius = radius * 0.44f,
        center = center,
    )
    drawCircle(
        color = Color(0xFF88B4E6).copy(alpha = 0.42f),
        radius = radius * 0.11f,
        center = Offset(center.x - radius * 0.14f, center.y - radius * 0.15f),
    )
}

/** Real OEM SystemUI face unlock drawables from RearFaceFeedback.kt:146-153. */
private class SystemFaceIcons(
    val face: Drawable?,
    val ring: Drawable?,
)

@Composable
private fun rememberSystemFaceIcons(): SystemFaceIcons {
    val context = LocalContext.current
    return remember(context) {
        val sys = runCatching { context.createPackageContext("com.android.systemui", 0) }.getOrNull()
        fun art(name: String): Drawable? = runCatching {
            val s = requireNotNull(sys)
            val id = s.resources.getIdentifier(name, "drawable", s.packageName)
            if (id != 0) s.getDrawable(id)?.mutate() else null
        }.getOrNull()
        SystemFaceIcons(
            face = art("face_unlock_face"),
            ring = art("face_unlock_ring"),
        )
    }
}

private fun DrawScope.drawAuthenticFaceHalo(
    center: Offset,
    radius: Float,
    screenHeight: Float,
    state: SmoothGlowState,
    runtimeShader: Any?,
    nativePaint: Paint?,
) {
    val scale = screenHeight / 596f
    val feather = (19f * scale).coerceAtLeast(2.2f)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        runtimeShader is RuntimeShader &&
        nativePaint != null
    ) {
        runtimeShader.setFloatUniform("center", center.x, center.y)
        runtimeShader.setFloatUniform("radius", radius)
        runtimeShader.setFloatUniform("feather", feather)
        runtimeShader.setFloatUniform(
            "arcs",
            state.innerTail,
            state.innerHead,
            state.outerTail,
            state.outerHead,
        )
        nativePaint.shader = runtimeShader
        nativePaint.alpha = (state.alpha.coerceIn(0f, 1f) * 255f).toInt()
        val pad = radius + 104f * scale
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(
                center.x - pad,
                center.y - pad,
                center.x + pad,
                center.y + pad,
                nativePaint,
            )
        }
        return
    }

    val outerExtent = (state.outerHead - state.outerTail).coerceIn(0f, 360f)
    val innerExtent = (state.innerHead - state.innerTail).coerceIn(0f, 360f)
    if (outerExtent > 1f) {
        val r = radius + feather * 0.45f
        drawArc(
            color = Color(0xFFC7FFE8).copy(alpha = 0.46f * state.alpha),
            startAngle = state.outerTail,
            sweepAngle = outerExtent,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = feather * 0.9f, cap = StrokeCap.Round),
        )
    }
    if (innerExtent > 1f) {
        val r = radius + feather * 0.14f
        drawArc(
            color = Color.White.copy(alpha = 0.88f * state.alpha),
            startAngle = state.innerTail,
            sweepAngle = innerExtent,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = feather * 0.45f, cap = StrokeCap.Round),
        )
    }
}

/**
 * Dreamy HyperOS-style luminous pastel aurora rear-screen clock wallpaper.
 * Looks crisp and airy inside the white/silver Xiaomi 17 Pro Max chassis,
 * and gives high contrast when frosted cards, popups, or charging liquid enter.
 */
private fun DrawScope.drawRearClockDesktop(
    paints: PreviewTextPaints,
    sLeft: Float,
    sTop: Float,
    sW: Float,
    sH: Float,
    uLeft: Float,
    uW: Float,
) {
    // Luminous sunrise-aurora gradient base
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFF9FB8FF), // soft sky periwinkle
                Color(0xFFD5B8F5), // dreamy lavender
                Color(0xFFFFCBA8), // warm sunlit peach
            ),
            start = Offset(sLeft, sTop),
            end = Offset(sLeft + sW, sTop + sH),
        ),
        topLeft = Offset(sLeft, sTop),
        size = Size(sW, sH),
    )
    // Soft glowing organic aurora light blobs
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFE7B8).copy(alpha = 0.65f), Color.Transparent),
            center = Offset(sLeft + sW * 0.82f, sTop + sH * 0.18f),
            radius = sW * 0.45f,
        ),
        radius = sW * 0.45f,
        center = Offset(sLeft + sW * 0.82f, sTop + sH * 0.18f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF7FA6FF).copy(alpha = 0.55f), Color.Transparent),
            center = Offset(sLeft + sW * 0.30f, sTop + sH * 0.85f),
            radius = sW * 0.48f,
        ),
        radius = sW * 0.48f,
        center = Offset(sLeft + sW * 0.30f, sTop + sH * 0.85f),
    )
    // Subtle soft bottom depth scrim for crisp white clock legibility
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color(0xFF232946).copy(alpha = 0.22f)),
            startY = sTop,
            endY = sTop + sH,
        ),
        topLeft = Offset(sLeft, sTop),
        size = Size(sW, sH),
    )

    val cx = uLeft + uW * 0.50f
    // Date caption above clock (HyperOS lock/rear screen editorial typography)
    drawCenteredText(
        paint = paints.medium,
        text = "11月21日 周六",
        cx = cx,
        cy = sTop + sH * 0.27f,
        textSizePx = sH * 0.085f,
        color = Color.White.copy(alpha = 0.90f),
    )
    // Bold clean clock digits with soft shadow
    drawCenteredText(
        paint = paints.monoBold,
        text = "09:41",
        cx = cx,
        cy = sTop + sH * 0.52f + 1.2.dp.toPx(),
        textSizePx = sH * 0.32f,
        color = Color(0xFF1F2740).copy(alpha = 0.18f),
    )
    drawCenteredText(
        paint = paints.monoBold,
        text = "09:41",
        cx = cx,
        cy = sTop + sH * 0.52f,
        textSizePx = sH * 0.32f,
        color = Color.White,
    )
}

/** Tactile white touch dot with press-bounce & expanding ripple ring for tap interactions. */
private fun DrawScope.drawTapDotIndicator(
    center: Offset,
    progress: Float,
) {
    val p = progress.coerceIn(0f, 1f)
    val alpha = smoothstepRange(0.0f, 0.18f, p) * (1f - smoothstepRange(0.76f, 1.0f, p))
    if (alpha <= 0.01f) return
    val press = sin(smoothstepRange(0.18f, 0.72f, p) * PI.toFloat())
    val ripple = smoothstepRange(0.24f, 0.92f, p)
    val baseR = 5.0.dp.toPx()
    val dotR = baseR * (1.0f - 0.24f * press)

    // Expanding translucent white ripple ring on press
    if (ripple > 0.01f && ripple < 0.99f) {
        drawCircle(
            color = Color.White.copy(alpha = 0.55f * (1f - ripple) * alpha),
            radius = baseR * (1.1f + 2.0f * ripple),
            center = center,
            style = Stroke(width = 1.6.dp.toPx()),
        )
    }
    // Soft outer halo so the white dot pops on both light and dark UI
    drawCircle(Color.Black.copy(alpha = 0.22f * alpha), dotR + 2.2.dp.toPx(), center)
    drawCircle(Color.White.copy(alpha = 0.30f * alpha), dotR + 2.6.dp.toPx(), center)
    drawCircle(Color.White.copy(alpha = 0.95f * alpha), dotR, center)
}

/**
 * Renders the real OEM SystemUI circular face-unlock icon (`face_unlock_ring` + `face_unlock_face`)
 * matching RearFaceFeedback.kt:435-464, with a crisp circular ring + smiley fallback.
 */
private fun DrawScope.drawOemFaceUnlockIcon(
    center: Offset,
    radius: Float,
    faceAlpha: Float,
    checkProgress: Float,
    faceIcons: SystemFaceIcons?,
    tint: Color = Color.White,
) {
    val strokeW = (radius * 0.14f).coerceAtLeast(1.2.dp.toPx())
    if (faceAlpha > 0.01f) {
        val ringDrawable = faceIcons?.ring
        val faceDrawable = faceIcons?.face
        if (ringDrawable != null && faceDrawable != null) {
            val opacity = (faceAlpha.coerceIn(0f, 1f) * 255f).toInt()
            val l = (center.x - radius).roundToInt()
            val t = (center.y - radius).roundToInt()
            val r = (center.x + radius).roundToInt()
            val b = (center.y + radius).roundToInt()
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                ringDrawable.setBounds(l, t, r, b)
                ringDrawable.alpha = opacity
                ringDrawable.draw(nc)
                faceDrawable.setBounds(l, t, r, b)
                faceDrawable.alpha = opacity
                faceDrawable.draw(nc)
            }
        } else {
            // Crisp OEM-style circular ring + minimalist smiley face (NO square brackets)
            val c = tint.copy(alpha = tint.alpha * faceAlpha)
            drawCircle(c, radius, center, style = Stroke(width = strokeW))
            drawCircle(c, strokeW * 0.72f, Offset(center.x - radius * 0.30f, center.y - radius * 0.16f))
            drawCircle(c, strokeW * 0.72f, Offset(center.x + radius * 0.30f, center.y - radius * 0.16f))
            drawArc(
                color = c,
                startAngle = 25f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(center.x - radius * 0.38f, center.y - radius * 0.16f),
                size = Size(radius * 0.76f, radius * 0.56f),
                style = Stroke(width = strokeW, cap = StrokeCap.Round),
            )
        }
    }
    if (checkProgress > 0.01f) {
        val checkColor = Color(0xFF34C759).copy(alpha = checkProgress.coerceIn(0f, 1f))
        drawCircle(
            color = checkColor,
            radius = radius,
            center = center,
            style = Stroke(width = strokeW),
        )
        val p1 = Offset(center.x - radius * 0.44f, center.y + radius * 0.02f)
        val p2 = Offset(center.x - radius * 0.10f, center.y + radius * 0.36f)
        val p3 = Offset(center.x + radius * 0.46f, center.y - radius * 0.30f)
        val seg1 = (checkProgress / 0.45f).coerceIn(0f, 1f)
        val seg2 = ((checkProgress - 0.45f) / 0.55f).coerceIn(0f, 1f)
        val path = Path().apply {
            moveTo(p1.x, p1.y)
            lineTo(lerpFloat(p1.x, p2.x, seg1), lerpFloat(p1.y, p2.y, seg1))
            if (seg2 > 0f) {
                lineTo(lerpFloat(p2.x, p3.x, seg2), lerpFloat(p2.y, p3.y, seg2))
            }
        }
        drawPath(path, checkColor, style = Stroke(width = strokeW * 1.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun DrawScope.drawSwipeGestureIndicator(
    start: Offset,
    end: Offset,
    progress: Float,
    alpha: Float,
) {
    if (alpha <= 0.01f) return
    val p = easeInOutCubic(progress)
    val cur = Offset(lerpFloat(start.x, end.x, p), lerpFloat(start.y, end.y, p))
    val tailP = (p - 0.35f).coerceAtLeast(0f)
    val tail = Offset(lerpFloat(start.x, end.x, tailP), lerpFloat(start.y, end.y, tailP))
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.45f * alpha)),
            start = tail,
            end = cur,
        ),
        start = tail,
        end = cur,
        strokeWidth = 5.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawCircle(Color.White.copy(alpha = 0.25f * alpha), 8.dp.toPx(), cur)
    drawCircle(Color.White.copy(alpha = 0.85f * alpha), 4.2.dp.toPx(), cur)
}

@Composable
private fun rememberFaceHaloShader(): Pair<Any?, Paint?> = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = runCatching { RuntimeShader(FaceHaloShaderSpec.AGSL_SOURCE) }.getOrNull()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        shader to paint
    } else {
        null to null
    }
}

private data class PreviewSlide(
    val title: String,
    val cycleMs: Long,
    val draw: DrawScope.(
        phase: Float,
        paints: PreviewTextPaints,
        isDark: Boolean,
        shader: Any?,
        nativePaint: Paint?,
        faceIcons: SystemFaceIcons?,
    ) -> Unit,
)

@Composable
private fun RearPreviewCarouselCard(
    slides: List<PreviewSlide>,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    val (shader, nativePaint) = rememberFaceHaloShader()
    val faceIcons = rememberSystemFaceIcons()
    val paints = remember { PreviewTextPaints() }
    val isDark = MochiTheme.colorScheme.surface.luminance() < 0.5f

    // Single synchronized clock keyed on `settledPage` (NOT `currentPage`!),
    // so `animateScrollToPage` is NEVER cancelled halfway through when currentPage flips at 50%!
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    var activePhase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(pagerState.settledPage, isDragged, slides.size) {
        if (isDragged) return@LaunchedEffect
        val currentIdx = pagerState.settledPage.coerceIn(0, slides.lastIndex)
        val durationNanos = slides[currentIdx].cycleMs * 1_000_000L
        activePhase = 0f
        val startNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val elapsed = now - startNanos
            if (slides.size > 1) {
                val p = (elapsed.toFloat() / durationNanos.toFloat()).coerceIn(0f, 1f)
                activePhase = p
                if (p >= 1f) {
                    val nextPage = (currentIdx + 1) % slides.size
                    pagerState.animateScrollToPage(
                        page = nextPage,
                        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
                    )
                    break
                }
            } else {
                activePhase = ((elapsed % durationNanos).toFloat() / durationNanos.toFloat())
            }
        }
    }

    ConfigCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                userScrollEnabled = slides.size > 1,
            ) { page ->
                val slide = slides[page]
                val pagePhase = if (page == pagerState.settledPage) activePhase else 0f
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.42f)
                            .clip(RoundedCornerShape(18.dp)),
                    ) {
                        slide.draw(this, pagePhase, paints, isDark, shader, nativePaint, faceIcons)
                    }

                    Text(
                        text = slide.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MochiTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (slides.size > 1) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(slides.size) { index ->
                        val active = pagerState.currentPage == index
                        val dotWidth by animateDpAsState(
                            targetValue = if (active) 18.dp else 5.dp,
                            animationSpec = spring(dampingRatio = 0.78f, stiffness = 460f),
                            label = "previewDotWidth",
                        )
                        Box(
                            modifier = Modifier
                                .height(5.dp)
                                .width(dotWidth)
                                .clip(CircleShape)
                                .background(
                                    if (active) MochiTheme.colorScheme.primary
                                    else MochiTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                )
                                .clickable {
                                    scope.launch {
                                        pagerState.animateScrollToPage(
                                            page = index,
                                            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
                                        )
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// Public Entry Points
// =========================================================================

@Composable
internal fun RearAppListPreviewCard(modifier: Modifier = Modifier) {
    val slides = remember {
        listOf(
            PreviewSlide(
                title = "在背屏打开应用",
                cycleMs = 6600L,
                draw = { phase, paints, isDark, shader, paint, faceIcons ->
                    drawRearAppListDemo(phase, paints, isDark, shader, paint, faceIcons)
                },
            ),
        )
    }
    RearPreviewCarouselCard(slides = slides, modifier = modifier)
}

@Composable
internal fun RearEnhancementPreviewsCard(modifier: Modifier = Modifier) {
    val slides = remember {
        listOf(
            PreviewSlide(
                title = "背屏沉浸式导航卡片",
                cycleMs = 5600L,
                draw = { phase, paints, isDark, _, _, _ ->
                    drawNavigationCardDemo(phase, paints, isDark)
                },
            ),
            PreviewSlide(
                title = "录音卡片",
                cycleMs = 5400L,
                draw = { phase, paints, isDark, _, _, _ ->
                    drawRecorderCardDemo(phase, paints, isDark)
                },
            ),
        )
    }
    RearPreviewCarouselCard(slides = slides, modifier = modifier)
}

@Composable
internal fun RearNotificationPreviewsCard(modifier: Modifier = Modifier) {
    val slides = remember {
        listOf(
            PreviewSlide(
                title = "背屏卡片通知",
                cycleMs = 6800L,
                draw = { phase, paints, isDark, shader, paint, faceIcons ->
                    drawNotificationCardDemo(phase, paints, isDark, shader, paint, faceIcons)
                },
            ),
            PreviewSlide(
                title = "背屏弹窗通知",
                cycleMs = 6400L,
                draw = { phase, paints, isDark, shader, paint, faceIcons ->
                    drawNotificationPopupDemo(phase, paints, isDark, shader, paint, faceIcons)
                },
            ),
        )
    }
    RearPreviewCarouselCard(slides = slides, modifier = modifier)
}

@Composable
internal fun RearChargingPreviewsCard(modifier: Modifier = Modifier) {
    val titleMrss = stringResource(R.string.rear_charging_style_mrss)
    val titleGlow = stringResource(R.string.rear_charging_style_glow)
    val titleLeicaRed = stringResource(R.string.rear_charging_style_leica)
    val titleLeicaGreen = stringResource(R.string.rear_charging_style_leica_green)
    val glowShaders = rememberGlowChargingShaders()
    val leicaAssets = rememberLeicaChargingAssets()

    val slides = remember(titleMrss, titleGlow, titleLeicaRed, titleLeicaGreen, glowShaders, leicaAssets) {
        listOf(
            PreviewSlide(
                title = titleMrss,
                cycleMs = 5200L,
                draw = { phase, paints, isDark, _, _, _ ->
                    drawMrssChargingDemo(phase, paints, isDark)
                },
            ),
            PreviewSlide(
                title = titleGlow,
                cycleMs = 5600L,
                draw = { phase, paints, isDark, _, _, _ ->
                    drawGlowLiquidChargingDemo(phase, paints, isDark, glowShaders)
                },
            ),
            PreviewSlide(
                title = titleLeicaRed,
                cycleMs = 8500L,
                draw = { phase, paints, isDark, _, _, _ ->
                    drawLeicaChargingDemo(phase, paints, isDark, leicaAssets, green = false)
                },
            ),
            PreviewSlide(
                title = titleLeicaGreen,
                cycleMs = 8500L,
                draw = { phase, paints, isDark, _, _, _ ->
                    drawLeicaChargingDemo(phase, paints, isDark, leicaAssets, green = true)
                },
            ),
        )
    }
    RearPreviewCarouselCard(slides = slides, modifier = modifier)
}

// =========================================================================
// 1. Rear App List Demo (在背屏打开应用)
// Matches stock LauncherController horizontal rounded-square icon tiles
// + RearFaceFeedback (centered face icon only + top-lens smoothGlow, no text)
// + smooth app launch zoom from tapped icon into rear display region
// =========================================================================

private fun DrawScope.drawRearAppListDemo(
    phase: Float,
    paints: PreviewTextPaints,
    isDarkSurface: Boolean,
    runtimeShader: Any?,
    nativePaint: Paint?,
    faceIcons: SystemFaceIcons?,
) {
    val halo = computeFaceHaloState(
        t = phase,
        scanStart = 0.44f,
        exitStart = 0.62f,
        exitEnd = 0.72f,
    )
    drawFocusedPhoneBackShell(paints, isDarkSurface, halo, runtimeShader, nativePaint) { sLeft, sTop, sW, sH, uLeft, uW ->
        // Base rear clock desktop
        drawRearClockDesktop(paints, sLeft, sTop, sW, sH, uLeft, uW)

        val listIn = easeOutCubic(smoothstepRange(0.14f, 0.30f, phase))
        val appIn = easeOutCubic(smoothstepRange(0.68f, 0.78f, phase))
        val resetFade = 1f - smoothstepRange(0.93f, 1.00f, phase)

        // Upward swipe gesture (0.11..0.28)
        val swipeAlpha = smoothstepRange(0.09f, 0.13f, phase) * (1f - smoothstepRange(0.24f, 0.29f, phase))
        if (swipeAlpha > 0.01f) {
            val sp = ((phase - 0.11f) / 0.16f).coerceIn(0f, 1f)
            drawSwipeGestureIndicator(
                start = Offset(uLeft + uW * 0.50f, sTop + sH * 0.88f),
                end = Offset(uLeft + uW * 0.50f, sTop + sH * 0.32f),
                progress = sp,
                alpha = swipeAlpha,
            )
        }

        // Authentic OEM stock rear app picker (RearAppCatalogHook.kt):
        // Vertical list of frosted translucent horizontal rows with CIRCULAR 36dp app icon on left + name on right
        val listAlpha = listIn * (1f - appIn * 0.85f) * resetFade
        val rowH = sH * 0.24f
        val rowGap = sH * 0.048f
        val rowLeft = uLeft + uW * 0.05f
        val rowW = uW * 0.90f
        val startRowY = sTop + sH * 0.10f

        if (listAlpha > 0.01f) {
            val offsetY = (1f - listIn) * sH * 0.78f
            drawRect(
                color = Color(0xFF0A0D13).copy(alpha = 0.86f * listAlpha),
                topLeft = Offset(sLeft, sTop + offsetY),
                size = Size(sW, sH),
            )
            val apps = listOf(
                Triple("微信", Color(0xFF07C160), "W"),
                Triple("哔哩哔哩", Color(0xFFFB7299), "B"),
                Triple("小红书", Color(0xFFFF2442), "红"),
            )
            val tapDip = sin(smoothstepRange(0.34f, 0.42f, phase) * PI.toFloat())
            apps.forEachIndexed { idx, (name, badgeColor, glyph) ->
                val ry = startRowY + offsetY + idx * (rowH + rowGap)
                val pressed = idx == 0 && tapDip > 0.01f
                val inset = if (pressed) uW * 0.02f * tapDip else 0f
                val rl = rowLeft + inset
                val rw = rowW - inset * 2f
                drawRoundRect(
                    color = Color.White.copy(alpha = (if (pressed) 0.22f else 0.13f) * listAlpha),
                    topLeft = Offset(rl, ry),
                    size = Size(rw, rowH),
                    cornerRadius = CornerRadius(rowH * 0.44f, rowH * 0.44f),
                )
                // Circular app icon (roundPickerIcon in RearAppCatalogHook.kt:465)
                val iconR = rowH * 0.35f
                val iconCx = rl + rowH * 0.50f
                val iconCy = ry + rowH * 0.50f
                drawCircle(badgeColor.copy(alpha = listAlpha), iconR, Offset(iconCx, iconCy))
                drawCenteredText(
                    paint = paints.bold,
                    text = glyph,
                    cx = iconCx,
                    cy = iconCy,
                    textSizePx = iconR * 1.12f,
                    color = Color.White.copy(alpha = listAlpha),
                )
                drawCenteredText(
                    paint = paints.medium,
                    text = name,
                    cx = iconCx + iconR + uW * 0.06f,
                    cy = iconCy,
                    textSizePx = rowH * 0.42f,
                    color = Color.White.copy(alpha = 0.94f * listAlpha),
                    align = Paint.Align.LEFT,
                )
            }
            // Tactile white tap dot pressing the WeChat row (0.31..0.44)
            if (phase in 0.31f..0.44f) {
                drawTapDotIndicator(
                    center = Offset(rowLeft + rowW * 0.56f, startRowY + offsetY + rowH * 0.50f),
                    progress = (phase - 0.31f) / 0.13f,
                )
            }
        }

        // Authentic RearFaceFeedback: translucent dark blur + OEM circular face-unlock icon + top-lens smoothGlow
        val authIn = smoothstepRange(0.42f, 0.49f, phase)
        val authOut = 1f - smoothstepRange(0.65f, 0.72f, phase)
        val authAlpha = (authIn * authOut).coerceIn(0f, 1f)
        if (authAlpha > 0.01f) {
            drawRect(
                color = Color(0xFF06090F).copy(alpha = 0.68f * authAlpha),
                topLeft = Offset(sLeft, sTop),
                size = Size(sW, sH),
            )
            val checkP = smoothstepRange(0.60f, 0.67f, phase)
            drawOemFaceUnlockIcon(
                center = Offset(uLeft + uW * 0.50f, sTop + sH * 0.50f),
                radius = sH * 0.135f,
                faceAlpha = (1f - checkP) * authAlpha,
                checkProgress = checkP * authAlpha,
                faceIcons = faceIcons,
                tint = Color.White,
            )
        }

        // App launch animation: zooms smoothly out from tapped row, flush with top/right/bottom edges!
        val openAlpha = appIn * resetFade
        if (openAlpha > 0.01f) {
            val targetL = uLeft
            val targetT = sTop
            val targetW = uW
            val targetH = sH
            val targetCorner = sH * 0.145f

            val l = lerpFloat(rowLeft, targetL, appIn)
            val t = lerpFloat(startRowY, targetT, appIn)
            val w = lerpFloat(rowW, targetW, appIn)
            val h = lerpFloat(rowH, targetH, appIn)
            val r = lerpFloat(rowH * 0.44f, targetCorner, appIn)

            val appClip = Path().apply {
                addRoundRect(RoundRect(Rect(l, t, l + w, t + h), CornerRadius(r, r)))
            }
            clipPath(appClip) {
                // Light background matching default rear app light UI
                drawRect(Color(0xFFF3F4F6).copy(alpha = openAlpha), Offset(l, t), Size(w, h))
                val uiAlpha = smoothstepRange(0.32f, 1f, appIn) * resetFade
                if (uiAlpha > 0.01f) {
                    // Light top header bar
                    drawRect(Color(0xFFEAECEF).copy(alpha = uiAlpha), Offset(l, t), Size(w, h * 0.24f))
                    drawCenteredText(
                        paint = paints.bold,
                        text = "微信",
                        cx = l + w * 0.10f,
                        cy = t + h * 0.125f,
                        textSizePx = h * 0.105f,
                        color = Color(0xFF14161A).copy(alpha = uiAlpha),
                        align = Paint.Align.LEFT,
                    )
                    // Incoming white message bubble
                    drawRoundRect(
                        color = Color.White.copy(alpha = uiAlpha),
                        topLeft = Offset(l + w * 0.07f, t + h * 0.33f),
                        size = Size(w * 0.72f, h * 0.22f),
                        cornerRadius = CornerRadius(h * 0.07f, h * 0.07f),
                    )
                    drawCenteredText(
                        paint = paints.regular,
                        text = "晚上一起吃饭吗？",
                        cx = l + w * 0.14f,
                        cy = t + h * 0.44f,
                        textSizePx = h * 0.085f,
                        color = Color(0xFF1A1C20).copy(alpha = uiAlpha),
                        align = Paint.Align.LEFT,
                    )
                    // Outgoing WeChat light-green reply bubble
                    drawRoundRect(
                        color = Color(0xFF95EC69).copy(alpha = uiAlpha),
                        topLeft = Offset(l + w * 0.34f, t + h * 0.63f),
                        size = Size(w * 0.58f, h * 0.22f),
                        cornerRadius = CornerRadius(h * 0.07f, h * 0.07f),
                    )
                    drawCenteredText(
                        paint = paints.medium,
                        text = "好呀，马上到",
                        cx = l + w * 0.63f,
                        cy = t + h * 0.74f,
                        textSizePx = h * 0.085f,
                        color = Color(0xFF111811).copy(alpha = uiAlpha),
                    )
                }
            }
        }
    }
}

// =========================================================================
// 2. Immersive Navigation Card (背屏沉浸式导航卡片)
// Matches RearNavigationCard.kt:
// - Clean dark teal-slate map (#10242C) with smooth roads & parks (NO weird diagonal grid!)
// - Top frosted headerGlass: app icon + "预计 19:28 到达"
// - Bottom frosted footerGlass: LEFT = title/detail text, RIGHT = turn-right arrow, BOTTOM = progress bar
// =========================================================================

private fun DrawScope.drawNavigationCardDemo(
    phase: Float,
    paints: PreviewTextPaints,
    isDarkSurface: Boolean,
) {
    drawFocusedPhoneBackShell(paints, isDarkSurface) { sLeft, sTop, sW, sH, uLeft, uW ->
        // Exact RearNavigationCard dark map base (#10242C) spanning 0..w behind lenses
        drawRect(Color(0xFF10242C), Offset(sLeft, sTop), Size(sW, sH))

        // Unified map world offset: parks, cross streets AND the green route bend all move TOGETHER!
        // Header ends at 0.240f, footer starts at 0.570f -> gap midpoint is 0.405f!
        val panY = easeInOutCubic(phase) * sH * 0.22f
        val routeX = uLeft + uW * 0.48f
        val turnY = sTop + sH * 0.13f + panY

        // Urban park / water blocks locked to the map coordinate system
        drawRoundRect(
            color = Color(0xFF15343E),
            topLeft = Offset(sLeft + sW * 0.06f, turnY - sH * 0.30f),
            size = Size(sW * 0.36f, sH * 0.26f),
            cornerRadius = CornerRadius(14f, 14f),
        )
        drawRoundRect(
            color = Color(0xFF132F38),
            topLeft = Offset(routeX + uW * 0.10f, turnY + sH * 0.06f),
            size = Size(sW * 0.32f, sH * 0.28f),
            cornerRadius = CornerRadius(14f, 14f),
        )

        // Road network locked to the EXACT same `turnY` world coordinates
        val roadColor = Color(0xFF1F3C47)
        drawLine(roadColor, Offset(sLeft, turnY), Offset(sLeft + sW, turnY), strokeWidth = 6.dp.toPx())
        drawLine(roadColor, Offset(sLeft, turnY + sH * 0.38f), Offset(sLeft + sW, turnY + sH * 0.38f), strokeWidth = 4.dp.toPx())
        drawLine(roadColor, Offset(routeX, sTop - sH * 0.2f), Offset(routeX, sTop + sH * 1.1f), strokeWidth = 6.dp.toPx())
        drawLine(roadColor, Offset(sLeft + sW * 0.28f, sTop), Offset(sLeft + sW * 0.28f, sTop + sH), strokeWidth = 3.5.dp.toPx())
        drawLine(roadColor, Offset(sLeft + sW * 0.86f, sTop), Offset(sLeft + sW * 0.86f, sTop + sH), strokeWidth = 3.5.dp.toPx())

        // Glowing emerald navigation route locked directly onto `routeX` & `turnY`!
        val routePath = Path().apply {
            moveTo(routeX, sTop + sH * 1.05f)
            lineTo(routeX, turnY + sH * 0.07f)
            quadraticTo(routeX, turnY, routeX + sW * 0.055f, turnY)
            lineTo(sLeft + sW * 1.02f, turnY)
        }
        drawPath(routePath, Color(0xFF0A4D33), style = Stroke(width = 8.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(routePath, Color(0xFF28D17C), style = Stroke(width = 4.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Anchored GPS car puck centered right in the gap between headerGlass (0.240) and footerGlass (0.570)!
        val puckY = sTop + sH * 0.405f
        drawCircle(Color(0xFF4285F4).copy(alpha = 0.28f), sH * 0.095f, Offset(routeX, puckY))
        drawCircle(Color.White, sH * 0.048f, Offset(routeX, puckY))
        drawCircle(Color(0xFF4285F4), sH * 0.032f, Offset(routeX, puckY))

        // Top frosted `headerGlass` matching RearNavigationCard.kt (app icon + arrival text)
        val headerL = uLeft + uW * 0.04f
        val headerT = sTop + sH * 0.065f
        val headerW = uW * 0.90f
        val headerH = sH * 0.175f
        drawRoundRect(
            color = Color(0xFF0E1D24).copy(alpha = 0.80f),
            topLeft = Offset(headerL, headerT),
            size = Size(headerW, headerH),
            cornerRadius = CornerRadius(headerH * 0.45f, headerH * 0.45f),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.15f),
            topLeft = Offset(headerL, headerT),
            size = Size(headerW, headerH),
            cornerRadius = CornerRadius(headerH * 0.45f, headerH * 0.45f),
            style = Stroke(width = 0.8.dp.toPx()),
        )
        // Small map app icon circle on left of header
        val hIconR = headerH * 0.30f
        drawCircle(Color(0xFF2E8BFF), hIconR, Offset(headerL + headerH * 0.50f, headerT + headerH * 0.50f))
        drawCenteredText(
            paint = paints.medium,
            text = "预计 19:28 到达",
            cx = headerL + headerH * 0.95f,
            cy = headerT + headerH * 0.50f,
            textSizePx = headerH * 0.48f,
            color = Color.White.copy(alpha = 0.94f),
            align = Paint.Align.LEFT,
        )

        // Bottom frosted `footerGlass` matching RearNavigationCard.kt:
        // LEFT = title ("150米后右转") + detail ("进入深南大道"), RIGHT = turn arrow, BOTTOM = progress bar
        val footL = uLeft + uW * 0.04f
        val footH = sH * 0.37f
        val footT = sTop + sH - footH - sH * 0.06f
        val footW = uW * 0.90f
        drawRoundRect(
            color = Color(0xFF0E1D24).copy(alpha = 0.88f),
            topLeft = Offset(footL, footT),
            size = Size(footW, footH),
            cornerRadius = CornerRadius(sH * 0.09f, sH * 0.09f),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.15f),
            topLeft = Offset(footL, footT),
            size = Size(footW, footH),
            cornerRadius = CornerRadius(sH * 0.09f, sH * 0.09f),
            style = Stroke(width = 0.8.dp.toPx()),
        )

        val distMeters = (150 - (phase * 90f).toInt() / 10 * 10).coerceAtLeast(60)
        val textX = footL + footW * 0.08f
        drawCenteredText(
            paint = paints.bold,
            text = "${distMeters}米后右转",
            cx = textX,
            cy = footT + footH * 0.28f,
            textSizePx = footH * 0.27f,
            color = Color.White,
            align = Paint.Align.LEFT,
        )
        drawCenteredText(
            paint = paints.regular,
            text = "进入深南大道",
            cx = textX,
            cy = footT + footH * 0.55f,
            textSizePx = footH * 0.19f,
            color = Color.White.copy(alpha = 0.72f),
            align = Paint.Align.LEFT,
        )

        // Turn-right arrow on the RIGHT side of footerGlass (matching RearNavigationCard.kt:654)
        val arrowCx = footL + footW - footH * 0.42f
        val arrowCy = footT + footH * 0.40f
        val ar = footH * 0.20f
        val turnPath = Path().apply {
            moveTo(arrowCx - ar * 0.35f, arrowCy + ar * 0.65f)
            lineTo(arrowCx - ar * 0.35f, arrowCy - ar * 0.15f)
            quadraticTo(arrowCx - ar * 0.35f, arrowCy - ar * 0.52f, arrowCx + ar * 0.10f, arrowCy - ar * 0.52f)
            lineTo(arrowCx + ar * 0.56f, arrowCy - ar * 0.52f)
            moveTo(arrowCx + ar * 0.22f, arrowCy - ar * 0.86f)
            lineTo(arrowCx + ar * 0.60f, arrowCy - ar * 0.52f)
            lineTo(arrowCx + ar * 0.22f, arrowCy - ar * 0.18f)
        }
        drawPath(turnPath, Color.White, style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Bottom traffic progress bar (matching RearNavigationCard.kt:662)
        val barL = footL + footW * 0.08f
        val barW = footW * 0.84f
        val barY = footT + footH * 0.82f
        val barH = sH * 0.028f
        val segments = listOf(
            0.00f to 0.45f to Color(0xFF28D17C),
            0.45f to 0.70f to Color(0xFFFFB020),
            0.70f to 0.84f to Color(0xFFFF4D4F),
            0.84f to 1.00f to Color(0xFF28D17C),
        )
        segments.forEach { (range, segColor) ->
            drawLine(segColor, Offset(barL + barW * range.first, barY), Offset(barL + barW * range.second, barY), strokeWidth = barH, cap = StrokeCap.Round)
        }
        val progX = barL + barW * lerpFloat(0.18f, 0.76f, phase)
        drawLine(Color(0xFF4285F4), Offset(barL, barY), Offset(progX, barY), strokeWidth = barH, cap = StrokeCap.Round)
    }
}

// =========================================================================
// 3. Recorder Card (录音卡片)
// Matches NativeRearTimerScene.kt (adapted OEM timer MAML card):
// - Warm coral-burgundy full-screen MAML background + subtle radial ripple glow
// - Header: compact animated waveform icon + "录音中" (#E6FFE1D4)
// - Center: large rolling count-up digits (00:12 -> 00:15) with upward digit roll
// - Bottom: Left Pause pill button, Right coral-red Stop button
// =========================================================================

private fun DrawScope.drawRecorderCardDemo(
    phase: Float,
    paints: PreviewTextPaints,
    isDarkSurface: Boolean,
) {
    drawFocusedPhoneBackShell(paints, isDarkSurface) { sLeft, sTop, sW, sH, uLeft, uW ->
        val breathe = 0.88f + 0.12f * sin(phase * 2f * PI.toFloat() * 1.8f)
        // Authentic warm terracotta-orange MAML background (from NativeRearTimerScene R/B channel swap)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF5A2416), Color(0xFF3E180F), Color(0xFF260E09)),
                startY = sTop,
                endY = sTop + sH,
            ),
            topLeft = Offset(sLeft, sTop),
            size = Size(sW, sH),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFFF7A4D).copy(alpha = 0.26f * breathe), Color.Transparent),
                center = Offset(uLeft + uW * 0.50f, sTop + sH * 0.46f),
                radius = sW * 0.56f,
            ),
            radius = sW * 0.56f,
            center = Offset(uLeft + uW * 0.50f, sTop + sH * 0.46f),
        )

        val cx = uLeft + uW * 0.50f

        // Header: mini live waveform icon (replaces OEM sandglass) + "录音中"
        val headY = sTop + sH * 0.17f
        val miniBarBaseX = cx - uW * 0.24f
        for (i in 0 until 4) {
            val v = 0.35f + 0.65f * abs(sin(phase * 2f * PI.toFloat() * 3f + i * 0.9f))
            val bh = sH * (0.04f + 0.055f * v)
            val bx = miniBarBaseX + i * 3.8.dp.toPx()
            drawLine(
                color = Color(0xFFFFE1D4),
                start = Offset(bx, headY - bh * 0.5f),
                end = Offset(bx, headY + bh * 0.5f),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawCenteredText(
            paint = paints.medium,
            text = "录音中",
            cx = cx + uW * 0.08f,
            cy = headY,
            textSizePx = sH * 0.095f,
            color = Color(0xFFE6FFE1D4),
        )

        // Large OEM-style rolling count-up timer (00:12 -> 00:15)
        val totalSeconds = phase * 4f
        val secIdx = floor(totalSeconds).toInt().coerceIn(0, 3)
        val frac = totalSeconds - secIdx
        val curDigit = 2 + secIdx
        val nextDigit = 2 + ((secIdx + 1) % 4)
        val roll = smoothstepRange(0.72f, 0.98f, frac)

        val timerY = sTop + sH * 0.46f
        val timerSize = sH * 0.29f
        val prefix = "00:1"
        paints.monoBold.textSize = timerSize
        val prefixW = paints.monoBold.measureText(prefix)
        val digitW = paints.monoBold.measureText("8")
        val totalW = prefixW + digitW
        val startX = cx - totalW * 0.5f

        drawCenteredText(
            paint = paints.monoBold,
            text = prefix,
            cx = startX,
            cy = timerY,
            textSizePx = timerSize,
            color = Color(0xFFFFF4EE),
            align = Paint.Align.LEFT,
        )

        val bandH = timerSize * 1.12f
        clipRect(
            left = startX + prefixW - 2f,
            top = timerY - bandH * 0.52f,
            right = startX + totalW + 4f,
            bottom = timerY + bandH * 0.52f,
        ) {
            val digitX = startX + prefixW + digitW * 0.5f
            drawCenteredText(
                paint = paints.monoBold,
                text = curDigit.toString(),
                cx = digitX,
                cy = timerY - roll * bandH * 0.85f,
                textSizePx = timerSize,
                color = Color(0xFFFFF4EE).copy(alpha = 1f - roll),
            )
            if (roll > 0.01f) {
                drawCenteredText(
                    paint = paints.monoBold,
                    text = nextDigit.toString(),
                    cx = digitX,
                    cy = timerY + (1f - roll) * bandH * 0.85f,
                    textSizePx = timerSize,
                    color = Color(0xFFFFF4EE).copy(alpha = roll),
                )
            }
        }

        // Bottom controls matching NativeRearTimerScene.kt:
        // Left = Pause button, Right = Stop button (record_end_v3)
        val btnY = sTop + sH * 0.80f
        val btnR = sH * 0.115f
        val pauseCx = cx - uW * 0.21f
        val stopCx = cx + uW * 0.21f

        drawCircle(Color(0xFFA67A66).copy(alpha = 0.34f), btnR, Offset(pauseCx, btnY))
        val barGap = btnR * 0.25f
        val barHalfH = btnR * 0.36f
        drawLine(Color.White, Offset(pauseCx - barGap, btnY - barHalfH), Offset(pauseCx - barGap, btnY + barHalfH), strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.White, Offset(pauseCx + barGap, btnY - barHalfH), Offset(pauseCx + barGap, btnY + barHalfH), strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round)

        drawCircle(Color(0xFFE8453C), btnR, Offset(stopCx, btnY))
        val sq = btnR * 0.66f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(stopCx - sq * 0.5f, btnY - sq * 0.5f),
            size = Size(sq, sq),
            cornerRadius = CornerRadius(sq * 0.22f, sq * 0.22f),
        )
    }
}

// =========================================================================
// Shared Authentic RearPopupReveal Content Renderer
// Matches RearPopupReveal.kt 1:1 (left-aligned icon -> body -> sender,
// app icon shrinks to bottom-right corner badge while sender avatar scales in!)
// =========================================================================

private fun DrawScope.drawRearPopupRevealBody(
    paints: PreviewTextPaints,
    uLeft: Float,
    uW: Float,
    panelTop: Float,
    sH: Float,
    reveal: Float,
    alpha: Float,
    faceIcons: SystemFaceIcons?,
    showQuickOpen: Boolean,
    showCardActions: Boolean,
) {
    if (alpha <= 0.01f) return
    val marginX = uW * 0.08f
    val contentLeft = uLeft + marginX
    val contentW = uW - marginX * 2f

    // Container shifts gently upward on reveal (topMargin: 22.5% -> 12.5% in RearPopupReveal.kt:316)
    val baseTop = panelTop + sH * lerpFloat(0.20f, 0.11f, reveal)
    val iconSize = sH * 0.25f
    val badgeSize = iconSize * 0.44f

    // 1. Avatar fades & scales in behind the morphing app icon
    if (reveal > 0.05f) {
        val avP = easeOutCubic(smoothstepRange(0.10f, 0.72f, reveal))
        val avSize = iconSize * lerpFloat(0.72f, 1.0f, avP)
        val avLeft = contentLeft + (iconSize - avSize) * 0.5f
        val avTop = baseTop + (iconSize - avSize) * 0.5f
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF4F8FF7), Color(0xFF3563D8)),
                start = Offset(avLeft, avTop),
                end = Offset(avLeft + avSize, avTop + avSize),
            ),
            topLeft = Offset(avLeft, avTop),
            size = Size(avSize, avSize),
            cornerRadius = CornerRadius(avSize * 0.24f, avSize * 0.24f),
            alpha = avP * alpha,
        )
        drawCenteredText(
            paint = paints.bold,
            text = "张",
            cx = avLeft + avSize * 0.5f,
            cy = avTop + avSize * 0.5f,
            textSizePx = avSize * 0.48f,
            color = Color.White.copy(alpha = avP * alpha),
        )
    }

    // 2. Green WeChat app icon smoothly shrinks & moves into the bottom-right corner badge
    val appMorph = easeInOutCubic(reveal)
    val curAppSize = lerpFloat(iconSize, badgeSize, appMorph)
    val curAppLeft = lerpFloat(contentLeft, contentLeft + iconSize - badgeSize * 0.85f, appMorph)
    val curAppTop = lerpFloat(baseTop, baseTop + iconSize - badgeSize * 0.85f, appMorph)
    drawRoundRect(
        color = Color(0xFF07C160).copy(alpha = alpha),
        topLeft = Offset(curAppLeft, curAppTop),
        size = Size(curAppSize, curAppSize),
        cornerRadius = CornerRadius(curAppSize * 0.26f, curAppSize * 0.26f),
    )
    drawCenteredText(
        paint = paints.bold,
        text = "W",
        cx = curAppLeft + curAppSize * 0.5f,
        cy = curAppTop + curAppSize * 0.5f,
        textSizePx = curAppSize * 0.54f,
        color = Color.White.copy(alpha = alpha),
    )

    // 3. Body & Sender labels (LEFT-ALIGNED, matching RearPopupReveal.kt:297-312)
    val bodyY = baseTop + iconSize + sH * 0.135f
    val senderY = bodyY + sH * 0.135f
    val oldAlpha = (1f - smoothstepRange(0.0f, 0.45f, reveal)) * alpha
    val newAlpha = smoothstepRange(0.28f, 0.85f, reveal) * alpha

    if (oldAlpha > 0.01f) {
        val redactedText = "你有1条新消息"
        val bodySize = sH * 0.115f
        paints.medium.textSize = bodySize
        val textW = paints.medium.measureText(redactedText)
        drawCenteredText(
            paint = paints.medium,
            text = redactedText,
            cx = contentLeft,
            cy = bodyY,
            textSizePx = bodySize,
            color = Color.White.copy(alpha = oldAlpha),
            align = Paint.Align.LEFT,
        )
        // Real OEM circular face-unlock icon positioned directly beside the redacted text (RearFaceFeedback.kt:424)
        val inlineR = sH * 0.052f
        val inlineX = (contentLeft + textW + sH * 0.045f + inlineR).coerceAtMost(contentLeft + contentW - inlineR)
        drawOemFaceUnlockIcon(
            center = Offset(inlineX, bodyY),
            radius = inlineR,
            faceAlpha = oldAlpha,
            checkProgress = 0f,
            faceIcons = faceIcons,
            tint = Color.White,
        )
        drawCenteredText(
            paint = paints.regular,
            text = "微信",
            cx = contentLeft,
            cy = senderY,
            textSizePx = sH * 0.085f,
            color = Color.White.copy(alpha = 0.62f * oldAlpha),
            align = Paint.Align.LEFT,
        )
    }

    if (newAlpha > 0.01f) {
        val lift = (1f - easeOutCubic(smoothstepRange(0.28f, 0.88f, reveal))) * sH * 0.04f
        drawCenteredText(
            paint = paints.medium,
            text = "晚上一起吃饭吗？",
            cx = contentLeft,
            cy = bodyY + lift,
            textSizePx = sH * 0.112f,
            color = Color.White.copy(alpha = newAlpha),
            align = Paint.Align.LEFT,
        )
        drawCenteredText(
            paint = paints.regular,
            text = "张明",
            cx = contentLeft,
            cy = senderY + lift,
            textSizePx = sH * 0.085f,
            color = Color.White.copy(alpha = 0.62f * newAlpha),
            align = Paint.Align.LEFT,
        )
    }

    // 4. Bottom footer actions (fade in after reveal, matching RearPopupReveal.kt:264)
    val ctrlAlpha = smoothstepRange(0.48f, 0.92f, reveal) * alpha
    if (ctrlAlpha > 0.01f) {
        val btnH = sH * 0.16f
        val btnTop = panelTop + sH - btnH - sH * 0.075f
        if (showCardActions) {
            // Two action pills from rear_notification_card.xml ("回复" | "已读")
            val gap = contentW * 0.05f
            val halfW = (contentW - gap) * 0.5f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.16f * ctrlAlpha),
                topLeft = Offset(contentLeft, btnTop),
                size = Size(halfW, btnH),
                cornerRadius = CornerRadius(btnH * 0.5f, btnH * 0.5f),
            )
            drawCenteredText(
                paint = paints.medium,
                text = "回复",
                cx = contentLeft + halfW * 0.5f,
                cy = btnTop + btnH * 0.5f,
                textSizePx = btnH * 0.48f,
                color = Color.White.copy(alpha = 0.92f * ctrlAlpha),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.16f * ctrlAlpha),
                topLeft = Offset(contentLeft + halfW + gap, btnTop),
                size = Size(halfW, btnH),
                cornerRadius = CornerRadius(btnH * 0.5f, btnH * 0.5f),
            )
            drawCenteredText(
                paint = paints.medium,
                text = "已读",
                cx = contentLeft + halfW + gap + halfW * 0.5f,
                cy = btnTop + btnH * 0.5f,
                textSizePx = btnH * 0.48f,
                color = Color.White.copy(alpha = 0.92f * ctrlAlpha),
            )
        } else if (showQuickOpen) {
            // Full-width "在背屏快捷打开" pill from RearPopupReveal.attachQuickOpen
            drawRoundRect(
                color = Color.White.copy(alpha = 0.16f * ctrlAlpha),
                topLeft = Offset(contentLeft, btnTop),
                size = Size(contentW, btnH),
                cornerRadius = CornerRadius(btnH * 0.5f, btnH * 0.5f),
            )
            drawCenteredText(
                paint = paints.medium,
                text = "在背屏快捷打开",
                cx = contentLeft + contentW * 0.5f,
                cy = btnTop + btnH * 0.5f,
                textSizePx = btnH * 0.48f,
                color = Color.White.copy(alpha = 0.92f * ctrlAlpha),
            )
        }
    }
}

// =========================================================================
// 4. Rear Card Notification (背屏卡片通知)
// Downward swipe from top pulls down frosted glass card -> top-lens smoothGlow -> RearPopupReveal
// =========================================================================

private fun DrawScope.drawNotificationCardDemo(
    phase: Float,
    paints: PreviewTextPaints,
    isDarkSurface: Boolean,
    runtimeShader: Any?,
    nativePaint: Paint?,
    faceIcons: SystemFaceIcons?,
) {
    val halo = computeFaceHaloState(
        t = phase,
        scanStart = 0.35f,
        exitStart = 0.54f,
        exitEnd = 0.64f,
    )
    drawFocusedPhoneBackShell(paints, isDarkSurface, halo, runtimeShader, nativePaint) { sLeft, sTop, sW, sH, uLeft, uW ->
        drawRearClockDesktop(paints, sLeft, sTop, sW, sH, uLeft, uW)

        val pullDown = easeOutCubic(smoothstepRange(0.14f, 0.31f, phase))
        val pullBack = smoothstepRange(0.91f, 0.99f, phase)
        val panelFrac = (pullDown * (1f - pullBack)).coerceIn(0f, 1f)

        if (panelFrac > 0.01f) {
            val panelTop = sTop - sH * (1f - panelFrac)
            // Frosted glass card shade (REAR_NOTIFICATION_GLASS)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF1A2230).copy(alpha = 0.92f), Color(0xFF101621).copy(alpha = 0.94f)),
                    startY = panelTop,
                    endY = panelTop + sH,
                ),
                topLeft = Offset(sLeft, panelTop),
                size = Size(sW, sH),
            )
            val reveal = smoothstepRange(0.55f, 0.68f, phase)
            drawRearPopupRevealBody(
                paints = paints,
                uLeft = uLeft,
                uW = uW,
                panelTop = panelTop,
                sH = sH,
                reveal = reveal,
                alpha = 1f,
                faceIcons = faceIcons,
                showQuickOpen = false,
                showCardActions = true,
            )
            // Tactile white tap dot on "已读" action pill after reveal (0.78..0.90)
            if (phase in 0.78f..0.90f) {
                val btnH = sH * 0.16f
                val btnCy = panelTop + sH - btnH * 0.5f - sH * 0.075f
                drawTapDotIndicator(
                    center = Offset(uLeft + uW * 0.72f, btnCy),
                    progress = (phase - 0.78f) / 0.12f,
                )
            }
        }

        // Visible downward swipe gesture from top edge
        val swipeAlpha = smoothstepRange(0.10f, 0.14f, phase) * (1f - smoothstepRange(0.25f, 0.31f, phase))
        if (swipeAlpha > 0.01f) {
            val sp = ((phase - 0.12f) / 0.17f).coerceIn(0f, 1f)
            drawSwipeGestureIndicator(
                start = Offset(uLeft + uW * 0.50f, sTop + sH * 0.08f),
                end = Offset(uLeft + uW * 0.50f, sTop + sH * 0.62f),
                progress = sp,
                alpha = swipeAlpha,
            )
        }
    }
}

// =========================================================================
// 5. Rear Popup Notification (背屏弹窗通知)
// Stock full-screen popup fades in -> top-lens smoothGlow -> RearPopupReveal + quickOpen
// =========================================================================

private fun DrawScope.drawNotificationPopupDemo(
    phase: Float,
    paints: PreviewTextPaints,
    isDarkSurface: Boolean,
    runtimeShader: Any?,
    nativePaint: Paint?,
    faceIcons: SystemFaceIcons?,
) {
    val halo = computeFaceHaloState(
        t = phase,
        scanStart = 0.28f,
        exitStart = 0.48f,
        exitEnd = 0.58f,
    )
    drawFocusedPhoneBackShell(paints, isDarkSurface, halo, runtimeShader, nativePaint) { sLeft, sTop, sW, sH, uLeft, uW ->
        drawRearClockDesktop(paints, sLeft, sTop, sW, sH, uLeft, uW)

        val popIn = easeOutCubic(smoothstepRange(0.10f, 0.22f, phase))
        val popOut = 1f - smoothstepRange(0.90f, 0.99f, phase)
        val popAlpha = (popIn * popOut).coerceIn(0f, 1f)

        if (popAlpha > 0.01f) {
            drawRect(
                color = Color(0xFF0A0D13).copy(alpha = 0.96f * popAlpha),
                topLeft = Offset(sLeft, sTop),
                size = Size(sW, sH),
            )
            val reveal = smoothstepRange(0.50f, 0.64f, phase)
            drawRearPopupRevealBody(
                paints = paints,
                uLeft = uLeft,
                uW = uW,
                panelTop = sTop,
                sH = sH,
                reveal = reveal,
                alpha = popAlpha,
                faceIcons = faceIcons,
                showQuickOpen = true,
                showCardActions = false,
            )
            // Tactile white tap dot on "在背屏快捷打开" button after reveal (0.76..0.89)
            if (phase in 0.76f..0.89f) {
                val btnH = sH * 0.16f
                val btnCy = sTop + sH - btnH * 0.5f - sH * 0.075f
                drawTapDotIndicator(
                    center = Offset(uLeft + uW * 0.50f, btnCy),
                    progress = (phase - 0.76f) / 0.13f,
                )
            }
        }
    }
}

// =========================================================================
// 6. Charging Animations (Authentic Views, Real Shaders & Real Leica Assets!)
// =========================================================================

/** Style 0: MRSS 液体 (matches RearMrssChargingView.kt 1:1) */
private fun DrawScope.drawMrssChargingDemo(
    phase: Float,
    paints: PreviewTextPaints,
    isDarkSurface: Boolean,
) {
    drawFocusedPhoneBackShell(paints, isDarkSurface) { sLeft, sTop, sW, sH, uLeft, uW ->
        drawRect(Color.Black, Offset(sLeft, sTop), Size(sW, sH))

        val fill = lerpFloat(0.28f, 0.86f, easeOutCubic(smoothstepRange(0.02f, 0.72f, phase)))
        val waterline = sTop + sH * (1f - fill)
        val wavePhase = phase * PI.toFloat() * 6f
        val amp = sH * 0.025f * min(1f, min(fill, 1f - fill) * 20f)

        val liquidPath = Path().apply {
            moveTo(sLeft, sTop + sH)
            for (i in 0..80) {
                val frac = i / 80f
                val x = sLeft + sW * frac
                val wave = sin(frac * PI.toFloat() * 2f + wavePhase) * amp +
                    sin(frac * PI.toFloat() * 3f - wavePhase * 0.6f) * amp * 0.35f
                lineTo(x, waterline + wave)
            }
            lineTo(sLeft + sW, sTop + sH)
            close()
        }
        drawPath(liquidPath, Color(0xFF34C759))

        clipPath(liquidPath) {
            repeat(6) { index ->
                val p = (phase * 1.4f + index / 6f) % 1f
                val bx = sLeft + sW * ((index * 0.173f + 0.12f) % 1f) + sin(wavePhase + index) * amp
                val by = sTop + sH - p * sH * fill
                drawCircle(Color(0x50FFFFFF), min(sW, sH) * (0.008f + index * 0.0018f), Offset(bx, by))
            }
        }

        val cx = uLeft + uW * 0.50f
        val cy = sTop + sH * 0.50f
        val pct = (fill * 100f).roundToInt().coerceIn(28, 86)
        drawCenteredText(
            paint = paints.bold,
            text = "$pct%",
            cx = cx,
            cy = cy,
            textSizePx = min(uW * 0.28f, sH * 0.28f),
            color = Color.White,
        )
        drawCenteredText(
            paint = paints.medium,
            text = "正在充电",
            cx = cx,
            cy = cy + sH * 0.22f,
            textSizePx = min(uW * 0.085f, sH * 0.088f),
            color = Color.White.copy(alpha = 0.88f),
        )
    }
}

/**
 * Style 1: 柔光光晕与波浪 (matches RearChargingLiquidView.kt 1:1 using the EXACT 3 AGSL shaders!)
 * Timeline (4200ms mapped to phase 0..1):
 * - Rear clock desktop visible initially -> green bolt + haloShader + rippleShader ->
 *   background darkens -> real liquidShader rises full-screen (ZERO black edge!) ->
 *   bolt slides left & turns yellow #FFE500 -> "86.42%" + "超级快充" fade in!
 */
private fun DrawScope.drawGlowLiquidChargingDemo(
    phase: Float,
    paints: PreviewTextPaints,
    isDarkSurface: Boolean,
    shaders: GlowChargingShaders,
) {
    drawFocusedPhoneBackShell(paints, isDarkSurface) { sLeft, sTop, sW, sH, uLeft, uW ->
        // 1. Rear clock stays visible behind initial bolt & ripple, then darkens as liquid enters!
        drawRearClockDesktop(paints, sLeft, sTop, sW, sH, uLeft, uW)

        val age = phase * 4200f
        fun seg(start: Float, dur: Float) = ((age - start) / dur).coerceIn(0f, 1f)
        fun ease(start: Float, dur: Float) = easeOutCubic(seg(start, dur))

        val dark = ease(1050f, 650f)
        drawRect(
            color = Color.Black.copy(alpha = 0.14f + 0.80f * dark),
            topLeft = Offset(sLeft, sTop),
            size = Size(sW, sH),
        )

        val rise = ease(1450f, 750f)
        val intro = easeOutCubic(seg(140f, 380f))
        val growth = easeInOutCubic(seg(140f, 620f))
        val slide = easeInOutCubic(seg(1800f, 580f))

        val localULeft = uLeft - sLeft
        val textCenterX = localULeft + uW * 0.58f
        val startBoltX = localULeft + uW * 0.50f
        val endBoltX = localULeft + uW * 0.18f
        val boltLocalX = lerpFloat(startBoltX, endBoltX, slide)
        val boltLocalY = sH * 0.50f
        val boltColor = lerpColor(Color(0xFF00C995), Color(0xFFFFE500), slide)

        // Translate nativeCanvas to (sLeft, sTop) so exact AGSL shaders fill 0..sW, 0..sH cleanly with ZERO edge gaps!
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.save()
            nc.translate(sLeft, sTop)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Exact liquidShader from RearChargingLiquidView.kt
                val liquidShader = shaders.liquid as? RuntimeShader
                if (rise > 0.005f && liquidShader != null) {
                    val surface = sH * (1.12f - (0.86f + 0.12f) * rise)
                    liquidShader.setFloatUniform("size", sW, sH)
                    liquidShader.setFloatUniform("surface", surface)
                    liquidShader.setFloatUniform("phase", phase * 4f * PI.toFloat())
                    liquidShader.setFloatUniform("reveal", ease(1450f, 280f))
                    liquidShader.setFloatUniform("textCenter", textCenterX, sH * 0.54f)
                    liquidShader.setFloatUniform("textRadius", uW * 0.42f, sH * 0.31f)
                    paints.shaderPaint.shader = liquidShader
                    paints.shaderPaint.alpha = 255
                    nc.drawRect(0f, 0f, sW, sH, paints.shaderPaint)
                    paints.shaderPaint.shader = null
                }

                // Exact haloShader from RearChargingLiquidView.kt
                val haloShader = shaders.halo as? RuntimeShader
                if (haloShader != null) {
                    val glowRadius = sH * (0.14f + 0.60f * easeInOutCubic(seg(180f, 1100f))) * (1f - 0.16f * slide)
                    val haloAttack = easeOutCubic(seg(175f, 500f))
                    val haloTail = 1f - 0.82f * easeInOutCubic(seg(610f, 1100f))
                    haloShader.setFloatUniform("center", boltLocalX, boltLocalY)
                    haloShader.setFloatUniform("radius", glowRadius.coerceAtLeast(1f))
                    haloShader.setFloatUniform("tint", boltColor.red, boltColor.green, boltColor.blue)
                    paints.shaderPaint.shader = haloShader
                    paints.shaderPaint.alpha = (255f * haloAttack * haloTail).toInt().coerceIn(0, 255)
                    nc.drawRect(0f, 0f, sW, sH, paints.shaderPaint)
                    paints.shaderPaint.shader = null
                    paints.shaderPaint.alpha = 255
                }

                // Exact rippleShader from RearChargingLiquidView.kt
                val ripple = seg(445f, 620f)
                val rippleShader = shaders.ripple as? RuntimeShader
                if (ripple > 0f && ripple < 1f && rippleShader != null) {
                    rippleShader.setFloatUniform("center", startBoltX, boltLocalY)
                    rippleShader.setFloatUniform("radius", sH * GlowChargingShaderSpec.sample(GlowChargingShaderSpec.ringRadius, ripple))
                    rippleShader.setFloatUniform("feather", sH * (0.045f + 0.08f * ripple))
                    paints.shaderPaint.shader = rippleShader
                    paints.shaderPaint.alpha = (GlowChargingShaderSpec.sample(GlowChargingShaderSpec.ringOpacity, ripple).coerceIn(0f, 1f) * 255f).toInt()
                    nc.drawRect(0f, 0f, sW, sH, paints.shaderPaint)
                    paints.shaderPaint.shader = null
                    paints.shaderPaint.alpha = 255
                }
            }
            nc.restore()
        }

        // Lightning bolt icon (green -> slides left -> golden yellow)
        if (intro > 0.01f) {
            val bx = sLeft + boltLocalX
            val by = sTop + boltLocalY
            val boltH = sH * (0.35f - 0.08f * slide) * (0.12f + 0.88f * growth)
            val boltPath = Path().apply {
                moveTo(bx + boltH * 0.14f, by - boltH * 0.50f)
                lineTo(bx - boltH * 0.25f, by + boltH * 0.06f)
                lineTo(bx + boltH * 0.02f, by + boltH * 0.06f)
                lineTo(bx - boltH * 0.14f, by + boltH * 0.50f)
                lineTo(bx + boltH * 0.25f, by - boltH * 0.06f)
                lineTo(bx - boltH * 0.02f, by - boltH * 0.06f)
                close()
            }
            drawPath(boltPath, boltColor.copy(alpha = intro))
        }

        // Decimal charge value ("86.42%") + status label
        val numAlpha = ease(2050f, 450f)
        if (numAlpha > 0.01f) {
            val dec = 40 + ((phase * 18f).toInt() % 9)
            drawCenteredText(
                paint = paints.monoBold,
                text = "86.${dec}%",
                cx = uLeft + uW * 0.29f,
                cy = sTop + sH * 0.48f,
                textSizePx = sH * 0.24f,
                color = Color.White.copy(alpha = numAlpha),
                align = Paint.Align.LEFT,
            )
            val subAlpha = ease(2320f, 420f)
            drawCenteredText(
                paint = paints.medium,
                text = "超级快充",
                cx = uLeft + uW * 0.29f,
                cy = sTop + sH * 0.72f,
                textSizePx = sH * 0.088f,
                color = Color.White.copy(alpha = 0.80f * subAlpha),
                align = Paint.Align.LEFT,
            )
        }
    }
}

/**
 * Styles 2 & 3: 徕卡红色光晕 / 徕卡绿色光晕
 * Renders the ACTUAL REAL assets from `assets/charging/leica/` (`glow.webp`, `num_0..9.png`,
 * `num_b.png`, `icon_0.png`, `icon_1.png`) using the EXACT layout formulas from RearLeicaChargingView.kt!
 */
private fun DrawScope.drawLeicaChargingDemo(
    phase: Float,
    paints: PreviewTextPaints,
    isDarkSurface: Boolean,
    assets: LeicaChargingAssets?,
    green: Boolean,
) {
    drawFocusedPhoneBackShell(paints, isDarkSurface) { sLeft, sTop, sW, sH, uLeft, uW ->
        drawRect(Color.Black, Offset(sLeft, sTop), Size(sW, sH))

        // Slow, calm charge progression (82% -> 86% over 8.5s) matching real charging pace
        val pct = lerpFloat(82f, 86f, easeInOutCubic(smoothstepRange(0.12f, 0.88f, phase))).roundToInt().coerceIn(82, 86)

        val glow = if (green) assets?.glowGreen else assets?.glowRed
        val glyphs = assets?.glyphs.orEmpty()
        val icons = assets?.icons.orEmpty()

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.save()
            nc.translate(sLeft, sTop)

            // 1. Real animated `glow.webp` filling the full display behind cameras
            if (glow != null) {
                glow.setBounds(0, 0, sW.roundToInt(), sH.roundToInt())
                glow.alpha = 255
                glow.draw(nc)
            }

            // 2. Real Leica digit sprites (`num_*.png` + `num_b.png`) + 23-tick gauge + `icon_0/1.png`
            if (glyphs.size >= 11) {
                val safeLeft = uLeft - sLeft
                val centerX = safeLeft + uW * 0.50f
                val centerY = sH * 0.43f
                val digits = pct.toString().map { glyphs[it - '0'] } + glyphs[10]
                val naturalWidth = digits.sumOf { it.width }
                val scale = minOf(sH * 0.34f / 379f, uW * 0.82f / naturalWidth)
                var x = centerX - naturalWidth * scale * 0.5f
                paints.bitmapPaint.alpha = 255
                for (digit in digits) {
                    val dw = digit.width * scale
                    val dh = digit.height * scale
                    paints.dstRect.set(x, centerY - dh * 0.5f, x + dw, centerY + dh * 0.5f)
                    nc.drawBitmap(digit, null, paints.dstRect, paints.bitmapPaint)
                    x += dw
                }

                // Exact 23-tick Leica gauge from RearLeicaChargingView.kt:153-179
                val gaugeY = sH * 0.75f
                val gScale = minOf(uW * 0.68f / 450f, sH / 596f)
                val firstX = centerX - 198f * gScale
                val filled = (pct / 100f * 23).roundToInt()
                paints.bitmapPaint.color = android.graphics.Color.WHITE
                for (index in 0 until 23) {
                    val active = filled >= index
                    val tickW = (if (active) 5f else 4f) * gScale
                    val tickH = (if ((index + 1) % 6 == 0) 34f else 16f) * gScale
                    val tickX = firstX + index * 18f * gScale
                    paints.bitmapPaint.alpha = (255 * if (active) 1f else 0.24f).toInt()
                    nc.drawRoundRect(
                        tickX - tickW * 0.5f,
                        gaugeY - tickH * 0.5f,
                        tickX + tickW * 0.5f,
                        gaugeY + tickH * 0.5f,
                        3f * gScale,
                        3f * gScale,
                        paints.bitmapPaint,
                    )
                }
                for (index in icons.indices) {
                    val icon = icons[index]
                    val iconX = firstX + (if (index == 0) -20f else 416f) * gScale
                    val iw = icon.width * gScale
                    val ih = icon.height * gScale
                    paints.dstRect.set(iconX - iw * 0.5f, gaugeY - ih * 0.5f, iconX + iw * 0.5f, gaugeY + ih * 0.5f)
                    paints.bitmapPaint.alpha = (255 * if (index == 0 || filled >= 22) 1f else 0.24f).toInt()
                    nc.drawBitmap(icon, null, paints.dstRect, paints.bitmapPaint)
                }
                paints.bitmapPaint.alpha = 255
            }

            nc.restore()
        }
    }
}
