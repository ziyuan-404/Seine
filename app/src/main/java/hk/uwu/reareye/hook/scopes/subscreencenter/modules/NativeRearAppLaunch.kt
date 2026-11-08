package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.app.ActivityOptions
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.Pair
import android.view.View
import android.widget.RemoteViews
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearAppDefaults
import org.json.JSONArray

/** Shared with notification cards: retain the framework's native launch animation and grants. */
internal object NativeRearAppLaunch {
    private val notificationOrigins=java.util.WeakHashMap<View,java.lang.ref.WeakReference<View>>()
    private val pickerOrigins=mutableMapOf<String,java.lang.ref.WeakReference<View>>()
    private var targetPackageName: String? = null
    fun registerPickerOrigin(packageName: String, view: View) { pickerOrigins[packageName] = java.lang.ref.WeakReference(view) }
    fun registerNotificationOrigin(root:View,icon:View) {
        notificationOrigins[root]=java.lang.ref.WeakReference(icon)
    }
    var onLaunched:(()->Unit)?=null
    var onRestoreOrigin:((Int)->Unit)?=null
    private var returnKind=RearLaunchOrigin.PICKER
    private var awaitingReturn=false
    var launchOrigin: RearLaunchOrigin? = null
        private set
    fun restoreOriginIfNeeded(toPicker: Boolean = false) {
        if(!awaitingReturn && !toPicker)return
        awaitingReturn=false
        onRestoreOrigin?.invoke(if (toPicker) RearLaunchOrigin.PICKER else returnKind)
        if (toPicker) pickerOrigins[targetPackageName]?.get()?.takeIf { it.isAttachedToWindow }?.let { target ->
            launchOrigin = launchOrigin?.retarget(target, RearLaunchOrigin.PICKER)
        }
    }
    fun canOpenNotification(sbn:android.service.notification.StatusBarNotification,prefs:HookPrefs,context:android.content.Context):Boolean {
        if(!prefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, RearAppDefaults.ENABLED)||sbn.packageName !in prefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS, RearAppDefaults.ALLOWED_APPS))return false
        return sbn.notification.contentIntent?.isActivity==true||
            sbn.user==android.os.Process.myUserHandle()&&context.packageManager.getLaunchIntentForPackage(sbn.packageName)!=null
    }
    fun openNotification(view:View,sbn:android.service.notification.StatusBarNotification,prefs:HookPrefs,onOpened:(()->Unit)?=null) {
        check(canOpenNotification(sbn,prefs,view.context)) { "Notification app is not enabled for rear launch" }
        val pending=sbn.notification.contentIntent
        if(pending?.isActivity==true)open(view,pending,prefs,sbn.packageName,RearLaunchOrigin.NOTIFICATION,onOpened)
        else open(view,requireNotNull(view.context.packageManager.getLaunchIntentForPackage(sbn.packageName)),prefs,RearLaunchOrigin.NOTIFICATION,onOpened)
    }
    fun open(view: View,intent: Intent,prefs: HookPrefs,kind:Int=RearLaunchOrigin.PICKER,onOpened:(()->Unit)?=null) {
        val pending=PendingIntent.getActivity(view.context,intent.component.hashCode(),intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        open(view,pending,prefs,intent.component?.packageName,kind,onOpened)
    }
    fun open(view: View,pending: PendingIntent,prefs: HookPrefs,targetPackage:String?=pending.creatorPackage,kind:Int=RearLaunchOrigin.PICKER,onOpened:(()->Unit)?=null) {
        RearAppFaceAuth.authorize(view,prefs) {
            runCatching { openAuthenticated(view,pending,prefs,targetPackage,kind,onOpened) }
                .onFailure { hk.uwu.reareye.hook.support.YLog.warn("Authenticated rear launch failed",it) }
        }
    }
    private fun openAuthenticated(view: View,pending: PendingIntent,prefs: HookPrefs,targetPackage:String?,kind:Int,onOpened:(()->Unit)?) {
        val responseClass=Class.forName("android.widget.RemoteViews\$RemoteResponse")
        val response=responseClass.getDeclaredMethod("fromPendingIntent",PendingIntent::class.java)
            .apply { isAccessible=true }.invoke(null,pending)
        @Suppress("UNCHECKED_CAST")
        val options=responseClass.getDeclaredMethod("getLaunchOptions",View::class.java)
            .apply { isAccessible=true }.invoke(response,view) as Pair<Intent,ActivityOptions>
        options.second.setLaunchDisplayId(1)
        var origin:RearLaunchOrigin?=null
        var existingTaskId = -1
        if(prefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, RearAppDefaults.ENABLED)) {
            launchOrigin?.sourceAlpha(1f)
            val source=if(kind==RearLaunchOrigin.NOTIFICATION)
                notificationOrigins[view]?.get()?.takeIf { it.isAttachedToWindow }?:view
            else view
            origin=RearLaunchOrigin.capture(source,kind,onOpened)
            origin?.let {
                ActivityOptions::class.java.getDeclaredMethod("setLaunchCookie",android.os.IBinder::class.java).invoke(options.second,it.cookie())
            }
            val display=view.context.getSystemService(DisplayManager::class.java).getDisplay(1)
            val size=android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            val cutout=display.cutout
            val bounds=Rect(cutout?.safeInsetLeft?:0,cutout?.safeInsetTop?:0,
                size.x-(cutout?.safeInsetRight?:0),size.y-(cutout?.safeInsetBottom?:0))
            val raw=prefs.getString(ConfigKeys.REAR_APP_REGION,RearAppDefaults.REGION)
            if(raw.isNotBlank())runCatching {
                val region=JSONArray(raw)
                val custom=Rect((size.x*region.getDouble(0)).toInt(),(size.y*region.getDouble(1)).toInt(),
                    (size.x*region.getDouble(2)).toInt(),(size.y*region.getDouble(3)).toInt())
                if(!custom.isEmpty&&Rect(0,0,size.x,size.y).contains(custom))bounds.set(custom)
            }
            val launch=options.second
            val rearMetrics=android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            view.context.getSystemService(DisplayManager::class.java).getDisplay(1).getRealMetrics(rearMetrics)
            val primaryMetrics=android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            view.context.getSystemService(DisplayManager::class.java).getDisplay(0).getRealMetrics(primaryMetrics)
            val rearBaseDpi=rearMetrics.densityDpi.takeIf { it>0 }?:480
            val primaryBaseDpi=primaryMetrics.densityDpi.takeIf { it>0 }?:rearBaseDpi
            val scale=RearAppDefaults.contentScale(prefs.getInt(ConfigKeys.REAR_APP_DPI,RearAppDefaults.APP_DPI),primaryBaseDpi)
            val logical=Rect(bounds.left,bounds.top,bounds.left+kotlin.math.round(bounds.width()/scale).toInt(),bounds.top+kotlin.math.round(bounds.height()/scale).toInt())
            ActivityOptions::class.java.getDeclaredMethod("setLaunchWindowingMode",Int::class.javaPrimitiveType)
                .invoke(launch,5)
            launch.setLaunchBounds(logical)
            val injector=ActivityOptions::class.java.getDeclaredMethod("getActivityOptionsInjector").invoke(launch)
            injector.javaClass.getMethod("setFreeformScale",Float::class.javaPrimitiveType).invoke(injector,scale)
            injector.javaClass.getMethod("setNormalFreeForm",Boolean::class.javaPrimitiveType).invoke(injector,false)
            // Reuse the app's existing task, including its back stack and process, across displays.
            @Suppress("DEPRECATION")
            val existing=view.context.getSystemService(ActivityManager::class.java).getRunningTasks(100)
                .firstOrNull { task -> task.baseActivity?.packageName==targetPackage&&
                    runCatching { task.javaClass.getField("userId").getInt(task)==pending.creatorUid/100000 }.getOrDefault(false) }
            existing?.takeIf { kind == RearLaunchOrigin.PICKER }?.let {
                existingTaskId = it.taskId
                ActivityOptions::class.java.getDeclaredMethod("setLaunchTaskId",Int::class.javaPrimitiveType).invoke(launch,it.taskId)
            }
        }
        val sendLaunch = {
            check(RemoteViews::class.java.getDeclaredMethod("startPendingIntent",View::class.java,PendingIntent::class.java,Pair::class.java)
                .apply { isAccessible=true }.invoke(null,view,pending,options)==true) { "Native rear launch rejected" }
        // Keep the notification source mounted through the shared-element
        // handoff. Cancelling it on send() removed the icon before Shell drew it.
            if (origin == null) onOpened?.invoke()
            if(prefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, RearAppDefaults.ENABLED)) {
            launchOrigin = origin
            targetPackageName = targetPackage
            origin?.let { returnKind=it.kind;awaitingReturn=true }
            val callback=onLaunched
            run {
                // Transfer focus is the native handoff point, including cold app
                // starts. A fixed delay could hide the source before Shell had a frame.
                val root=view.rootView
                if(!root.hasWindowFocus())root.postOnAnimation { callback?.invoke() }
                else {
                    val observer=root.viewTreeObserver
                    lateinit var listener:android.view.ViewTreeObserver.OnWindowFocusChangeListener
                    listener=android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused->
                        if(!focused) {
                            if(observer.isAlive)observer.removeOnWindowFocusChangeListener(listener)
                            root.postOnAnimation { callback?.invoke() }
                        }
                    }
                    observer.addOnWindowFocusChangeListener(listener)
                }
            }
            }
        }
        // Launch while the stock card is opening, so its Folme progress also
        // drives the real task surface and the picker list in the same frames.
        sendLaunch()
    }
}
