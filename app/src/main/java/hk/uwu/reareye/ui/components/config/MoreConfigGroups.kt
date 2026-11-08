package hk.uwu.reareye.ui.components.config

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigCategory
import hk.uwu.reareye.ui.config.ConfigCategoryIcon
import hk.uwu.reareye.ui.config.ConfigGroup
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigNode

/** The only More grouping definition; all children remain the original ConfigItem objects. */
internal data class MoreConfigGroup(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val icon: ImageVector,
    val sections: List<List<String>>,
) {
    val itemKeys: List<String> = sections.flatten()
}

internal val MoreHiddenConfigKeys = setOf(
    ConfigKeys.CFG_REAR_WIDGET_CARD_MANAGER,
    ConfigKeys.CFG_REAR_WIDGET_BUSINESS_MANAGER,
    ConfigKeys.CFG_REAR_WIDGET_BUSINESS_EXTRA_MANAGER,
    ConfigKeys.CFG_REAR_WALLPAPER_MANAGER,
)

internal val MoreConfigGroups = listOf(
    MoreConfigGroup(
        id = "rear_enhancement",
        titleRes = R.string.seine_rear_enhancement_title,
        descriptionRes = R.string.seine_rear_enhancement_desc,
        icon = Icons.Rounded.AutoAwesome,
        sections = listOf(
            listOf(
                ConfigKeys.REAR_APP_ENABLED,
                ConfigKeys.ACTIVITIES_WHITELIST_APPS,
                ConfigKeys.HOOK_ACTIVITIES_WHITELIST,
                ConfigKeys.ALLOW_ALL_ACTIVITIES,
                ConfigKeys.ALLOW_EXTERNAL_DISPLAY_LAUNCH,
            ),
            listOf(
                ConfigKeys.REAR_APP_FACE_AUTH,
                ConfigKeys.REAR_APP_FACE_AUTH_STYLE,
            ),
            listOf(
                ConfigKeys.REAR_APP_HOME_STYLE,
                ConfigKeys.REAR_APP_ORDER_MODE,
            ),
        ),
    ),
    MoreConfigGroup(
        id = "rear_apps",
        titleRes = R.string.rear_app_settings_title,
        descriptionRes = R.string.rear_app_settings_desc,
        icon = Icons.Rounded.Apps,
        sections = listOf(
            listOf(ConfigKeys.CFG_REAR_APP_DISPLAY),
            listOf(
                ConfigKeys.REAR_APP_DPI,
                ConfigKeys.CFG_CUSTOM_BOUNDS_COMPAT_MANAGER,
            ),
            listOf(ConfigKeys.REAR_SCREEN_TIMEOUT_SECONDS),
        ),
    ),
    MoreConfigGroup(
        id = "apps_runtime",
        titleRes = R.string.config_more_apps_runtime_title,
        descriptionRes = R.string.config_more_apps_runtime_desc,
        icon = Icons.Rounded.Tune,
        sections = listOf(
            listOf(
                ConfigKeys.HOOK_BACKGROUND_WHITELIST,
                ConfigKeys.BACKGROUND_WHITELIST_APPS,
                ConfigKeys.BACKGROUND_LOCK_APPS,
            ),
            listOf(
                ConfigKeys.HOOK_SKIP_LOCK_BACK_HOME,
                ConfigKeys.SUBSCREEN_LOCK_BACK_HOME_WHITELIST_APPS,
            ),
            listOf(
                ConfigKeys.SUBSCREEN_DOUBLE_TAP_SLEEP_DISABLED_APPS,
                ConfigKeys.SUBSCREEN_DOUBLE_TAP_WAKE_DISABLED_APPS,
            ),
            listOf(ConfigKeys.HOOK_DISABLE_REAR_SCREEN_COVER),
            listOf(ConfigKeys.SUBSCREEN_HIGH_LOAD_MODE_DISABLED_APPS),
        ),
    ),
    MoreConfigGroup(
        id = "notifications_route",
        titleRes = R.string.config_more_notifications_route_title,
        descriptionRes = R.string.config_more_notifications_route_desc,
        icon = Icons.Rounded.NotificationsActive,
        sections = listOf(
            listOf(
                ConfigKeys.REAR_NOTIFICATION_SYNC,
                ConfigKeys.REAR_NOTIFICATION_PRIVACY,
                ConfigKeys.REAR_NOTIFICATION_FACE_AUTH_STYLE,
                ConfigKeys.REAR_NOTIFICATION_SYNC_APPS,
                ConfigKeys.REAR_NOTIFICATION_GLASS,
            ),
            listOf(
                ConfigKeys.CFG_REAR_WIDGET_SCENE_ROUTE_MANAGER,
                ConfigKeys.HOOK_ALLOW_REAR_FOCUS_NOTICES,
            ),
        ),
    ),
    MoreConfigGroup(
        id = "charging",
        titleRes = R.string.rear_charging_category,
        descriptionRes = R.string.rear_charging_category_desc,
        icon = Icons.Rounded.BatteryChargingFull,
        sections = listOf(
            listOf(
                ConfigKeys.REAR_LIQUID_CHARGING,
                ConfigKeys.REAR_CHARGING_STYLE,
                ConfigKeys.REAR_LIQUID_CHARGING_PERSISTENT,
            ),
        ),
    ),
    MoreConfigGroup(
        id = "music_lyrics",
        titleRes = R.string.config_more_music_lyrics_title,
        descriptionRes = R.string.config_more_music_lyrics_desc,
        icon = Icons.Rounded.MusicNote,
        sections = listOf(
            listOf(
                ConfigKeys.HOOK_MUSIC_CONTROLS_WHITELIST,
                ConfigKeys.MUSIC_CONTROLS_WHITELIST_APPS,
                ConfigKeys.HOOK_MUSIC_CONTROLS_FORCE_UPDATE,
            ),
            listOf(
                ConfigKeys.LYRIC_DISPLAY_MODE,
                ConfigKeys.LYRIC_SHOW_ARTIST_BEFORE_FIRST_LINE,
                ConfigKeys.LYRIC_PROVIDER,
                ConfigKeys.SUPER_LYRIC_DISPLAY_MODE,
            ),
            listOf(
                ConfigKeys.HOOK_REMOVE_NATIVE_LYRIC_SUPPORT,
                ConfigKeys.HOOK_TAKE_OVER_BUILTIN_LYRIC_HANDLING,
            ),
            listOf(ConfigKeys.HOOK_SKIP_UNCHANGED_MEDIA_TITLE_UPDATE),
        ),
    ),
    MoreConfigGroup(
        id = "video_wallpaper",
        titleRes = R.string.config_more_video_wallpaper_title,
        descriptionRes = R.string.config_more_video_wallpaper_desc,
        icon = Icons.Rounded.Movie,
        sections = listOf(
            listOf(
                ConfigKeys.HOOK_VIDEO_LOOPING,
                ConfigKeys.HOOK_VIDEO_WALLPAPER_RESUME_PROGRESS,
            ),
            listOf(
                ConfigKeys.HOOK_UNMUTE_VIDEO_WALLPAPER,
                ConfigKeys.VIDEO_WALLPAPER_VOLUME,
            ),
        ),
    ),
    MoreConfigGroup(
        id = "unlock_limits",
        titleRes = R.string.config_more_unlock_limits_title,
        descriptionRes = R.string.config_more_unlock_limits_desc,
        icon = Icons.Rounded.LockOpen,
        sections = listOf(
            listOf(
                ConfigKeys.HOOK_UNLIMITED_SUBSCREEN_APP_LIST,
                ConfigKeys.HOOK_UNLOCK_VIDEO_RESTRICTIONS,
                ConfigKeys.HOOK_UNLOCK_TEMPLATE_MAXIMUM_LIMIT,
            ),
            listOf(
                ConfigKeys.MISC_HOOK_GMS_UNLOCK,
                ConfigKeys.MISC_HOOK_SIGNATURE_PERMISSION_GRANT,
            ),
        ),
    ),
    MoreConfigGroup(
        id = "misc",
        titleRes = R.string.category_misc,
        descriptionRes = R.string.config_more_gallery_weather_desc,
        icon = Icons.Rounded.Tune,
        sections = listOf(
            listOf(ConfigKeys.MISC_HOOK_TOP_RESUMED_FIX),
            listOf(
                ConfigKeys.WEATHER_DEVICE_LEVEL,
                ConfigKeys.WEATHER_UNLOCK_SUPER_BLUR,
            ),
            listOf(
                ConfigKeys.GALLERY_BACKUP_SERVER,
                ConfigKeys.GALLERY_LONGER_TRASHBIN_TIME,
                ConfigKeys.GALLERY_TRASH_RETENTION_DAYS,
            ),
            listOf(
                ConfigKeys.GALLERY_ENABLE_HDR_ENHANCED,
                ConfigKeys.GALLERY_ENABLE_ID_PHOTO,
                ConfigKeys.GALLERY_ENABLE_PHOTO_MOVIE,
                ConfigKeys.GALLERY_ENABLE_VIDEO_POST,
                ConfigKeys.GALLERY_ENABLE_VIDEO_EDITOR,
                ConfigKeys.GALLERY_ENABLE_MAGIC_MATTING,
            ),
            listOf(
                ConfigKeys.GALLERY_ENABLE_PDF,
                ConfigKeys.GALLERY_ENABLE_OCR,
                ConfigKeys.GALLERY_ENABLE_OCR_FORM,
                ConfigKeys.GALLERY_ENABLE_PRINT,
            ),
            listOf(ConfigKeys.GALLERY_ENABLE_PRIVACY_WATERMARK),
        ),
    ),
    MoreConfigGroup(
        id = "reareye_settings",
        titleRes = R.string.config_more_reareye_settings_title,
        descriptionRes = R.string.config_more_reareye_settings_desc,
        icon = Icons.Rounded.Tune,
        sections = listOf(
            listOf(
                ConfigKeys.MODULE_THEME_MODE,
                ConfigKeys.MODULE_MOCHI_PALETTE_STYLE,
                ConfigKeys.MODULE_MOCHI_SEED_COLOR,
            ),
            listOf(
                ConfigKeys.MODULE_MOCHI_GRAPHICS_LEVEL,
                ConfigKeys.MODULE_NAVIGATION_BAR_MODE,
                ConfigKeys.MODULE_SEARCH_BAR_STYLE,
            ),
            listOf(
                ConfigKeys.MODULE_STORE_API_PROVIDER,
                ConfigKeys.MODULE_STORE_WEBVIEW_HARDWARE_ACCELERATION,
            ),
            listOf(ConfigKeys.MODULE_HIDE_LAUNCHER_ENTRY),
            listOf(ConfigKeys.MORE_DEBUG),
        ),
    ),
)

internal data class MoreCategory(val group: MoreConfigGroup, val category: ConfigCategory) {
    val sections: List<ConfigGroup> = with(category.children.associateBy(ConfigNode::key)) {
        group.sections.map { keys ->
            ConfigGroup(
                titleRes = when (keys.first()) {
                    ConfigKeys.WEATHER_DEVICE_LEVEL -> R.string.category_weather
                    ConfigKeys.GALLERY_BACKUP_SERVER -> R.string.category_gallery
                    else -> null
                },
                children = keys.map { getValue(it) },
            )
        }
    }
}
internal data class MoreSearchEntry(val item: ConfigItem, val category: MoreCategory)

internal fun flattenMoreSourceItems(nodes: List<ConfigNode>): List<ConfigItem> =
    nodes.flatMap { node ->
        when (node) {
            is ConfigItem -> listOf(node)
            is ConfigCategory -> flattenMoreSourceItems(node.children)
            is ConfigGroup -> flattenMoreSourceItems(node.children)
        }
    }

internal fun buildMoreCategories(nodes: List<ConfigNode>): List<MoreCategory> {
    val itemsByKey = flattenMoreSourceItems(nodes).associateBy(ConfigItem::key)
    return MoreConfigGroups.map { group ->
        MoreCategory(
            group = group,
            category = ConfigCategory(
                key = "dashboard_more_" + group.id,
                titleRes = group.titleRes,
                descriptionRes = group.descriptionRes,
                icon = ConfigCategoryIcon.Compose(group.icon),
                children = group.itemKeys.map(itemsByKey::getValue),
            ),
        )
    }
}

internal fun searchMoreEntries(
    entries: List<MoreSearchEntry>,
    query: String,
    resolveString: (Int) -> String,
): List<MoreSearchEntry> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return emptyList()
    return entries.filter { entry ->
        listOfNotNull(
            entry.item.titleRes,
            entry.item.descriptionRes,
            entry.category.group.titleRes,
        ).any { res -> resolveString(res).contains(normalizedQuery, ignoreCase = true) }
    }
}
