package hk.uwu.reareye.ui.components.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import hk.uwu.reareye.ui.theme.LocalSeineMiuixStyle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.basic.MochiSegmentedControl
import ui.feice.mochi.basic.MochiSegmentedDefaults
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.shape.MochiRoundedRectangle

internal object ConfigUi {
    val HorizontalInset = 16.dp
    val CardCorner = MochiListDefaults.SectionCorner
    val CardShape = MochiRoundedRectangle(CardCorner)
}

@Composable
internal fun ConfigSegmentedControl(
    items: ImmutableList<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    topPadding: Dp = 0.dp,
) {
    MochiSegmentedControl(
        items = items,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        behavior = MochiSegmentedDefaults.behavior(height = ConfigUi.CardCorner * 2, segmentPadding = 8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ConfigUi.HorizontalInset, end = ConfigUi.HorizontalInset, top = topPadding, bottom = 10.dp),
    )
}

@Composable
internal fun ConfigCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalSeineMiuixStyle.current) {
        Card(
            modifier = modifier.fillMaxWidth(),
            cornerRadius = ConfigUi.CardCorner,
            colors = CardDefaults.defaultColors(color = MochiDefaults.groupedContainer()),
        ) {
            Column(Modifier.fillMaxWidth().padding(insideMargin), content = content)
        }
    } else {
        MochiListCard(modifier = modifier, showDividers = false, animateHeight = false) {
            Column(Modifier.fillMaxWidth().padding(insideMargin), content = content)
        }
    }
}
internal val LocalConfigOwnsBack = staticCompositionLocalOf { false }
