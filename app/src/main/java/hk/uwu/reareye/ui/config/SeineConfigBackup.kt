package hk.uwu.reareye.ui.config

import hk.uwu.reareye.ui.components.config.flattenMoreSourceItems
import org.json.JSONArray
import org.json.JSONObject

internal object SeineConfigBackup {
    const val MAX_BYTES = 512_000
    private const val FORMAT = "seine-config"
    private const val VERSION = 1

    private enum class Kind { BOOLEAN, INTEGER, FLOAT, TEXT, SET }

    private val schema = buildMap {
        flattenMoreSourceItems(REAREyeConfig).forEach { item ->
            val kind = when (item.type) {
                is ConfigType.BooleanVal -> Kind.BOOLEAN
                is ConfigType.AppList -> Kind.SET
                is ConfigType.FloatSlider -> Kind.FLOAT
                is ConfigType.Manager -> null
                else -> Kind.INTEGER
            }
            if (kind != null) put(item.key, kind)
        }
        put(ConfigKeys.MODULE_NAVIGATION_QUICK_ACTIONS, Kind.TEXT)
        put(ConfigKeys.MODULE_MOCHI_COLOR_MODE, Kind.INTEGER)
        put(ConfigKeys.MODULE_STORE_API_CUSTOM_DOMAIN, Kind.TEXT)
        put(ConfigKeys.REAR_APP_REGION, Kind.TEXT)
        put(ConfigKeys.REAR_APP_ROUNDED, Kind.BOOLEAN)
        put(ConfigKeys.REAR_APP_CORNER_RADIUS, Kind.FLOAT)
        put(ConfigKeys.REAR_APP_CAMERA_FILL, Kind.BOOLEAN)
        put(ConfigKeys.REAR_APP_ORDER_DATA, Kind.TEXT)
        put(ConfigKeys.CUSTOM_BOUNDS_COMPAT_APPS, Kind.SET)
        put(ConfigKeys.CUSTOM_BOUNDS_COMPAT_CONFIG_DATA, Kind.TEXT)
        put(ConfigKeys.REAR_WIDGET_BUSINESS_DATA, Kind.TEXT)
        put(ConfigKeys.REAR_WIDGET_CARD_DATA, Kind.TEXT)
        put(ConfigKeys.REAR_WIDGET_CARD_ORDER_DATA, Kind.TEXT)
        put(ConfigKeys.REAR_WIDGET_SCENE_ROUTE_DATA, Kind.TEXT)
        put(ConfigKeys.REAR_WIDGET_BUSINESS_EXTRA_CONFIG_DATA, Kind.TEXT)
        // Turning off face authentication must always go through its consent dialog.
        remove(ConfigKeys.REAR_APP_FACE_AUTH)
        remove(ConfigKeys.REAR_NOTIFICATION_PRIVACY)
    }

    fun export(prefs: PrefsManager): String {
        check(prefs.isRemoteReady())
        val values = JSONObject()
        val snapshot = prefs.all()
        schema.forEach { (key, kind) ->
            val value = snapshot[key]
            val encoded = when (kind) {
                Kind.BOOLEAN -> value as? Boolean
                Kind.INTEGER -> value as? Int
                Kind.FLOAT -> (value as? Float)?.takeIf { it.isFinite() }
                Kind.TEXT -> value as? String
                Kind.SET -> (value as? Set<*>)?.let { JSONArray(it.filterIsInstance<String>().sorted()) }
            }
            values.put(key, encoded ?: JSONObject.NULL)
        }
        return JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("settings", values).toString(2).also {
                require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            }
    }

    fun parse(json: String): Map<String, Any?> {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT && root.optInt("version") == VERSION)
        val settings = root.getJSONObject("settings")
        return buildMap {
            for (key in settings.keys()) {
                val value = settings.get(key)
                val kind = schema[key] ?: continue
                val decoded = if (value === JSONObject.NULL) null else when (kind) {
                    Kind.BOOLEAN -> value as Boolean
                    Kind.INTEGER -> value as Int
                    Kind.FLOAT -> (value as Number).toFloat().also { require(it.isFinite()) }
                    Kind.TEXT -> value as String
                    Kind.SET -> {
                        val array = value as JSONArray
                        (0 until array.length()).mapTo(linkedSetOf()) { array.get(it) as String }
                    }
                }
                put(key, decoded)
            }
        }
    }

    fun restore(prefs: PrefsManager, values: Map<String, Any?>) {
        check(prefs.isRemoteReady())
        val editor = prefs.prefs.edit()
        values.forEach { (key, value) ->
            if (value == null) {
                editor.remove(key)
                return@forEach
            }
            when (schema.getValue(key)) {
                Kind.BOOLEAN -> editor.putBoolean(key, value as Boolean)
                Kind.INTEGER -> editor.putInt(key, value as Int)
                Kind.FLOAT -> editor.putFloat(key, value as Float)
                Kind.TEXT -> editor.putString(key, value as String)
                Kind.SET -> editor.putStringSet(key, (value as Set<*>).filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit())
    }
}
