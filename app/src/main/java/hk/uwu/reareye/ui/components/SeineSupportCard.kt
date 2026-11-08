package hk.uwu.reareye.ui.components

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import hk.uwu.reareye.R
import hk.uwu.reareye.version.SeineProject
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonDefaults
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.Text
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.MochiTheme

@Composable
internal fun SeineSupportCard(onOpenSponsor: () -> Unit) {
    val context = LocalContext.current
    val colors = MochiTheme.colorScheme
    val rewardBrush = remember {
        Brush.horizontalGradient(listOf(Color(0xFFF39B58), Color(0xFFE76470)))
    }
    val contactContainer = if (colors.isDark) Color(0xFF48405A) else Color(0xFFE3DDF4)
    val contactText = if (colors.isDark) Color(0xFFF2EAFB) else Color(0xFF484255)

    MochiListCard(
        modifier = Modifier
            .clip(MochiRoundedRectangle(MochiListDefaults.SectionCorner))
            .clickable(onClick = onOpenSponsor),
        showDividers = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.seine_support_illustration),
                contentDescription = null,
                modifier = Modifier.size(80.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.seine_support_title),
                    style = MochiTheme.textStyles.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    stringResource(R.string.seine_support_summary),
                    style = MochiTheme.textStyles.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val compactPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)
                    MochiButton(
                        onClick = onOpenSponsor,
                        modifier = Modifier.background(rewardBrush, MochiCapsule),
                        minHeight = 32.dp,
                        contentPadding = compactPadding,
                        colors = MochiButtonDefaults.colors().copy(
                            container = Color.Transparent,
                            content = Color.White,
                        ),
                    ) {
                        Text(stringResource(R.string.seine_support_reward), style = MochiTheme.textStyles.labelLarge)
                    }
                    MochiButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, (SeineProject.REPOSITORY + "/issues").toUri()))
                        },
                        minHeight = 32.dp,
                        contentPadding = compactPadding,
                        style = MochiButtonStyle.Tonal,
                        colors = MochiButtonDefaults.colors(MochiButtonStyle.Tonal).copy(
                            container = contactContainer,
                            content = contactText,
                        ),
                    ) {
                        Text(stringResource(R.string.seine_support_contact), style = MochiTheme.textStyles.labelLarge)
                    }
                }
            }
        }
    }
}
