package hk.uwu.reareye.ui.components.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.reareye.version.SeineProject
import hk.uwu.reareye.version.SeineRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiSheet
import ui.feice.mochi.basic.MochiSheetState
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.MochiTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class UpdateDownloadPhase {
    IDLE,
    DOWNLOADING,
    COMPLETED,
}

@Composable
internal fun UpdatePromptSheet(
    state: MochiSheetState,
    release: SeineRelease,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mochiColors = MochiTheme.colorScheme
    val isDark = mochiColors.isDark

    val accentGreen = if (isDark) Color(0xFF34D399) else Color(0xFF16A34A)
    val accentGreenContainer = if (isDark) Color(0xFF103828) else Color(0xFFE5F8EC)

    var phase by remember(release.tag) { mutableStateOf(UpdateDownloadPhase.IDLE) }
    var downloadProgress by remember(release.tag) { mutableFloatStateOf(0f) }
    var downloadedApkUri by remember(release.tag) { mutableStateOf<Uri?>(null) }
    var downloadJob by remember(release.tag) { mutableStateOf<Job?>(null) }
    var sheetTopY by remember { mutableFloatStateOf(Float.NaN) }
    var isSheetSettled by remember { mutableStateOf(false) }

    // Trigger icon entrance animation right after the sheet stops moving into place.
    LaunchedEffect(state.isVisible, sheetTopY) {
        if (!state.isVisible) {
            isSheetSettled = false
            sheetTopY = Float.NaN
        } else if (!sheetTopY.isNaN() && !isSheetSettled) {
            delay(90L)
            isSheetSettled = true
        }
    }

    LaunchedEffect(state.isVisible) {
        if (!state.isVisible) {
            downloadJob?.cancel()
            downloadJob = null
            phase = UpdateDownloadPhase.IDLE
            downloadProgress = 0f
            downloadedApkUri = null
        }
    }

    fun startUpdate() {
        if (release.apk == null) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url)))
            return
        }
        if (phase == UpdateDownloadPhase.DOWNLOADING) return
        val downloadedApk = downloadedApkUri
        if (downloadedApk != null) {
            downloadJob?.cancel()
            launchPackageInstaller(context, downloadedApk)
            return
        }
        downloadJob?.cancel()
        phase = UpdateDownloadPhase.DOWNLOADING
        downloadProgress = 0f
        downloadJob = scope.launch {
            try {
                val apkUri = SeineProject.downloadUpdate(context, release) { progress ->
                    withContext(Dispatchers.Main.immediate) { downloadProgress = progress }
                }
                downloadedApkUri = apkUri
                downloadProgress = 1f
                phase = UpdateDownloadPhase.COMPLETED
                Toast.makeText(context, R.string.home_update_saved_to_downloads, Toast.LENGTH_SHORT).show()
                // Let the completion animation finish before opening the installer.
                delay(920L)
                launchPackageInstaller(context, apkUri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                phase = UpdateDownloadPhase.IDLE
                downloadProgress = 0f
                Toast.makeText(context, R.string.home_update_download_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val currentVer = SeineProject.CURRENT_RELEASE.removePrefix("v")
    val targetVer = release.tag.removePrefix("v")
    val changelogText = release.body.ifBlank {
        stringResource(R.string.home_update_changelog_empty)
    }

    MochiSheet(
        state = state,
        onDismiss = {
            downloadJob?.cancel()
            downloadJob = null
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    sheetTopY = coords.positionInWindow().y
                }
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top-centered animated update & download badge
            UpdateDownloadBadge(
                sheetSettled = isSheetSettled,
                sheetVisible = state.isVisible,
                phase = phase,
                downloadProgress = downloadProgress,
                accentGreen = accentGreen,
                modifier = Modifier.size(76.dp),
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.home_update_warning_title),
                style = MochiTheme.textStyles.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp,
                ),
                color = mochiColors.onSurface,
            )

            Spacer(Modifier.height(8.dp))

            // Version row: current -> latest
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "v$currentVer",
                    style = MochiTheme.textStyles.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = mochiColors.onSurfaceVariant,
                )
                Text(
                    text = "→",
                    style = MochiTheme.textStyles.bodyMedium,
                    color = mochiColors.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(accentGreenContainer)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "v$targetVer",
                        style = MochiTheme.textStyles.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = accentGreen,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Changelog card (takes remaining middle height and scrolls if needed)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(MochiRoundedRectangle(20.dp))
                    .background(mochiColors.surfaceContainerLow)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_update_changelog_title),
                    style = MochiTheme.textStyles.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = mochiColors.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = changelogText,
                        style = MochiTheme.textStyles.bodyMedium.copy(lineHeight = 21.sp),
                        color = mochiColors.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Bottom-pinned action buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val primaryLabel = when (phase) {
                    UpdateDownloadPhase.IDLE -> stringResource(
                        if (release.apk == null) R.string.home_update_view_release else R.string.home_update_download,
                    )
                    UpdateDownloadPhase.DOWNLOADING -> stringResource(
                        R.string.home_update_downloading,
                        (downloadProgress * 100f).roundToInt().coerceIn(0, 100),
                    )
                    UpdateDownloadPhase.COMPLETED -> stringResource(R.string.home_update_install)
                }
                MochiButton(
                    onClick = ::startUpdate,
                    enabled = phase != UpdateDownloadPhase.DOWNLOADING,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(primaryLabel)
                }
                MochiButton(
                    onClick = {
                        downloadJob?.cancel()
                        downloadJob = null
                        state.hide()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = MochiButtonStyle.Tonal,
                ) {
                    Text(stringResource(R.string.home_update_later))
                }
            }
        }
    }
}

@Composable
private fun UpdateDownloadBadge(
    sheetSettled: Boolean,
    sheetVisible: Boolean,
    phase: UpdateDownloadPhase,
    downloadProgress: Float,
    accentGreen: Color,
    modifier: Modifier = Modifier,
) {
    // Entrance: 1) arrow fades + springs in after sheet settles, 2) green ring sweeps 360° around it.
    val arrowAlpha = remember { Animatable(0f) }
    val arrowScale = remember { Animatable(0.72f) }
    val ringSweep = remember { Animatable(0f) }

    // Completion: circle spins 360°, then Apple-Pay-style checkmark draws smoothly.
    val completeSpin = remember { Animatable(0f) }
    val checkProgress = remember { Animatable(0f) }
    val completePop = remember { Animatable(1f) }

    LaunchedEffect(sheetVisible) {
        if (!sheetVisible) {
            arrowAlpha.snapTo(0f)
            arrowScale.snapTo(0.72f)
            ringSweep.snapTo(0f)
            completeSpin.snapTo(0f)
            checkProgress.snapTo(0f)
            completePop.snapTo(1f)
        }
    }

    LaunchedEffect(sheetSettled) {
        if (sheetSettled && arrowAlpha.value < 0.99f) {
            launch {
                arrowAlpha.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
            }
            launch {
                arrowScale.animateTo(1f, spring(dampingRatio = 0.56f, stiffness = 420f))
            }
            delay(130L)
            ringSweep.animateTo(360f, tween(860, easing = FastOutSlowInEasing))
        }
    }

    LaunchedEffect(phase) {
        if (phase == UpdateDownloadPhase.COMPLETED) {
            launch {
                completeSpin.snapTo(0f)
                completeSpin.animateTo(360f, tween(520, easing = FastOutSlowInEasing))
            }
            launch {
                delay(180L)
                checkProgress.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
                completePop.snapTo(0.94f)
                completePop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 480f))
            }
        } else if (phase == UpdateDownloadPhase.IDLE) {
            completeSpin.snapTo(0f)
            checkProgress.snapTo(0f)
            completePop.snapTo(1f)
        }
    }

    val waveTransition = rememberInfiniteTransition(label = "UpdateWavePhase")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "WavePhaseRad",
    )

    Canvas(
        modifier = modifier.graphicsLayer {
            val s = completePop.value
            scaleX = s
            scaleY = s
        },
    ) {
        val diameter = size.minDimension
        val strokePx = 5.0.dp.toPx()
        val inset = strokePx / 2f
        val ringSize = Size(diameter - strokePx, diameter - strokePx)
        val center = Offset(size.width / 2f, size.height / 2f)
        val innerRadius = (diameter - strokePx * 2f) / 2f

        // Subtle track ring once sweep starts
        if (ringSweep.value > 0.5f) {
            drawCircle(
                color = accentGreen.copy(alpha = 0.14f),
                radius = (diameter - strokePx) / 2f,
                center = center,
                style = Stroke(width = strokePx),
            )
        }

        // Clipped inner circle for rising green wave / solid completion fill
        val fillLevel = when (phase) {
            UpdateDownloadPhase.IDLE -> 0f
            UpdateDownloadPhase.DOWNLOADING -> downloadProgress.coerceIn(0f, 1f)
            UpdateDownloadPhase.COMPLETED -> 1f
        }

        val innerClipPath = Path().apply {
            addOval(
                androidx.compose.ui.geometry.Rect(
                    left = center.x - innerRadius,
                    top = center.y - innerRadius,
                    right = center.x + innerRadius,
                    bottom = center.y + innerRadius,
                ),
            )
        }

        if (fillLevel > 0.005f) {
            clipPath(innerClipPath) {
                if (fillLevel >= 0.995f) {
                    drawRect(color = accentGreen)
                } else {
                    val baseY = (center.y + innerRadius) - (innerRadius * 2f) * fillLevel
                    val amplitude = (4.5.dp.toPx() * (1f - abs(fillLevel - 0.5f) * 0.9f)).coerceAtLeast(1.5.dp.toPx())
                    val left = center.x - innerRadius
                    val right = center.x + innerRadius
                    val bottom = center.y + innerRadius + 4f

                    // Back translucent wave
                    val backWave = Path().apply {
                        moveTo(left, bottom)
                        var x = left
                        while (x <= right) {
                            val norm = (x - left) / (innerRadius * 2f)
                            val y = baseY + sin(norm * 2.2f * PI.toFloat() - wavePhase + 1.4f) * (amplitude * 0.85f)
                            lineTo(x, y)
                            x += 3f
                        }
                        lineTo(right, bottom)
                        close()
                    }
                    drawPath(backWave, color = accentGreen.copy(alpha = 0.45f))

                    // Front primary wave
                    val frontWave = Path().apply {
                        moveTo(left, bottom)
                        var x = left
                        while (x <= right) {
                            val norm = (x - left) / (innerRadius * 2f)
                            val y = baseY + sin(norm * 2.6f * PI.toFloat() + wavePhase) * amplitude
                            lineTo(x, y)
                            x += 3f
                        }
                        lineTo(right, bottom)
                        close()
                    }
                    drawPath(frontWave, color = accentGreen)
                }
            }
        }

        // Rotating green ring (sweeps 360° on enter, spins 360° on completion)
        val totalRotation = if (phase == UpdateDownloadPhase.COMPLETED) completeSpin.value else 0f
        rotate(totalRotation, pivot = center) {
            if (ringSweep.value > 0.5f) {
                drawArc(
                    color = accentGreen,
                    startAngle = -90f,
                    sweepAngle = ringSweep.value,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = ringSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }

        // Upward green arrow (fades & springs in; adapts to white over filled green wave)
        val arrowVisibility = if (phase == UpdateDownloadPhase.COMPLETED) {
            (1f - checkProgress.value * 2.2f).coerceIn(0f, 1f)
        } else {
            arrowAlpha.value
        }

        if (arrowVisibility > 0.01f) {
            val s = arrowScale.value
            val halfW = diameter * 0.16f * s
            val halfH = diameter * 0.19f * s
            val arrowStroke = 3.0.dp.toPx()

            fun drawUpArrow(color: Color) {
                val shaftPath = Path().apply {
                    moveTo(center.x, center.y + halfH)
                    lineTo(center.x, center.y - halfH)
                    moveTo(center.x - halfW, center.y - halfH * 0.15f)
                    lineTo(center.x, center.y - halfH)
                    lineTo(center.x + halfW, center.y - halfH * 0.15f)
                }
                drawPath(
                    path = shaftPath,
                    color = color,
                    style = Stroke(width = arrowStroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            // Draw green arrow on unfilled upper part, and crisp white arrow over the rising green wave
            drawUpArrow(accentGreen.copy(alpha = arrowVisibility))
            if (fillLevel > 0.02f) {
                val waveClip = Path().apply {
                    val baseY = (center.y + innerRadius) - (innerRadius * 2f) * fillLevel
                    addRect(
                        androidx.compose.ui.geometry.Rect(
                            left = center.x - innerRadius,
                            top = baseY,
                            right = center.x + innerRadius,
                            bottom = center.y + innerRadius,
                        ),
                    )
                }
                clipPath(innerClipPath) {
                    clipPath(waveClip) {
                        drawUpArrow(Color.White.copy(alpha = arrowVisibility))
                    }
                }
            }
        }

        // Apple-Pay-style animated checkmark in the center upon completion
        if (checkProgress.value > 0.01f) {
            val fullCheckPath = Path().apply {
                moveTo(center.x - diameter * 0.18f, center.y + diameter * 0.01f)
                lineTo(center.x - diameter * 0.04f, center.y + diameter * 0.15f)
                lineTo(center.x + diameter * 0.20f, center.y - diameter * 0.13f)
            }
            val measure = PathMeasure()
            measure.setPath(fullCheckPath, false)
            val drawnPath = Path()
            measure.getSegment(0f, measure.length * checkProgress.value, drawnPath, true)
            drawPath(
                path = drawnPath,
                color = Color.White,
                style = Stroke(
                    width = 3.6.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

private fun launchPackageInstaller(context: Context, apkUri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.home_update_install_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
