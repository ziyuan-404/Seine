package hk.uwu.reareye.widgetapi

object RearWidgetApiContract {
    const val SERVICE_PERMISSION = "fr.uwu.Seine.permission.ACCESS_REAR_WIDGET_API"
    const val HOOK_HOST_PACKAGE = "com.xiaomi.subscreencenter"
    const val ACTION_REQUEST_HOOK_SERVICE = "fr.uwu.Seine.widgetapi.REQUEST_HOOK_SERVICE"

    object Extras {
        const val BUNDLE = "bundle"
        const val BINDER = "binder"
        const val FORCE_SYNC = "forceSync"
    }

    object Operation {
        const val REGISTER_FILE = "register_file"
        const val UNREGISTER_FILE = "unregister_file"
        const val REGISTER = "register"
        const val UNREGISTER = "unregister"
        const val REGISTER_SCENE_ROUTE = "register_scene_route"
        const val UNREGISTER_SCENE_ROUTE = "unregister_scene_route"
        const val DISABLE_DISPLAY = "disable_display"
        const val DISABLE_CARD_DISPLAY = "disable_card_display"
        const val POST = "post"
        const val UPDATE = "update"
        const val REMOVE = "remove"
    }

    object BundleKeys {
        const val PACKAGE_NAME = "packageName"
        const val SCENE = "scene"
        const val BUSINESS = "business"
        const val NOTIFICATION_ID = "notificationId"
        const val COMPOSITE_KEY = "compositeKey"
        const val TEMPLATE_SOURCE_PATH = "templateSourcePath"
        const val IMAGE_VALUE = "imageValue"
        const val PREVIEW_BASE64 = "previewBase64"
        const val PREVIEW_MIME_TYPE = "previewMimeType"
        const val PREVIEW_WIDTH = "previewWidth"
        const val PREVIEW_HEIGHT = "previewHeight"
        const val TEMPLATE_SCHEMA_JSON = "templateSchemaJson"
        const val ONE_CONFIG_JSON = "oneConfigJson"

        const val STICKY = "sticky"
        const val DISABLE_POPUP = "disablePopup"
        const val FORCE_POPUP = "forcePopup"
        const val ENABLE_FLOAT = "enableFloat"
        const val SHOW_TIME_TIP = "showTimeTip"
        const val INDEX = "index"
        const val PRIORITY = "priority"
    }
}
