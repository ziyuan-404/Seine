package hk.uwu.reareye.widgetapi

object RearWallpaperApiContract {
    const val SERVICE_PERMISSION = RearWidgetApiContract.SERVICE_PERMISSION
    const val HOOK_HOST_PACKAGE = RearWidgetApiContract.HOOK_HOST_PACKAGE
    const val ACTION_REQUEST_HOOK_SERVICE = "fr.uwu.Seine.wallpaperapi.REQUEST_HOOK_SERVICE"

    object Extras {
        const val BUNDLE = "bundle"
        const val BINDER = "binder"
        const val FORCE_SYNC = "forceSync"
    }

    object BundleKeys {
        const val ITEMS = "items"
        const val CURRENT_INDEX = "currentIndex"
        const val CURRENT_WALLPAPER_ID = "currentWallpaperId"
        const val WALLPAPER_ID = "wallpaperId"
        const val TITLE = "title"
        const val NAME = "name"
        const val DESCRIPTION = "description"
        const val AUTHOR = "author"
        const val DESIGNER = "designer"
        const val RES_SUB_TYPE = "resSubType"
        const val IMPORTED = "imported"
        const val CAN_EDIT_METADATA = "canEditMetadata"
        const val CAN_DELETE = "canDelete"
        const val EDITABLE = "editable"
        const val THIRD_PARTIES = "thirdParties"
        const val SUPPORT_AON = "supportAon"
        const val PREVIEW_AVAILABLE = "previewAvailable"
        const val PREVIEW_SIGNATURE = "previewSignature"
        const val SUCCESS = "success"
        const val ERROR = "error"
        const val META_TITLE_FALLBACK = "metaTitleFallback"
        const val META_TITLE_ZH_CN = "metaTitleZhCn"
        const val META_DESCRIPTION_FALLBACK = "metaDescriptionFallback"
        const val META_DESCRIPTION_ZH_CN = "metaDescriptionZhCn"
        const val META_AUTHOR = "metaAuthor"
        const val META_DESIGNER = "metaDesigner"
        const val META_CATEGORY = "metaCategory"
        const val META_RES_SUB_TYPE = "metaResSubType"
        const val META_EDITABLE = "metaEditable"
        const val META_THIRD_PARTIES = "metaThirdParties"
        const val META_SUPPORT_AON = "metaSupportAon"
        const val TEMPLATE_CONFIG_AVAILABLE = "templateConfigAvailable"
        const val TEMPLATE_CONFIG_CUSTOMIZED = "templateConfigCustomized"
    }
}
