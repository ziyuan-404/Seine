package hk.uwu.reareye.ui.components.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val SPONSOR_NAV_SOURCE_KEY = "seine_support_card"

internal fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

internal fun smoothstepF(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Curves extracted from HyperOS system back-pin (贴背屏) animation
 * (`com.xiaomi.subscreencenter.pin` / `FolmeEase`).
 */
internal val BackPinQuartInOutEasing = Easing { t ->
    val x = t.coerceIn(0f, 1f)
    if (x < 0.5f) {
        8f * x * x * x * x
    } else {
        val inv = -2f * x + 2f
        1f - (inv * inv * inv * inv) / 2f
    }
}

internal val BackPinCubicOutEasing = Easing { t ->
    val inv = 1f - t.coerceIn(0f, 1f)
    1f - inv * inv * inv
}

internal val BackPinCubicInOutEasing = Easing { t ->
    val x = t.coerceIn(0f, 1f)
    if (x < 0.5f) {
        4f * x * x * x
    } else {
        val inv = -2f * x + 2f
        1f - (inv * inv * inv) / 2f
    }
}

internal val BackPinSineOutEasing = Easing { t ->
    sin(t.coerceIn(0f, 1f) * PI / 2.0).toFloat()
}

internal val BackPinFrontRevealSpring = MochiMotions.responseSpring(0.55f, 0.92f)
internal val BackPinCardUnfoldSpring = MochiMotions.responseSpring(0.52f, 0.88f)

@Stable
class SponsorHeroState {
    var sourceIconRect by mutableStateOf<Rect?>(null)
    var targetCardRect by mutableStateOf<Rect?>(null)

    var isHeroFlying by mutableStateOf(false)
        internal set

    var openToken by mutableIntStateOf(0)
        private set

    val flightProgress = Animatable(1f)
    val scaleProgress = Animatable(1f)
    val settlePulse = Animatable(0f)
    val cardUnfoldProgress = Animatable(1f)
    val chromeRevealProgress = Animatable(1f)

    fun triggerOpen() {
        isHeroFlying = true
        openToken++
    }

    fun resetImmediate() {
        isHeroFlying = false
    }
}

val LocalSponsorHeroState = staticCompositionLocalOf<SponsorHeroState?> { null }

@Composable
fun rememberSponsorHeroState(): SponsorHeroState = remember { SponsorHeroState() }

/**
 * Full-screen overlay that flies the rear-screen QR illustration from [hk.uwu.reareye.ui.components.SeineSupportCard]
 * with a single 3D spin (referenced from HyperOS back-pin animation curves), lifts it into the upper-middle
 * of the sponsor QR card, then performs a long, seamless overlapping dissolve as the full QR card unfolds
 * outward from the exact same bounds and the rest of the page fades in.
 */
@Composable
fun SponsorHeroOverlay(state: SponsorHeroState) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    LaunchedEffect(state.openToken) {
        if (state.openToken == 0) return@LaunchedEffect
        state.flightProgress.snapTo(0f)
        state.scaleProgress.snapTo(0f)
        state.settlePulse.snapTo(0f)
        state.cardUnfoldProgress.snapTo(0f)
        state.chromeRevealProgress.snapTo(0f)
        state.isHeroFlying = true

        coroutineScope {
            // Phase 1: Lift & single 360° 3D spin to the upper-center of the QR card
            // (mirrors BackPin cardRotationY CubicOut + cardPosition CubicOut).
            launch {
                state.flightProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 560, easing = BackPinCubicOutEasing),
                )
            }
            // Scale expansion using BackPin QuartInOut curve.
            launch {
                state.scaleProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 540, easing = BackPinQuartInOutEasing),
                )
            }
            // Subtle BackPin micro-settle pulse (0.68 -> 0.60 -> 0.68 cadence) right as it lands.
            launch {
                delay(380L)
                state.settlePulse.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 120, easing = BackPinSineOutEasing),
                )
                state.settlePulse.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 200, easing = BackPinCubicOutEasing),
                )
            }
            // Phase 2: Long, continuous card unfold & cross-dissolve (~600ms) so icon and QR card feel like one element.
            launch {
                delay(360L)
                state.cardUnfoldProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 600, easing = BackPinQuartInOutEasing),
                )
            }
            // Phase 3: Remaining page chrome (title, segmented control, buttons) fades in smoothly.
            launch {
                delay(520L)
                state.chromeRevealProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 440, easing = FastOutSlowInEasing),
                )
            }
        }
        state.isHeroFlying = false
    }

    if (!state.isHeroFlying) return

    val startRect = state.sourceIconRect ?: return
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val targetRect = state.targetCardRect ?: run {
        val maxCardWidthPx = with(density) { 304.dp.toPx() }
        val horizontalPaddingPx = with(density) { 32.dp.toPx() }
        val cardWidth = (screenWidthPx - horizontalPaddingPx).coerceAtMost(maxCardWidthPx)
        val cardHeight = cardWidth / 0.69f
        val left = (screenWidthPx - cardWidth) * 0.5f
        val top = screenHeightPx * 0.24f
        Rect(left, top, left + cardWidth, top + cardHeight)
    }

    // Destination during Phase 1: upper-center of the sponsor QR card (right over the QR code area).
    val upperMidTarget = Offset(
        x = targetRect.center.x,
        y = targetRect.top + targetRect.height * 0.42f,
    )
    // Final QR image center inside SponsorPayCard (slightly above card geometric center due to text below QR).
    val finalQrCenter = Offset(
        x = targetRect.center.x,
        y = targetRect.top + targetRect.height * 0.47f,
    )

    val flight = state.flightProgress.value
    val scaleP = state.scaleProgress.value
    val pulse = state.settlePulse.value
    val unfold = state.cardUnfoldProgress.value.coerceIn(0f, 1f)

    // Smooth arc upward from the card's left icon to the upper-center of the QR card,
    // then glide gently into the exact QR code position as the card unfolds around it.
    val arcLiftPx = with(density) { 24.dp.toPx() } * sin(flight * PI).toFloat()
    val midCenter = Offset(
        x = lerpF(startRect.center.x, upperMidTarget.x, flight),
        y = lerpF(startRect.center.y, upperMidTarget.y, flight) - arcLiftPx,
    )
    val currentCenter = Offset(
        x = lerpF(midCenter.x, finalQrCenter.x, unfold),
        y = lerpF(midCenter.y, finalQrCenter.y, unfold),
    )

    val startSizePx = startRect.width.coerceAtLeast(1f)
    // Match the white QR code square width (~72% of card width, max 220dp) so the dissolve aligns pixel-for-pixel.
    val qrBoxSizePx = (targetRect.width * 0.72f).coerceAtMost(with(density) { 220.dp.toPx() }).coerceAtLeast(startSizePx)
    val midTargetSizePx = (qrBoxSizePx * 0.88f).coerceAtLeast(startSizePx)
    val baseScale = lerpF(1f, midTargetSizePx / startSizePx, scaleP)
    val unfoldScale = lerpF(baseScale, qrBoxSizePx / startSizePx, unfold)
    val finalScale = unfoldScale * (1f - 0.04f * pulse)

    // Single full 360° 3D spin with subtle X-axis perspective tilt.
    val rotY = 360f * flight
    val rotX = -10f * sin(flight * PI).toFloat()
    val rotZ = -4f * sin(flight * PI).toFloat()

    // Long, silky cross-dissolve overlapping with SponsorPayCard's fade-in.
    val iconAlpha = 1f - smoothstepF(0.18f, 0.90f, unfold)
    if (iconAlpha <= 0.005f) return

    val iconDpSize = with(density) { startSizePx.toDp() }
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.seine_support_illustration),
            contentDescription = null,
            modifier = Modifier
                .size(iconDpSize)
                .graphicsLayer {
                    translationX = currentCenter.x - startSizePx * 0.5f
                    translationY = currentCenter.y - startSizePx * 0.5f
                    scaleX = finalScale
                    scaleY = finalScale
                    rotationY = rotY
                    rotationX = rotX
                    rotationZ = rotZ
                    cameraDistance = 12f * this.density
                    alpha = iconAlpha
                },
        )
    }
}

/**
 * Tracks whether the cold-start FlowMix-style staggered home entrance has already played
 * during the current process session so switching tabs does not replay it every time.
 */
internal object HomeLaunchAnimationState {
    var hasPlayed = false
}

/**
 * FlowMix-inspired staggered card entrance: each section floats upward into place with
 * a crisp spring bounce and subtle scale pop.
 */
@Composable
fun FlowMixCardEntrance(
    index: Int,
    modifier: Modifier = Modifier,
    playAnimation: Boolean = true,
    staggerMillis: Long = 52L,
    initialOffsetY: Dp = 26.dp,
    content: @Composable () -> Unit,
) {
    val progress = remember(playAnimation) { Animatable(if (playAnimation) 0f else 1f) }
    val alphaAnim = remember(playAnimation) { Animatable(if (playAnimation) 0f else 1f) }

    LaunchedEffect(playAnimation) {
        if (!playAnimation) {
            progress.snapTo(1f)
            alphaAnim.snapTo(1f)
            return@LaunchedEffect
        }
        val delayMs = (index * staggerMillis).coerceAtLeast(0L)
        if (delayMs > 0L) delay(delayMs)
        coroutineScope {
            launch {
                alphaAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 250, easing = LinearOutSlowInEasing),
                )
            }
            launch {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = 340f,
                        visibilityThreshold = 0.001f,
                    ),
                )
            }
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            val p = progress.value
            alpha = alphaAnim.value.coerceIn(0f, 1f)
            translationY = initialOffsetY.toPx() * (1f - p)
            val s = 0.94f + 0.06f * p
            scaleX = s
            scaleY = s
        },
    ) {
        content()
    }
}

/**
 * Material 3 Expressive-style morphing polygon loading indicator:
 * smoothly morphs between rounded organic shapes (clover, soft-burst, scalloped flower,
 * squircle) while rotating continuously.
 */
@Composable
fun MaterialExpressiveLoadingIndicator(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "MaterialExpressiveLoading")
    val morphCycle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "MorphCycle",
    )
    val continuousRotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "MorphRotation",
    )
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val baseRadius = size.minDimension * 0.44f

        val stage = morphCycle.toInt().coerceIn(0, 3)
        val rawT = morphCycle - stage
        val t = FastOutSlowInEasing.transform(rawT)
        val nextStage = (stage + 1) % 4

        fun shapeRadius(s: Int, theta: Float): Float = when (s) {
            // 4-petal clover
            0 -> baseRadius * (0.86f + 0.16f * cos(4f * theta))
            // 7-lobe soft burst
            1 -> baseRadius * (0.88f + 0.13f * cos(7f * theta))
            // Rounded pillow / oval
            2 -> baseRadius * (0.88f + 0.14f * cos(2f * theta))
            // 5-lobe flower
            else -> baseRadius * (0.86f + 0.15f * cos(5f * theta))
        }

        val samples = 72
        path.reset()
        for (i in 0 until samples) {
            val theta = (i.toFloat() / samples) * (2f * PI.toFloat())
            val r = lerpF(shapeRadius(stage, theta), shapeRadius(nextStage, theta), t)
            val x = cx + r * cos(theta)
            val y = cy + r * sin(theta)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        val stepRotation = stage * 90f + 90f * t
        rotate(degrees = continuousRotation * 0.5f + stepRotation * 0.5f, pivot = Offset(cx, cy)) {
            drawPath(path = path, color = color)
        }
    }
}
