package hk.uwu.reareye.ui.screen

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hk.uwu.reareye.R
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonDefaults
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiSegmentedControl
import ui.feice.mochi.basic.MochiSegmentedDefaults
import ui.feice.mochi.basic.Text
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.MochiTheme

private enum class SponsorPayment(
    val titleRes: Int,
    val scanRes: Int,
    val drawableRes: Int,
    val mime: String,
    val extension: String,
    val light: Color,
    val dark: Color,
    val accent: Color,
) {
    WECHAT(R.string.seine_support_wechat, R.string.seine_support_scan_wechat, R.drawable.seine_support_wechat,
        "image/png", "png", Color(0xFF5DB777), Color(0xFF205738), Color(0xFF388747)),
    ALIPAY(R.string.seine_support_alipay, R.string.seine_support_scan_alipay, R.drawable.seine_support_alipay,
        "image/jpeg", "jpg", Color(0xFF559DFF), Color(0xFF173A7C), Color(0xFF326FBD));

    fun scanIntent(): Intent = when (this) {
        WECHAT -> Intent(Intent.ACTION_MAIN)
            .setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
            .putExtra("LauncherUI.From.Scaner.Shortcut", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ALIPAY -> Intent(Intent.ACTION_VIEW, "alipays://platformapi/startapp?saId=10000007".toUri())
            .setPackage("com.eg.android.AlipayGphone")
    }
}

@Composable
internal fun SeineSponsorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var payment by rememberSaveable { mutableStateOf(SponsorPayment.WECHAT) }
    var saving by remember { mutableStateOf(false) }
    val rotation = animateFloatAsState(
        targetValue = if (payment == SponsorPayment.WECHAT) 0f else 180f,
        animationSpec = tween(380),
        label = "Sponsor card flip",
    )
    val tilt = rememberSponsorTilt()
    val face by remember(rotation) {
        derivedStateOf { if (rotation.value < 90f) SponsorPayment.WECHAT else SponsorPayment.ALIPAY }
    }

    fun save(method: SponsorPayment, openScanner: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { saveSponsorCode(context, method) }
                val message = if (!saved) {
                    R.string.seine_support_save_failed
                } else if (openScanner && runCatching { context.startActivity(method.scanIntent()) }.isFailure) {
                    R.string.seine_support_scan_unavailable
                } else {
                    R.string.seine_support_saved
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
            }
        }
    }

    MochiLazyLargeTitleScaffold(
        title = stringResource(R.string.seine_support_reward_page),
        navigationIcon = { MochiBackButton(onClick = onBack) },
        contentSpacing = 16.dp,
    ) {
        item(key = "payment") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MochiSegmentedControl(
                    items = persistentListOf(
                        stringResource(R.string.seine_support_wechat_tab),
                        stringResource(R.string.seine_support_alipay),
                    ),
                    selectedIndex = payment.ordinal,
                    onSelect = { payment = SponsorPayment.entries[it] },
                    modifier = Modifier.widthIn(max = 304.dp).fillMaxWidth(),
                    behavior = MochiSegmentedDefaults.behavior(height = 42.dp),
                )
            }
        }
        item(key = "qr_card") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SponsorPayCard(
                    payment = face,
                    modifier = Modifier
                        .widthIn(max = 304.dp)
                        .fillMaxWidth()
                        .aspectRatio(.69f)
                        .graphicsLayer {
                            cameraDistance = size.width * 2.2f
                            rotationX = tilt.value.y
                            rotationY = rotation.value - (if (face == SponsorPayment.ALIPAY) 180f else 0f) + tilt.value.x
                        },
                    onFlip = {
                        payment = if (payment == SponsorPayment.WECHAT) SponsorPayment.ALIPAY else SponsorPayment.WECHAT
                    },
                    onSave = { save(face, openScanner = false) },
                )
            }
        }
        item(key = "save") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.widthIn(max = 304.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MochiButton(
                        onClick = { save(payment, openScanner = true) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        colors = MochiButtonDefaults.colors().copy(container = payment.accent, content = Color.White),
                    ) { Text(stringResource(payment.scanRes)) }
                    MochiButton(
                        onClick = { save(payment, openScanner = false) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        style = MochiButtonStyle.Tonal,
                    ) { Text(stringResource(R.string.seine_support_save)) }
                }
            }
        }
    }
}

@Composable
private fun SponsorPayCard(
    payment: SponsorPayment,
    modifier: Modifier,
    onFlip: () -> Unit,
    onSave: () -> Unit,
) {
    val shape = remember { MochiRoundedRectangle(28.dp) }
    val gradient = remember(payment) { Brush.verticalGradient(listOf(payment.light, payment.dark)) }
    val label = stringResource(payment.titleRes)
    Column(
        modifier = modifier
            .clip(shape)
            .background(gradient)
            .border(3.dp, Color.Black.copy(alpha = .14f), shape)
            .drawWithCache {
                val texture = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = .16f), Color.Transparent),
                    center = Offset(size.width * .15f, size.height * .15f),
                    radius = size.width,
                )
                onDrawBehind { drawRect(texture) }
            }
            .combinedClickable(
                onClickLabel = stringResource(R.string.seine_support_flip),
                onLongClickLabel = stringResource(R.string.seine_support_save),
                onClick = onFlip,
                onLongClick = onSave,
            )
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Text(label, color = Color.White, style = MochiTheme.textStyles.titleMedium)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(payment.drawableRes),
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MochiRoundedRectangle(18.dp))
                    .background(Color.White)
                    .padding(9.dp),
            )
            Text("Seine", modifier = Modifier.padding(top = 12.dp), color = Color.White, style = MochiTheme.textStyles.titleMedium)
            Text(
                stringResource(R.string.seine_support_flip),
                color = Color.White.copy(alpha = .84f),
                style = MochiTheme.textStyles.bodySmall,
            )
        }
    }
}

@Composable
private fun rememberSponsorTilt(): State<Offset> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(context, lifecycle) {
        val manager = context.getSystemService(SensorManager::class.java)
        val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (manager == null || gravity == null) return@DisposableEffect onDispose {}
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val aim = Offset(
                    (-event.values[0] * 1.4f).coerceIn(-8f, 8f),
                    (event.values[1] * .6f).coerceIn(-5f, 5f),
                )
                tilt.value = tilt.value * .8f + aim * .2f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> manager.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_UI)
                Lifecycle.Event.ON_PAUSE -> manager.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            manager.unregisterListener(listener)
        }
    }
    return tilt
}

private fun saveSponsorCode(context: Context, payment: SponsorPayment): Boolean {
    val resolver = context.contentResolver
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val entry = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Seine_" + payment.name + "_" + System.currentTimeMillis() + "." + payment.extension)
        put(MediaStore.Images.Media.MIME_TYPE, payment.mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Seine")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = runCatching { resolver.insert(collection, entry) }.getOrNull() ?: return false
    return try {
        context.resources.openRawResource(payment.drawableRes).use { input ->
            checkNotNull(resolver.openOutputStream(uri)).use { output -> input.copyTo(output) }
        }
        check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null) == 1)
        true
    } catch (_: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}
