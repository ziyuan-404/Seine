package hk.uwu.reareye.ui.screen

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.chrisbanes.haze.HazeState
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.SeineWordmark
import hk.uwu.reareye.repository.contributor.ContributorLoadState
import hk.uwu.reareye.repository.contributor.ContributorProfile
import hk.uwu.reareye.repository.contributor.ContributorRepository
import hk.uwu.reareye.ui.components.card.SuperCard
import hk.uwu.reareye.ui.components.motion.ArtRevealItem
import hk.uwu.reareye.ui.components.motion.ArtStaggeredReveal
import hk.uwu.reareye.ui.theme.rearAcrylicEffect
import hk.uwu.reareye.ui.theme.rearAcrylicSource
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeState
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeStyle
import hk.uwu.reareye.utils.effect.BgEffectBackground
import hk.uwu.reareye.utils.other.DeviceConfigTools
import hk.uwu.reareye.utils.other.LibraryItem
import hk.uwu.reareye.utils.other.OSVersionTools
import hk.uwu.reareye.utils.other.loadLibraries
import hk.uwu.reareye.utils.pageContentPadding
import ui.feice.mochi.navigation.LocalMochiBottomBarInset
import ui.feice.mochi.navigation.MochiTabScaffoldDefaults
import hk.uwu.reareye.version.SeineProject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection

private val AboutPageHorizontalPadding = 12.dp
private val AboutCardSpacing = 8.dp
private val AboutGradientFadeDistance = 389.dp

private val contributorAvatarHttpClient = OkHttpClient()

private object ContributorAvatarCache {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun peek(url: String?): ImageBitmap? {
        val key = url?.takeIf { it.isNotBlank() } ?: return null
        return cache[key]
    }

    suspend fun preload(urls: List<String>) {
        urls.distinct().forEach { url ->
            load(url)
        }
    }

    suspend fun load(url: String?): ImageBitmap? {
        val key = url?.takeIf { it.isNotBlank() } ?: return null
        cache[key]?.let { return it }

        val image = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(key)
                    .build()
                contributorAvatarHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    BitmapFactory.decodeStream(response.body.byteStream())?.asImageBitmap()
                }
            }.getOrNull()
        }

        if (image != null) {
            cache.putIfAbsent(key, image)
        }
        return cache[key] ?: image
    }
}

private sealed interface AboutRoute {
    data object Root : AboutRoute
    data object Contributors : AboutRoute
    data object Licenses : AboutRoute
}

private data class AboutAnimatedRoute(
    val route: AboutRoute,
    val depth: Int,
)

private data class CreditEntry(
    val titleRes: Int,
    val summaryRes: Int,
    val url: String,
)

private data class SourceReference(
    val title: String,
    @param:StringRes val descriptionRes: Int,
    val url: String? = null,
)

private val CodeReferences = listOf(
    SourceReference("REAREye", R.string.seine_reference_reareye, SeineProject.UPSTREAM),
    SourceReference("Mochi Compose", R.string.seine_reference_mochi, "https://github.com/feice11/mochi-compose"),
    SourceReference(
        "MiRearScreenSwitcher",
        R.string.seine_reference_mrss,
        "https://github.com/AntiOblivionis/MiRearScreenSwitcher",
    ),
)

private val DesignReferences = listOf(
    SourceReference("Folia", R.string.seine_reference_folia),
    SourceReference("STRAY洋葱", R.string.seine_reference_stray),
)

@Composable
private fun rememberSkeletonPulseAlpha(label: String): Float {
    val infiniteTransition = rememberInfiniteTransition(label = label)
    val alpha = infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "$label-alpha",
    )
    return alpha.value
}

@Composable
fun AboutScreen(
    bottomInnerPadding: Dp = 0.dp,
) {
    val versionText = SeineProject.CURRENT_RELEASE.removePrefix("v")
    val contributorState by ContributorRepository.state.collectAsState()
    val lazyListState = rememberLazyListState()

    var route by remember { mutableStateOf<AboutRoute>(AboutRoute.Root) }
    val animatedRoute = remember(route) {
        AboutAnimatedRoute(
            route = route,
            depth = if (route is AboutRoute.Root) 0 else 1,
        )
    }

    val entries = remember {
        listOf(
            CreditEntry(
                titleRes = R.string.credits_github_title,
                summaryRes = R.string.credits_github_desc,
                url = SeineProject.REPOSITORY,
            ),
            CreditEntry(
                titleRes = R.string.seine_upstream_title,
                summaryRes = R.string.seine_upstream_summary,
                url = SeineProject.UPSTREAM,
            ),
            CreditEntry(
                titleRes = R.string.credits_afdian_title,
                summaryRes = R.string.seine_upstream_afdian_summary,
                url = "https://ifdian.net/a/rgbmc",
            ),
            CreditEntry(
                titleRes = R.string.credits_docs,
                summaryRes = R.string.credits_docs_desc,
                url = "https://reareye.uwu.hk"
            ),
        )
    }

    LaunchedEffect(Unit) {
        ContributorRepository.preload()
    }

    LaunchedEffect(route) {
        if (route is AboutRoute.Contributors) {
            ContributorRepository.ensureLoaded(force = false)
        }
    }

    LaunchedEffect(contributorState) {
        val loadedState = contributorState as? ContributorLoadState.Loaded ?: return@LaunchedEffect
        ContributorAvatarCache.preload(
            loadedState.contributors.mapNotNull { it.avatar?.takeIf(String::isNotBlank) }
        )
    }

    BackHandler(enabled = route is AboutRoute.Contributors || route is AboutRoute.Licenses) {
        route = AboutRoute.Root
    }

    AnimatedContent(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.surface)
            .graphicsLayer { clip = true },
        targetState = animatedRoute,
        contentKey = { it.route },
        transitionSpec = {
            val forward = targetState.depth >= initialState.depth

            fadeIn(
                animationSpec = tween(
                    durationMillis = 210,
                    delayMillis = 50,
                    easing = LinearOutSlowInEasing,
                )
            ) + slideInHorizontally(
                animationSpec = tween(
                    durationMillis = 280,
                    easing = FastOutSlowInEasing,
                )
            ) { fullWidth ->
                if (forward) fullWidth / 9 else -fullWidth / 9
            } togetherWith (
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = 110,
                            easing = FastOutLinearInEasing,
                        )
                    ) + slideOutHorizontally(
                        animationSpec = tween(
                            durationMillis = 190,
                            easing = FastOutLinearInEasing,
                        )
                    ) { fullWidth ->
                        if (forward) -fullWidth / 12 else fullWidth / 12
                    }
                    )
        },
        label = "AboutRouteTransition",
    ) { target ->
        when (target.route) {
            AboutRoute.Root -> CompositionLocalProvider(LocalMochiBottomBarInset provides bottomInnerPadding) {
                AboutRootPage(
                    versionText = versionText,
                    entries = entries,
                    lazyListState = lazyListState,
                    onOpenContributors = {
                        route = AboutRoute.Contributors
                    },
                    onOpenLibraries = {
                        route = AboutRoute.Licenses
                    },
                )
            }

            AboutRoute.Contributors -> AboutSecondaryPage(
                title = stringResource(R.string.credits_contributors_title),
                onBack = { route = AboutRoute.Root },
            ) { paddingValues, scrollBehavior, hazeState ->
                ContributorListContent(
                    bottomInnerPadding = bottomInnerPadding,
                    paddingValues = paddingValues,
                    scrollBehavior = scrollBehavior,
                    hazeState = hazeState,
                    state = contributorState,
                )
            }

            AboutRoute.Licenses -> AboutSecondaryPage(
                title = stringResource(R.string.licenses_name),
                onBack = { route = AboutRoute.Root },
            ) { paddingValues, scrollBehavior, hazeState ->
                LicenseContent(
                    bottomInnerPadding = bottomInnerPadding,
                    paddingValues = paddingValues,
                    scrollBehavior = scrollBehavior,
                    hazeState = hazeState,
                )
            }
        }
    }
}

@Composable
private fun AboutRootPage(
    versionText: String,
    entries: List<CreditEntry>,
    lazyListState: LazyListState,
    onOpenContributors: () -> Unit,
    onOpenLibraries: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val gradientFadeDistancePx = with(LocalDensity.current) {
        AboutGradientFadeDistance.toPx().coerceAtLeast(1f)
    }
    val scrollProgress by remember(lazyListState, gradientFadeDistancePx) {
        derivedStateOf {
            val index = lazyListState.firstVisibleItemIndex
            val offset = lazyListState.firstVisibleItemScrollOffset

            if (index > 0) {
                1f
            } else {
                (offset.toFloat() / gradientFadeDistancePx).coerceIn(0f, 1f)
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.about_navigation),
                scrollBehavior = scrollBehavior,
                color = colorScheme.surface.copy(alpha = if (scrollProgress == 1f) 1f else 0f),
                titleColor = colorScheme.onSurface.copy(alpha = scrollProgress),
                defaultWindowInsetsPadding = false,
                navigationIconPadding = 12.dp,
            )
        }
    ) { paddingValues ->
        AboutRootContent(
            paddingValues = paddingValues,
            scrollBehavior = scrollBehavior,
            hazeState = hazeState,
            versionText = versionText,
            entries = entries,
            onOpenContributors = onOpenContributors,
            scrollProgress = scrollProgress,
            lazyListState = lazyListState,
            onOpenLibraries = onOpenLibraries,
        )
    }
}

@Composable
private fun AboutRootContent(
    paddingValues: PaddingValues,
    scrollBehavior: ScrollBehavior,
    hazeState: HazeState,
    versionText: String,
    entries: List<CreditEntry>,
    onOpenContributors: () -> Unit,
    scrollProgress: Float,
    lazyListState: LazyListState,
    onOpenLibraries: () -> Unit,
) {
    val context = LocalContext.current
    val surface = colorScheme.surface
    val darkTheme = surface.luminance() < 0.5f
    val runtimeShaderSupported = remember { isRuntimeShaderSupported() }
    val scrollPadding = pageContentPadding(
        paddingValues,
        paddingValues,
        false,
        extraStart = WindowInsets.displayCutout.asPaddingValues()
            .calculateLeftPadding(LayoutDirection.Ltr),
        extraEnd = WindowInsets.displayCutout.asPaddingValues()
            .calculateRightPadding(LayoutDirection.Ltr),
    )

    BgEffectBackground(
        dynamicBackground = runtimeShaderSupported,
        modifier = Modifier.fillMaxSize(),
        backgroundColor = surface,
        isDarkTheme = darkTheme,
        effectBackground = runtimeShaderSupported,
        alpha = { 1f - scrollProgress },
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .rearAcrylicSource(hazeState)
                .padding(horizontal = AboutPageHorizontalPadding),
            contentPadding = PaddingValues(
                top = scrollPadding.calculateTopPadding(),
                bottom = MochiTabScaffoldDefaults.contentBottomPadding(),
            ),
        ) {
            item(key = "project_hero") {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 340.dp).padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    SeineWordmark(
                        width = 222.dp,
                        color = if (darkTheme) colorScheme.onBackground else Color(0xFF682753),
                    )
                    Text(
                        text = versionText,
                        color = colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item(key = "content") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AboutCardSpacing),
                ) {
                    AboutDeviceInfoCard()

                    MochiListSection {
                        entries.forEach { entry ->
                            MochiListRow(
                                title = stringResource(entry.titleRes),
                                subtitle = stringResource(entry.summaryRes),
                                leading = { MochiIonicon("link") },
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, entry.url.toUri())) },
                                chevron = true,
                            )
                        }
                        MochiListRow(
                            stringResource(R.string.credits_contributors_title),
                            leading = { MochiIonicon("people") },
                            onClick = onOpenContributors,
                            chevron = true,
                        )
                        MochiListRow(
                            stringResource(R.string.licenses_name),
                            leading = { MochiIonicon("document-text") },
                            onClick = onOpenLibraries,
                            chevron = true,
                        )
                    }
                }
            }

        }
    }
}

@Composable
private fun AboutDeviceInfoCard() {
    val context = LocalContext.current
    val osVersion = remember(context) { OSVersionTools.addVersionSuffix(context) }
    MochiListSection {
        AboutDeviceInfoRow(stringResource(R.string.device_name), DeviceConfigTools.deviceName)
        AboutDeviceInfoRow(stringResource(R.string.about_device_model), DeviceConfigTools.marketName)
        AboutDeviceInfoRow(stringResource(R.string.os_version), osVersion)
        AboutDeviceInfoRow(stringResource(R.string.android_version), DeviceConfigTools.androidVersion)
    }
}

@Composable
private fun AboutDeviceInfoRow(title: String, value: String) {
    MochiListRow(
        title = title,
        trailing = {
            Text(
                text = value,
                modifier = Modifier.widthIn(max = 220.dp),
                color = colorScheme.onSurfaceVariantSummary,
                fontSize = 15.sp,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun AboutSecondaryPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues, ScrollBehavior, HazeState) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.rearAcrylicEffect(hazeState, hazeStyle),
                color = Color.Transparent,
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            modifier = Modifier.graphicsLayer {
                                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                            },
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                navigationIconPadding = 12.dp,
                scrollBehavior = scrollBehavior,
            )
        }
    ) { paddingValues ->
        content(
            paddingValues,
            scrollBehavior,
            hazeState,
        )
    }
}

@Composable
private fun ContributorListContent(
    bottomInnerPadding: Dp,
    paddingValues: PaddingValues,
    scrollBehavior: ScrollBehavior,
    hazeState: HazeState,
    state: ContributorLoadState,
) {
    val avatarPlaceholderAlpha = rememberSkeletonPulseAlpha("contributor-avatar-skeleton")
    val maintainerDescription = stringResource(R.string.seine_contributor_maintainer)
    val maintainer = remember(maintainerDescription) {
        ContributorProfile(
            name = "ziyuan-404",
            description = maintainerDescription,
            link = "https://github.com/ziyuan-404",
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .rearAcrylicSource(hazeState)
            .padding(horizontal = AboutPageHorizontalPadding),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding(),
            bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(AboutCardSpacing),
        overscrollEffect = null,
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
        }

        item(key = "seine-maintainer") {
            ContributorCard(
                item = maintainer,
                avatarPlaceholderAlpha = avatarPlaceholderAlpha,
                avatarRes = R.drawable.seine_maintainer_avatar,
            )
        }

        when (state) {
            ContributorLoadState.Idle,
            ContributorLoadState.Loading,
                -> item {
                ArtRevealItem(visible = true, delayMillis = 40) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InfiniteProgressIndicator()
                            Text(text = stringResource(R.string.credits_contributors_loading))
                        }
                    }
                }
            }

            ContributorLoadState.Failed -> item {
                ArtRevealItem(visible = true, delayMillis = 40) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SuperCard(
                            title = stringResource(R.string.credits_contributors_title),
                            summary = stringResource(R.string.credits_contributors_load_failed),
                            bottomAction = {
                                Button(
                                    onClick = { ContributorRepository.ensureLoaded(force = true) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(text = stringResource(R.string.credits_contributors_retry))
                                }
                            },
                        )
                    }
                }
            }

            is ContributorLoadState.Loaded -> {
                val upstreamContributors = state.contributors.filterNot {
                    it.name.equals(maintainer.name, ignoreCase = true) ||
                        it.link?.trimEnd('/')?.equals(maintainer.link, ignoreCase = true) == true
                }
                itemsIndexed(
                    upstreamContributors,
                    key = { _, item -> item.link?.takeIf { it.isNotBlank() } ?: item.name },
                ) { index, item ->
                    val revealKey = item.link?.takeIf { it.isNotBlank() } ?: item.name
                    ArtStaggeredReveal(
                        visible = true,
                        revealKey = revealKey,
                        delayMillis = (36 + index * 18).coerceAtMost(150),
                    ) {
                        ContributorCard(
                            item = item,
                            avatarPlaceholderAlpha = avatarPlaceholderAlpha,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseContent(
    bottomInnerPadding: Dp,
    paddingValues: PaddingValues,
    scrollBehavior: ScrollBehavior,
    hazeState: HazeState,
) {
    val context = LocalContext.current
    val data = remember {
        loadLibraries(context)
    }


    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .rearAcrylicSource(hazeState)
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding(),
            bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        overscrollEffect = null,
    ) {
        item(key = "code_references") {
            SourceReferenceSection(stringResource(R.string.seine_reference_code_title), CodeReferences)
        }
        item(key = "design_references") {
            SourceReferenceSection(stringResource(R.string.seine_reference_design_title), DesignReferences)
        }
        items(data.libraries) {
            LibraryItem(it, data.licenses)
        }
    }

}

@Composable
private fun SourceReferenceSection(title: String, references: List<SourceReference>) {
    val context = LocalContext.current
    MochiListSection(header = title) {
        references.forEach { reference ->
            MochiListRow(
                title = reference.title,
                subtitle = stringResource(reference.descriptionRes),
                chevron = reference.url != null,
                onClick = reference.url?.let { url ->
                    { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                },
            )
        }
    }
}

@Composable
private fun ContributorCard(
    item: ContributorProfile,
    avatarPlaceholderAlpha: Float,
    @DrawableRes avatarRes: Int? = null,
) {
    val context = LocalContext.current
    val link = item.link?.takeIf { it.isNotBlank() }
    val hasLink = link != null

    Card(modifier = Modifier.fillMaxWidth()) {
        SuperCard(
            title = item.name,
            summary = item.description.takeIf { it.isNotBlank() },
            startAction = {
                ContributorAvatar(
                    avatarUrl = item.avatar,
                    placeholderAlpha = avatarPlaceholderAlpha,
                    avatarRes = avatarRes,
                )
            },
            onClick = link?.let { targetLink ->
                {
                    context.startActivity(Intent(Intent.ACTION_VIEW, targetLink.toUri()))
                }
            },
            endActions = {
                if (hasLink) {
                    Icon(
                        imageVector = MiuixIcons.Link,
                        tint = colorScheme.onSurface,
                        contentDescription = null,
                    )
                }
            },
        )
    }
}

@Composable
private fun ContributorAvatar(
    avatarUrl: String?,
    placeholderAlpha: Float,
    modifier: Modifier = Modifier,
    @DrawableRes avatarRes: Int? = null,
) {
    var imageBitmap by remember(avatarUrl) {
        mutableStateOf(ContributorAvatarCache.peek(avatarUrl))
    }

    LaunchedEffect(avatarUrl) {
        if (avatarUrl.isNullOrBlank()) {
            return@LaunchedEffect
        }

        imageBitmap = ContributorAvatarCache.load(avatarUrl)
    }

    if (avatarRes != null) {
        Image(
            painter = painterResource(avatarRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(42.dp).clip(CircleShape),
        )
    } else if (avatarUrl.isNullOrBlank()) {
        Box(
            modifier = modifier.size(42.dp).clip(CircleShape).background(colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            MochiIonicon("person", tint = colorScheme.onSecondaryContainer)
        }
    } else if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap!!,
            contentDescription = null,
            modifier = modifier
                .size(42.dp)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(colorScheme.secondaryContainer.copy(alpha = placeholderAlpha)),
        )
    }
}
