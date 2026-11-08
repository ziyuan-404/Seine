package hk.uwu.reareye.ui.components.config

import android.widget.Toast
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.RoundedCorner
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiTitleBar
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonDefaults
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiTheme
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearAppDefaults
import hk.uwu.reareye.ui.config.PrefsManager
import kotlinx.coroutines.delay
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_REGION_SIZE = .2f
private val RegionSliderInsets = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val RegionSliderSpacing = 8.dp

private data class RearDisplayGeometry(
    val width: Int = 976,
    val height: Int = 596,
    val safeRegion: RearDisplayRegion = RearDisplayRegion(296f / 976f, 0f, 1f, 1f),
    val cornerRadius: Float = 101f,
) {
    companion object {
        @Suppress("DEPRECATION")
        fun read(context: Context): RearDisplayGeometry = runCatching {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(1)
                ?: error("Rear display unavailable")
            val dimensions = Point().also(display::getRealSize)
            require(dimensions.x > 0 && dimensions.y > 0)
            val cutout = display.cutout
            val safe = RearDisplayRegion(
                (cutout?.safeInsetLeft ?: 0).toFloat() / dimensions.x,
                (cutout?.safeInsetTop ?: 0).toFloat() / dimensions.y,
                1f - (cutout?.safeInsetRight ?: 0).toFloat() / dimensions.x,
                1f - (cutout?.safeInsetBottom ?: 0).toFloat() / dimensions.y,
            )
            val corner = listOf(RoundedCorner.POSITION_TOP_RIGHT, RoundedCorner.POSITION_BOTTOM_RIGHT)
                .mapNotNull { position -> display.getRoundedCorner(position)?.radius?.takeIf { it > 0 } }
                .maxOrNull()?.toFloat() ?: (101f * dimensions.x / 976f)
            RearDisplayGeometry(dimensions.x, dimensions.y, safe, corner)
        }.getOrDefault(RearDisplayGeometry())
    }
}

private data class RearDisplayRegion(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    fun encode(): String = JSONArray(listOf(left, top, right, bottom)).toString()

    companion object {
        fun read(value: String, defaultRegion: RearDisplayRegion): RearDisplayRegion = runCatching {
            val safe = RearDisplayRegion()
            val array = JSONArray(value)
            require(array.length() == 4)
            val values = (0..3).map { array.getDouble(it).toFloat().also { value -> require(value.isFinite()) } }
            val left = values[0].coerceIn(safe.left, maxOf(safe.left, safe.right - MIN_REGION_SIZE))
            val top = values[1].coerceIn(safe.top, maxOf(safe.top, safe.bottom - MIN_REGION_SIZE))
            RearDisplayRegion(left, top, values[2].coerceIn(minOf(safe.right, left + MIN_REGION_SIZE), safe.right),
                values[3].coerceIn(minOf(safe.bottom, top + MIN_REGION_SIZE), safe.bottom))
        }.getOrDefault(defaultRegion)
    }
}

@Composable
fun RearAppDisplayManagerScreen(
    prefsManager: PrefsManager,
    onBack: () -> Unit,
    embedded: Boolean = false,
    bottomPadding: Dp = 0.dp,
    extraSettings: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val geometry = remember(context) { RearDisplayGeometry.read(context) }
    val safe = geometry.safeRegion
    val limits = RearDisplayRegion()
    val defaultRegion = remember { RearDisplayRegion.read(RearAppDefaults.REGION, limits) }
    var region by remember { mutableStateOf(defaultRegion) }
    var automatic by remember { mutableStateOf(false) }
    var rounded by remember { mutableStateOf(true) }
    var cameraFill by remember { mutableStateOf(false) }
    var radius by remember { mutableStateOf(RearAppDefaults.CORNER_RADIUS) }
    var loaded by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    BackHandler(enabled = !embedded && !LocalConfigOwnsBack.current, onBack = onBack)
    LaunchedEffect(Unit) {
        repeat(40) {
            if (prefsManager.isRemoteReady()) {
                val raw = prefsManager.getString(ConfigKeys.REAR_APP_REGION, RearAppDefaults.REGION)
                region = if (raw.isBlank()) safe else RearDisplayRegion.read(raw, defaultRegion)
                automatic = raw.isBlank()
                rounded = prefsManager.getBoolean(ConfigKeys.REAR_APP_ROUNDED, true)
                cameraFill = prefsManager.getBoolean(ConfigKeys.REAR_APP_CAMERA_FILL, false)
                radius = prefsManager.getFloat(ConfigKeys.REAR_APP_CORNER_RADIUS, RearAppDefaults.CORNER_RADIUS).coerceIn(0f, 180f)
                loaded = true
                return@LaunchedEffect
            }
            delay(200L)
        }
        unavailable = true
    }
    Scaffold(topBar = {
        if (!embedded) MochiTitleBar(
            title = stringResource(R.string.rear_app_display_title),
            navigationIcon = {
                MochiBackButton(onClick = onBack)
            },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = ConfigUi.HorizontalInset),
            contentPadding = PaddingValues(top = (if (embedded) 0.dp else padding.calculateTopPadding()) + 12.dp,
                bottom = (if (embedded) bottomPadding else padding.calculateBottomPadding()) + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!loaded) {
                item { Text(stringResource(if (unavailable) R.string.rear_app_region_unavailable else R.string.rear_app_region_loading)) }
            } else {
                item {
                    Text(stringResource(R.string.rear_app_region_hint),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                item {
                    RearDisplayRegionPreview(region, geometry, rounded, radius) {
                        region = it
                        automatic = false
                    }
                }
                item {
                    Text(stringResource(if (automatic) R.string.rear_app_region_auto else R.string.rear_app_region_custom))
                    Text(stringResource(R.string.rear_app_region_coordinates,
                        region.left * 100f, region.top * 100f, region.right * 100f, region.bottom * 100f),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                item {
                    MochiListCard {
                        Column(
                            modifier = Modifier.padding(RegionSliderInsets),
                            verticalArrangement = Arrangement.spacedBy(RegionSliderSpacing),
                        ) {
                            RegionEdgeSlider(R.string.rear_app_region_left, region.left,
                                limits.left..maxOf(limits.left, region.right - MIN_REGION_SIZE)) {
                                region = region.copy(left = it); automatic = false
                            }
                            RegionEdgeSlider(R.string.rear_app_region_top, region.top,
                                limits.top..maxOf(limits.top, region.bottom - MIN_REGION_SIZE)) {
                                region = region.copy(top = it); automatic = false
                            }
                            RegionEdgeSlider(R.string.rear_app_region_right, region.right,
                                minOf(limits.right, region.left + MIN_REGION_SIZE)..limits.right) {
                                region = region.copy(right = it); automatic = false
                            }
                            RegionEdgeSlider(R.string.rear_app_region_bottom, region.bottom,
                                minOf(limits.bottom, region.top + MIN_REGION_SIZE)..limits.bottom) {
                                region = region.copy(bottom = it); automatic = false
                            }
                        }
                    }
                }
                item {
                    MochiListCard {
                        ConfigSwitchRow(title = stringResource(R.string.rear_app_camera_fill),
                            summary = stringResource(R.string.rear_app_camera_fill_desc),
                            checked = cameraFill, onCheckedChange = { cameraFill = it })
                    }
                }
                item {
                    MochiListCard {
                        ConfigSwitchRow(title = stringResource(R.string.rear_app_rounded),
                            checked = rounded, onCheckedChange = { rounded = it })
                        if (rounded) {
                            ConfigSwitchRow(title = stringResource(R.string.rear_app_corner_auto),
                                checked = radius == 0f,
                                onCheckedChange = { radius = if (it) 0f else RearAppDefaults.CORNER_RADIUS })
                            if (radius > 0f) {
                                DisplayAdjustmentSlider(
                                    title = stringResource(R.string.rear_app_corner_radius, radius.roundToInt()),
                                    value = radius,
                                    range = 1f..180f,
                                    modifier = Modifier.padding(RegionSliderInsets),
                                    onChange = { radius = it.roundToInt().toFloat() },
                                )
                            }
                        }
                    }
                }
                item(key = "region_actions") {
                    ConfigCard {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            MochiButton(
                                onClick = {
                                    region = defaultRegion
                                    automatic = false
                                    rounded = true
                                    radius = RearAppDefaults.CORNER_RADIUS
                                    cameraFill = false
                                },
                                style = MochiButtonStyle.Outlined,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                MochiIonicon("refresh", size = 16.dp, tint = LocalContentColor.current)
                                Text(stringResource(R.string.rear_app_region_reset), color = LocalContentColor.current)
                            }
                            MochiButton(
                                onClick = {
                                    prefsManager.prefs.edit()
                                        .putString(ConfigKeys.REAR_APP_REGION, if (automatic) "" else region.encode())
                                        .putBoolean(ConfigKeys.REAR_APP_ROUNDED, rounded)
                                        .putBoolean(ConfigKeys.REAR_APP_CAMERA_FILL, cameraFill)
                                        .putFloat(ConfigKeys.REAR_APP_CORNER_RADIUS, radius)
                                        .apply()
                                    Toast.makeText(context, R.string.rear_app_region_saved, Toast.LENGTH_SHORT).show()
                                    if (!embedded) onBack()
                                },
                                colors = MochiButtonDefaults.colors(MochiButtonStyle.Filled).copy(
                                    container = MiuixTheme.colorScheme.primary,
                                    content = MiuixTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                MochiIonicon("checkmark", size = 16.dp, tint = LocalContentColor.current)
                                Text(stringResource(R.string.rear_app_region_save), color = LocalContentColor.current)
                            }
                            Text(
                                text = stringResource(R.string.rear_app_region_unsaved),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MochiTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
            if (extraSettings != null) {
                item(key = "application_settings") { extraSettings() }
            }
        }
    }
}

@Composable
private fun RegionEdgeSlider(
    titleRes: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val title = stringResource(titleRes)
    DisplayAdjustmentSlider(
        title = "$title  ${(value * 1000f).roundToInt() / 10f}%",
        value = value,
        range = range,
        sliderDescription = title,
        onChange = onChange,
    )
}

@Composable
private fun DisplayAdjustmentSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    sliderDescription: String = title,
    onChange: (Float) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(RegionSliderSpacing)) {
        Text(title)
        // Keep Slider's range nonempty when the bounds meet at the minimum region size.
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.coerceIn(range.start, range.endInclusive)) },
            valueRange = range.start..maxOf(range.start + .00001f, range.endInclusive),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = sliderDescription },
        )
    }
}

@Composable
private fun RearDisplayRegionPreview(region: RearDisplayRegion, geometry: RearDisplayGeometry, rounded: Boolean, radius: Float,
    onChange: (RearDisplayRegion) -> Unit) {
    val latest by rememberUpdatedState(region)
    val change by rememberUpdatedState(onChange)
    val currentRounded by rememberUpdatedState(rounded)
    val currentRadius by rememberUpdatedState(radius)
    val description = stringResource(R.string.rear_app_region_preview_description)
    val safe = geometry.safeRegion
    Canvas(Modifier.fillMaxWidth().aspectRatio(geometry.width.toFloat() / geometry.height)
        .semantics { contentDescription = description }
        .pointerInput(geometry) {
            var handle = -1
            detectDragGestures(onDragStart = { start ->
                val area = latest
                val previewSize = Size(size.width.toFloat(), size.height.toFloat())
                val curve = previewCornerRadius(area, previewSize, geometry, currentRounded, currentRadius)
                val dotRadius = 4.dp.toPx()
                val handles = previewHandles(area, previewSize, curve, dotRadius)
                val hitRadius = 28.dp.toPx()
                handle = handles.indices.minByOrNull { corner ->
                    handles[corner].minOf { (it - start).getDistanceSquared() }
                }?.takeIf { corner ->
                    handles[corner].any { (it - start).getDistanceSquared() <= hitRadius * hitRadius }
                } ?: -1
                val point = Offset(start.x / size.width, start.y / size.height)
                if (handle == -1 && point.x in area.left..area.right &&
                    point.y in area.top..area.bottom) handle = 2
            }, onDrag = { event, delta ->
                if (handle < 0) return@detectDragGestures
                event.consume()
                val area = latest
                val dx = delta.x / size.width
                val dy = delta.y / size.height
                val next = if (handle == 2) {
                    val width = area.right - area.left
                    val height = area.bottom - area.top
                    val left = (area.left + dx).coerceIn(0f, maxOf(0f, 1f - width))
                    val top = (area.top + dy).coerceIn(0f, maxOf(0f, 1f - height))
                    RearDisplayRegion(left, top, minOf(1f, left + width), minOf(1f, top + height))
                } else area.copy(
                    left = (area.left + dx).coerceIn(0f, maxOf(0f, area.right - MIN_REGION_SIZE)),
                    top = if (handle == 0) (area.top + dy)
                        .coerceIn(0f, maxOf(0f, area.bottom - MIN_REGION_SIZE)) else area.top,
                    bottom = if (handle == 1) (area.bottom + dy)
                        .coerceIn(minOf(1f, area.top + MIN_REGION_SIZE), 1f) else area.bottom,
                )
                change(next)
            }, onDragEnd = { handle = -1 }, onDragCancel = { handle = -1 })
        }) {
        val physicalRadius = size.width * geometry.cornerRadius / geometry.width
        val physicalOutline = Path().apply {
            addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(physicalRadius)))
        }
        // The camera mask and app preview share the physical screen's rounded outline.
        // App rounding remains a separate draft setting and never changes this outer frame.
        val curve = previewCornerRadius(region, size, geometry, currentRounded, currentRadius)
        clipPath(physicalOutline) {
            drawRect(Color(0xFF181C25))
            drawRect(Color(0xFF464A53), size = Size(safe.left * size.width, size.height))
            drawRect(Color(0xFF464A53), size = Size(size.width, safe.top * size.height))
            drawRect(Color(0xFF464A53), topLeft = Offset(safe.right * size.width, 0f),
                size = Size((1f - safe.right) * size.width, size.height))
            drawRect(Color(0xFF464A53), topLeft = Offset(0f, safe.bottom * size.height),
                size = Size(size.width, (1f - safe.bottom) * size.height))
            val origin = Offset(region.left * size.width, region.top * size.height)
            val activeSize = Size((region.right - region.left) * size.width,
                (region.bottom - region.top) * size.height)
            drawRoundRect(Color(0xFF364F7B), origin, activeSize, CornerRadius(curve))
            drawRoundRect(Color(0xFF95BBFF), origin, activeSize, CornerRadius(curve), style = Stroke(2.dp.toPx()))
            // Physical camera lenses remain visible even when the application is deliberately
            // expanded into the surrounding camera area, matching the stock illustration.
            if (safe.left > .1f) listOf(.25f, .75f).forEach { y ->
                val lens = min(size.height * .22f, size.width * safe.left * .46f)
                val center = Offset(size.width * safe.left / 2f, size.height * y)
                drawCircle(Color(0xFF0B0D11), lens, center)
                drawCircle(Color(0xFF747983), lens, center, style = Stroke(size.height * .012f))
            }
        }
        val dotRadius = 4.dp.toPx()
        previewHandles(region, size, curve, dotRadius).forEach { points ->
            points.forEach { drawCircle(Color.White, dotRadius, it) }
        }
    }
}

private fun previewCornerRadius(
    region: RearDisplayRegion,
    size: Size,
    geometry: RearDisplayGeometry,
    rounded: Boolean,
    radius: Float,
): Float {
    if (!rounded) return 0f
    val pixels = if (radius == 0f) geometry.cornerRadius else radius
    return min(
        pixels * size.width / geometry.width,
        min((region.right - region.left) * size.width, (region.bottom - region.top) * size.height) / 2f,
    )
}

private fun previewHandles(
    region: RearDisplayRegion,
    size: Size,
    curve: Float,
    dotRadius: Float,
): List<List<Offset>> {
    val left = region.left * size.width
    val top = region.top * size.height
    val bottom = region.bottom * size.height

    fun point(x: Float, y: Float) = Offset(
        x.coerceIn(dotRadius, maxOf(dotRadius, size.width - dotRadius)),
        y.coerceIn(dotRadius, maxOf(dotRadius, size.height - dotRadius)),
    )

    fun corner(y: Float, vertical: Float): List<Offset> {
        if (curve == 0f) return listOf(point(left, y))
        val spacing = maxOf(curve, dotRadius * 3f)
        return listOf(point(left + spacing, y), point(left, y + spacing * vertical))
    }

    return listOf(
        corner(top, 1f),
        corner(bottom, -1f),
    )
}
