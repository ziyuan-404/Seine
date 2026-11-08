package hk.uwu.reareye.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import hk.uwu.reareye.R

@Composable
internal fun SeineWordmark(width: Dp, color: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.seine_wordmark),
        contentDescription = stringResource(R.string.app_name),
        modifier = modifier.width(width).aspectRatio(1054f / 250f),
        colorFilter = ColorFilter.tint(color),
    )
}
