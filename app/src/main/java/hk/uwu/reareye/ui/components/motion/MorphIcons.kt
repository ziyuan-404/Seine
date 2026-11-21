package hk.uwu.reareye.ui.components.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val MorphBounceSpring = spring<Float>(
    dampingRatio = 0.36f,
    stiffness = 460f,
)

private val MorphFillSpring = spring<Float>(
    dampingRatio = 0.72f,
    stiffness = 520f,
)

/**
 * Morphicons-inspired minimalist Home icon:
 * - Hollow rounded stroke when unselected
 * - Solid filled house with crisp pill doorway cutout when selected
 * - Q-bounce spring scale & vertical hop on selection
 */
@Composable
fun MorphHomeIcon(
    selected: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val bounceScale = remember { Animatable(1f) }
    val hopOffset = remember { Animatable(0f) }
    val fillProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MorphFillSpring,
        label = "MorphHomeFill",
    )

    LaunchedEffect(selected) {
        if (selected) {
            launch {
                bounceScale.snapTo(0.78f)
                bounceScale.animateTo(1f, MorphBounceSpring)
            }
            launch {
                hopOffset.snapTo(-2.5f)
                hopOffset.animateTo(0f, MorphBounceSpring)
            }
        } else {
            bounceScale.animateTo(1f, MorphFillSpring)
            hopOffset.animateTo(0f, MorphFillSpring)
        }
    }

    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                compositingStrategy = if (fillProgress > 0.01f) {
                    CompositingStrategy.Offscreen
                } else {
                    CompositingStrategy.Auto
                }
                val s = bounceScale.value
                scaleX = s
                scaleY = s
                translationY = hopOffset.value.dp.toPx()
            },
    ) {
        val u = this.size.minDimension / 24f
        val strokePx = 1.95f * u

        val housePath = Path().apply {
            moveTo(3.5f * u, 10.6f * u)
            quadraticTo(3.5f * u, 9.6f * u, 4.3f * u, 9.0f * u)
            lineTo(11.1f * u, 3.6f * u)
            quadraticTo(12.0f * u, 2.9f * u, 12.9f * u, 3.6f * u)
            lineTo(19.7f * u, 9.0f * u)
            quadraticTo(20.5f * u, 9.6f * u, 20.5f * u, 10.6f * u)
            lineTo(20.5f * u, 18.6f * u)
            quadraticTo(20.5f * u, 20.5f * u, 18.6f * u, 20.5f * u)
            lineTo(5.4f * u, 20.5f * u)
            quadraticTo(3.5f * u, 20.5f * u, 3.5f * u, 18.6f * u)
            close()
        }

        if (fillProgress > 0.01f) {
            drawPath(
                path = housePath,
                color = tint.copy(alpha = fillProgress),
            )
            // Cutout arched doorway inside filled house
            val doorRect = Rect(
                left = 9.75f * u,
                top = 13.0f * u,
                right = 14.25f * u,
                bottom = 20.7f * u,
            )
            val doorPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = doorRect,
                        topLeft = CornerRadius(2.25f * u, 2.25f * u),
                        topRight = CornerRadius(2.25f * u, 2.25f * u),
                    ),
                )
            }
            drawPath(
                path = doorPath,
                color = Color.Black.copy(alpha = fillProgress),
                blendMode = BlendMode.DstOut,
            )
        }

        if (fillProgress < 0.99f) {
            val outlineAlpha = 1f - fillProgress
            drawPath(
                path = housePath,
                color = tint.copy(alpha = outlineAlpha),
                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawLine(
                color = tint.copy(alpha = outlineAlpha),
                start = Offset(9.8f * u, 16.0f * u),
                end = Offset(14.2f * u, 16.0f * u),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Morphicons-inspired minimalist Configuration / Sliders icon:
 * - Two clean horizontal tracks with hollow circular knobs when unselected
 * - Solid filled capsule tracks & knobs with Q-bounce + micro-tilt when selected
 */
@Composable
fun MorphConfigIcon(
    selected: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val bounceScale = remember { Animatable(1f) }
    val knobShift = remember { Animatable(if (selected) 1f else 0f) }
    val fillProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MorphFillSpring,
        label = "MorphConfigFill",
    )

    LaunchedEffect(selected) {
        if (selected) {
            launch {
                bounceScale.snapTo(0.80f)
                bounceScale.animateTo(1f, MorphBounceSpring)
            }
            launch {
                knobShift.animateTo(1f, MorphBounceSpring)
            }
        } else {
            launch { bounceScale.animateTo(1f, MorphFillSpring) }
            launch { knobShift.animateTo(0f, MorphFillSpring) }
        }
    }

    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                compositingStrategy = if (fillProgress > 0.01f) {
                    CompositingStrategy.Offscreen
                } else {
                    CompositingStrategy.Auto
                }
                val s = bounceScale.value
                scaleX = s
                scaleY = s
            },
    ) {
        val u = this.size.minDimension / 24f
        val strokePx = 1.95f * u
        val shift = knobShift.value

        // Top row: knob slides slightly right on select
        val topY = 7.5f * u
        val topKnobX = (9.0f + 5.5f * shift) * u
        // Bottom row: knob slides slightly left on select
        val botY = 16.5f * u
        val botKnobX = (15.0f - 5.5f * shift) * u
        val knobR = 2.65f * u

        if (fillProgress > 0.01f) {
            val alpha = fillProgress
            // Thicker filled track bars
            drawLine(
                color = tint.copy(alpha = alpha * 0.42f),
                start = Offset(4.0f * u, topY),
                end = Offset(20.0f * u, topY),
                strokeWidth = 2.5f * u,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint.copy(alpha = alpha * 0.42f),
                start = Offset(4.0f * u, botY),
                end = Offset(20.0f * u, botY),
                strokeWidth = 2.5f * u,
                cap = StrokeCap.Round,
            )
            // Solid filled knobs
            drawCircle(
                color = tint.copy(alpha = alpha),
                radius = knobR + 0.5f * u,
                center = Offset(topKnobX, topY),
            )
            drawCircle(
                color = tint.copy(alpha = alpha),
                radius = knobR + 0.5f * u,
                center = Offset(botKnobX, botY),
            )
        }

        if (fillProgress < 0.99f) {
            val alpha = 1f - fillProgress
            // Left/Right segments around top knob
            drawLine(
                color = tint.copy(alpha = alpha),
                start = Offset(4.0f * u, topY),
                end = Offset((topKnobX - knobR - 0.6f * u).coerceAtLeast(4.0f * u), topY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint.copy(alpha = alpha),
                start = Offset((topKnobX + knobR + 0.6f * u).coerceAtMost(20.0f * u), topY),
                end = Offset(20.0f * u, topY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = tint.copy(alpha = alpha),
                radius = knobR,
                center = Offset(topKnobX, topY),
                style = Stroke(width = strokePx),
            )

            // Left/Right segments around bottom knob
            drawLine(
                color = tint.copy(alpha = alpha),
                start = Offset(4.0f * u, botY),
                end = Offset((botKnobX - knobR - 0.6f * u).coerceAtLeast(4.0f * u), botY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint.copy(alpha = alpha),
                start = Offset((botKnobX + knobR + 0.6f * u).coerceAtMost(20.0f * u), botY),
                end = Offset(20.0f * u, botY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = tint.copy(alpha = alpha),
                radius = knobR,
                center = Offset(botKnobX, botY),
                style = Stroke(width = strokePx),
            )
        }
    }
}

/**
 * Morphicons-inspired minimalist About / Info icon:
 * - Hollow circle + minimalist "i" when unselected
 * - Solid filled circle with crisp "i" cutout + Q-bounce when selected
 */
@Composable
fun MorphAboutIcon(
    selected: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val bounceScale = remember { Animatable(1f) }
    val dotPop = remember { Animatable(0f) }
    val fillProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MorphFillSpring,
        label = "MorphAboutFill",
    )

    LaunchedEffect(selected) {
        if (selected) {
            launch {
                bounceScale.snapTo(0.78f)
                bounceScale.animateTo(1f, MorphBounceSpring)
            }
            launch {
                dotPop.snapTo(-1.8f)
                dotPop.animateTo(0f, MorphBounceSpring)
            }
        } else {
            bounceScale.animateTo(1f, MorphFillSpring)
            dotPop.animateTo(0f, MorphFillSpring)
        }
    }

    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                compositingStrategy = if (fillProgress > 0.01f) {
                    CompositingStrategy.Offscreen
                } else {
                    CompositingStrategy.Auto
                }
                val s = bounceScale.value
                scaleX = s
                scaleY = s
            },
    ) {
        val u = this.size.minDimension / 24f
        val strokePx = 1.95f * u
        val center = Offset(12f * u, 12f * u)
        val radius = 8.75f * u
        val dotY = (8.1f + dotPop.value) * u

        if (fillProgress > 0.01f) {
            drawCircle(
                color = tint.copy(alpha = fillProgress),
                radius = radius + strokePx * 0.45f,
                center = center,
            )
            // Cutout "i" dot & stem
            drawCircle(
                color = Color.Black.copy(alpha = fillProgress),
                radius = 1.25f * u,
                center = Offset(12f * u, dotY),
                blendMode = BlendMode.DstOut,
            )
            drawLine(
                color = Color.Black.copy(alpha = fillProgress),
                start = Offset(12f * u, 11.4f * u),
                end = Offset(12f * u, 16.4f * u),
                strokeWidth = 2.15f * u,
                cap = StrokeCap.Round,
                blendMode = BlendMode.DstOut,
            )
        }

        if (fillProgress < 0.99f) {
            val alpha = 1f - fillProgress
            drawCircle(
                color = tint.copy(alpha = alpha),
                radius = radius,
                center = center,
                style = Stroke(width = strokePx),
            )
            drawCircle(
                color = tint.copy(alpha = alpha),
                radius = 1.15f * u,
                center = Offset(12f * u, dotY),
            )
            drawLine(
                color = tint.copy(alpha = alpha),
                start = Offset(12f * u, 11.4f * u),
                end = Offset(12f * u, 16.4f * u),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Morphicons-inspired 2x2 rounded bento grid menu icon replacing the 3-dots icon.
 * Morphs smoothly with a 45° Q-bounce rotation and tighter spacing when expanded.
 */
@Composable
fun MorphTopMenuIcon(
    expanded: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val rotation = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    val morph by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = MorphBounceSpring,
        label = "MorphMenuExpand",
    )

    LaunchedEffect(expanded) {
        launch {
            rotation.animateTo(if (expanded) 45f else 0f, MorphBounceSpring)
        }
        launch {
            scale.snapTo(0.84f)
            scale.animateTo(1f, MorphBounceSpring)
        }
    }

    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val s = scale.value
                scaleX = s
                scaleY = s
            },
    ) {
        val u = this.size.minDimension / 24f
        val tileSize = (5.4f - 0.6f * morph) * u
        val corner = (1.8f + 0.6f * morph) * u
        val offset = (4.8f + 0.5f * morph) * u
        val strokePx = 1.85f * u

        rotate(rotation.value) {
            val centers = listOf(
                Offset(12f * u - offset, 12f * u - offset),
                Offset(12f * u + offset, 12f * u - offset),
                Offset(12f * u - offset, 12f * u + offset),
                Offset(12f * u + offset, 12f * u + offset),
            )
            centers.forEachIndexed { index, c ->
                val topLeft = Offset(c.x - tileSize / 2f, c.y - tileSize / 2f)
                if (index == 0 || morph > 0.35f) {
                    drawRoundRect(
                        color = tint,
                        topLeft = topLeft,
                        size = Size(tileSize, tileSize),
                        cornerRadius = CornerRadius(corner, corner),
                    )
                } else {
                    drawRoundRect(
                        color = tint,
                        topLeft = topLeft,
                        size = Size(tileSize, tileSize),
                        cornerRadius = CornerRadius(corner, corner),
                        style = Stroke(width = strokePx),
                    )
                }
            }
        }
    }
}
