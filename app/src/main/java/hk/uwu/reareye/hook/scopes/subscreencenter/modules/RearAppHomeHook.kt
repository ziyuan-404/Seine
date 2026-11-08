package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.app.Activity
import android.content.Intent
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearAppDefaults
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.HotReloadPolicy
import org.luckypray.dexkit.annotations.DexKitExperimentalApi

/** Opens the actual stock app-card picker when navigation returns from a rear app. */
@OptIn(DexKitExperimentalApi::class)
class RearAppHomeHook : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            var active = true
            runtime.onClose { active = false }
            runCatching {
                val bridge = runtime.manage(createDexKitCacheBridge(
                    packageName = hookAppInfo.packageName,
                    packageVersionCode = resolveHookPackageVersionCode(
                        hookSystemContext, hookAppInfo.packageName, hookAppInfo.sourceDir,
                    ),
                    sourceDir = hookAppInfo.sourceDir,
                    dataDir = hookAppInfo.dataDir,
                ))
                val finishPoint = resolveDexKitMethodInjectionPoint(bridge, "SSC_REAR_APP_HOME_FINISH") {
                    findMethod { matcher {
                        paramCount(0)
                        returnType = "void"
                        usingStrings("onFinishGesture: mLauncherContainer.getTranslationY() = ")
                    } }.singleOrNull()
                } ?: error("Native app picker completion method missing")
                val panelClass = finishPoint.className.toClass()
                NativeRearCardAnimation.panelClass = panelClass
                // Sample the stock card's Folme property after its own setter
                // updates the clip and child matrix. The launcher stops drawing
                // once an external app takes focus, so pre-draw cannot drive it.
                val cardProgress = "U1.F".toClass()
                cardProgress.declaredMethods.filter {
                    it.name == "setValue" && it.parameterCount == 2 && it.parameterTypes[1] == Float::class.javaPrimitiveType
                }.forEach { method ->
                    cardProgress.resolve().firstMethod { name = method.name; parameters(*method.parameterTypes) }.hook {
                        hotReloadPolicy = HotReloadPolicy.REINSTALL
                        after { (args(0).value as? View)?.let { NativeRearCardAnimation.onStockCardProgress(it) } }
                    }
                }
                "com.xiaomi.subscreencenter.launcher.LauncherCardView".toClass().resolve().firstMethod {
                    name = "d"; parameterCount = 0
                }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    after { (instance as? View)?.let { NativeRearCardAnimation.onStockCardProgress(it) } }
                }
                panelClass.resolve().firstMethod {
                    name = "z"; parameters(String::class.java, Boolean::class.javaPrimitiveType!!)
                }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    after {
                        val owner = instance
                        if (owner in NativeRearCardAnimation.isolatedPanels && !args(1).boolean() && owner != null)
                            NativeRearCardAnimation.onStockPanelProgress(owner)
                    }
                }
                // The animation-only owner must not release the real launcher's
                // widget session when its native close callback runs.
                panelClass.resolve().firstMethod { name = "w"; parameterCount = 0 }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    before {
                        if (instance in NativeRearCardAnimation.isolatedPanels) {
                            panelClass.getDeclaredField("k").apply { isAccessible = true }.set(instance, null)
                            result = null
                        }
                    }
                }
                val closePoint = resolveDexKitMethodInjectionPoint(bridge, "SSC_REAR_APP_HOME_CLOSE_CARD") {
                    findMethod { matcher {
                        declaredClass = finishPoint.className
                        paramTypes(Boolean::class.java)
                        returnType = "void"
                        usingStrings("closeAppCard: mActivated = ")
                    } }.singleOrNull()
                }
                val closeCard = closePoint?.let {
                    panelClass.getDeclaredMethod(it.methodName, Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                }
                val showPoint = resolveDexKitMethodInjectionPoint(bridge, "SSC_REAR_APP_HOME_NATIVE_SHOW") {
                    findMethod { matcher {
                        paramTypes(Boolean::class.java)
                        returnType = "boolean"
                        usingStrings("State is same, show = ", "State is not same, old = ")
                    } }.singleOrNull()
                } ?: error("Native launcher show/hide gesture entry missing")
                val gestureClass = showPoint.className.toClass()
                val show = gestureClass.getDeclaredMethod(showPoint.methodName, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                val controllerName = resolveDexKitClassValue(bridge, "SSC_REAR_APP_HOME_CONTROLLER") {
                    findClass { matcher { usingStrings(
                        "LauncherController", "isChangBaAppSupportRearScreen support = ",
                    ) } }.singleOrNull()
                } ?: error("Native launcher controller missing")
                val controllerClass = controllerName.toClass()
                val gestureField = controllerClass.declaredFields.single { gestureClass.isAssignableFrom(it.type) }
                    .apply { isAccessible = true }
                val launcher = "com.xiaomi.subscreencenter.SubScreenLauncher".toClass()
                // The launcher already owns a full-screen blur layer. External
                // freeform tasks launched from notifications and shortcuts do
                // not go through the picker path that normally raises it.
                val nativeBlur = runCatching {
                    launcher.getMethod("a", String::class.java, Float::class.javaPrimitiveType)
                }.getOrNull()
                val nativeBlurProgress = runCatching { launcher.getMethod("c") }.getOrNull()
                var blurOwner: Activity? = null
                fun syncRearBlur(activity: Activity, appVisible: Boolean) {
                    if (nativeBlur == null) return
                    if (appVisible) {
                        val progress = runCatching {
                            (nativeBlurProgress?.invoke(activity) as? Number)?.toFloat() ?: 0f
                        }.getOrDefault(0f)
                        if (blurOwner === activity || progress < .99f) {
                            runCatching { nativeBlur.invoke(activity, "rearApp", 1f) }
                                .onFailure { YLog.warn("Native rear app backdrop blur unavailable", it) }
                            blurOwner = activity
                        }
                    } else if (blurOwner === activity) {
                        blurOwner = null
                        runCatching { nativeBlur.invoke(activity, "rearApp", 0f) }
                    }
                }
                val field = launcher.declaredFields.single { panelClass.isAssignableFrom(it.type) }
                    .apply { isAccessible = true }
                val controllerField = launcher.declaredFields.single { controllerClass.isAssignableFrom(it.type) }
                    .apply { isAccessible = true }
                val currentLauncher="com.xiaomi.subscreencenter.SubScreenCenterApp".toClass().declaredMethods.single {
                    java.lang.reflect.Modifier.isStatic(it.modifiers)&&it.parameterCount==0&&it.returnType==launcher
                }.apply { isAccessible=true }
                var registerAppHomeGesture:(()->Unit)?=null
                var syncAfterLaunch: (() -> Unit)? = null
                val assistant="com.xiaomi.subscreencenter.SmartAssistantPanel".toClass()
                val assistantField=launcher.declaredFields.single { assistant.isAssignableFrom(it.type) }.apply { isAccessible=true }
                val showAssistant=assistant.getMethod("setAssistantVisibleImpl",Boolean::class.javaPrimitiveType)
                var restoreNotificationOnFocus=false
                fun showNotificationOrigin(activity:Activity) {
                    assistantField.get(activity)?.let { showAssistant.invoke(it,true) }
                }
                fun preparePicker() {
                    val activity = currentLauncher.invoke(null) as? Activity ?: return
                    if (!active || activity.isDestroyed) return
                    val panel = field.get(activity) as? View ?: return
                    val id = activity.resources.getIdentifier("launcher_container", "id", activity.packageName)
                    val container = panel.findViewById<View>(id) ?: return
                    if (panel.isActivated && container.isShown && container.translationY == 0f &&
                        container.alpha == 1f && container.scaleX == 1f && container.scaleY == 1f) return
                    assistantField.get(activity)?.let { showAssistant.invoke(it, false) }
                    // Cancel the pending native hide before settling through its
                    // gesture completion, preserving the RecyclerView position.
                    "miuix.animation.Folme".toClass().getMethod("clean", View::class.java).invoke(null, container)
                    container.alpha = 1f; container.scaleX = 1f; container.scaleY = 1f
                    panelClass.getMethod("setTranslation", Int::class.javaPrimitiveType).invoke(panel, 0)
                    panelClass.getDeclaredMethod(finishPoint.methodName).apply { isAccessible = true }.invoke(panel)
                }
                val prepare: () -> Unit = ::preparePicker
                val scalePickerList = panelClass.getMethod("C", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                val animateList: (Boolean) -> Unit = { opening ->
                    runCatching {
                        val activity = currentLauncher.invoke(null) as? Activity ?: return@runCatching
                        if (!active || activity.isDestroyed) return@runCatching
                        val panel = field.get(activity) ?: return@runCatching
                        if (opening) scalePickerList.invoke(panel, 1f, .8f)
                        else scalePickerList.invoke(panel, .8f, 1f)
                    }.onFailure { YLog.warn("Native rear picker list animation failed", it) }
                }
                NativeRearCardAnimation.preparePicker = prepare
                NativeRearCardAnimation.animatePickerList = animateList
                runtime.onClose {
                    NativeRearCardAnimation.closeAll()
                    if (NativeRearCardAnimation.preparePicker === prepare) NativeRearCardAnimation.preparePicker = null
                    if (NativeRearCardAnimation.animatePickerList === animateList) NativeRearCardAnimation.animatePickerList = null
                    NativeRearCardAnimation.panelClass = null
                }
                val restoreOrigin:(Int)->Unit={ kind ->
                    runCatching {
                        val activity=currentLauncher.invoke(null) as? Activity
                        if(active&&kind==RearLaunchOrigin.PICKER&&activity!=null&&!activity.isDestroyed) {
                            preparePicker()
                        } else if(active&&kind==RearLaunchOrigin.NOTIFICATION&&activity!=null&&!activity.isDestroyed) {
                            restoreNotificationOnFocus=true
                            showNotificationOrigin(activity)
                        }
                    }.onFailure { YLog.warn("Restoring native rear launch source failed",it) }
                }
                NativeRearAppLaunch.onRestoreOrigin=restoreOrigin
                runtime.onClose { if(NativeRearAppLaunch.onRestoreOrigin===restoreOrigin)NativeRearAppLaunch.onRestoreOrigin=null }
                val retainLauncherAfterLaunch:()->Unit={
                    runCatching {
                        val activity=currentLauncher.invoke(null) as? Activity
                        if(active&&activity!=null&&!activity.isDestroyed) {
                            val popupType = "com.xiaomi.subscreencenter.NotificationPanel".toClass()
                            launcher.declaredFields.firstOrNull { it.type == popupType }?.apply { isAccessible = true }
                                ?.get(activity)?.let { popupType.getMethod("e", String::class.java).invoke(it, "rearApp") }
                            // Native cards retain their launcher list and scroll
                            // position while expanded. External apps do the same;
                            // hiding/reopening the list resets it to the first row.
                            registerAppHomeGesture?.invoke()
                            syncRearBlur(activity, true)
                            listOf(32L, 120L, 350L, 800L).forEach { delay ->
                                activity.window.decorView.postDelayed({ if (active) syncAfterLaunch?.invoke() }, delay)
                            }
                        }
                    }.onFailure { YLog.warn("Retaining native rear picker after app launch failed",it) }
                }
                NativeRearAppLaunch.onLaunched=retainLauncherAfterLaunch
                runtime.onClose { if(NativeRearAppLaunch.onLaunched===retainLauncherAfterLaunch)NativeRearAppLaunch.onLaunched=null }
                val queryRearTasks = "c2.b".toClass().getMethod("b", Int::class.javaPrimitiveType)
                fun rearTasks(): List<android.app.ActivityManager.RunningTaskInfo> =
                    (queryRearTasks.invoke(null, 30) as? List<*>).orEmpty()
                        .filterIsInstance<android.app.ActivityManager.RunningTaskInfo>()
                fun rearAppTask(): android.app.ActivityManager.RunningTaskInfo? {
                    // Use the ROM's explicit display-1 query. ActivityManager's
                    // generic task query can return a different display's list.
                    val top = rearTasks().firstOrNull()
                    // Parked native freeforms may still report visible during their
                    // transition. Only the top rear task owns navigation; never
                    // let a background app capture drags inside the stock drawer.
                    return top?.takeIf { it.isVisible && it.topActivity?.packageName != "com.xiaomi.subscreencenter" }
                }
                fun rearAppForeground() = rearAppTask() != null
                val taskManager="android.app.ActivityTaskManager".toClass().getDeclaredMethod("getService").invoke(null)
                val bringHome=taskManager.javaClass.getMethod("startActivityFromRecents",Int::class.javaPrimitiveType,android.os.Bundle::class.java)
                var nativeGestureFinished = false
                val returnHome=Runnable {
                    val activity=currentLauncher.invoke(null) as? Activity
                    if(active&&hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, RearAppDefaults.ENABLED)&&activity!=null&&rearAppForeground()) {
                        YLog.debug("[RearAppHome] " + (if (nativeGestureFinished) "recents fallback" else "native action") + " target=" + activity.taskId)
                        activity.sendBroadcast(Intent(RearLaunchOrigin.CLOSING_ACTION).setPackage("com.android.systemui"))
                        syncRearBlur(activity, false)
                        NativeRearAppLaunch.restoreOriginIfNeeded(toPicker = true)
                        // A real native recents gesture has already transformed
                        // its leash. A second Shell close would replay the exit.
                        val options = if(nativeGestureFinished)
                            android.app.ActivityOptions.makeCustomAnimation(activity,0,0)
                        else android.app.ActivityOptions.makeBasic()
                        val launch = options.setLaunchDisplayId(1).toBundle()
                        val stack = rearTasks()
                        if (!nativeGestureFinished && stack.getOrNull(1)?.taskId == activity.taskId)
                            launch.putInt("hk.uwu.reareye.REAR_PICKER_SOURCE_TASK", stack.first().taskId)
                        bringHome.invoke(taskManager, activity.taskId, launch)
                    }
                }
                // The stock recents animation excludes freeform tasks and waits for a
                // callback that never arrives. Its built-in Home action recognizer
                // already supports callbacks for non-recents states; use that path
                // while an app is open, then restore normal launcher gestures.
                val managerName=resolveDexKitClassValue(bridge,"SSC_REAR_APP_GESTURE_MANAGER") {
                    findClass { matcher { usingStrings("SubScreenGestureManager","Try enable default back ") } }.singleOrNull()
                }?:error("Stock rear gesture manager missing")
                val registerPoint=resolveDexKitMethodInjectionPoint(bridge,"SSC_REAR_APP_HOME_REGISTER") {
                    findMethod { matcher {
                        declaredClass=managerName;paramTypes("java.lang.Runnable","int");returnType="void"
                        usingNumbers(100,103)
                    } }.singleOrNull()
                }?:error("Stock rear Home action registration missing")
                val unregisterPoint=resolveDexKitMethodInjectionPoint(bridge,"SSC_REAR_APP_HOME_UNREGISTER") {
                    findMethod { matcher {
                        declaredClass=managerName;paramTypes("int");returnType="void"
                        usingNumbers(100,103)
                    } }.singleOrNull()
                }?:error("Stock rear Home action removal missing")
                val managerClass=managerName.toClass()
                val appClass="com.xiaomi.subscreencenter.SubScreenCenterApp".toClass()
                val singleton=appClass.declaredFields.single { java.lang.reflect.Modifier.isStatic(it.modifiers)&&it.type==appClass }.apply { isAccessible=true }
                val managerField=appClass.declaredFields.single { it.type==managerClass }.apply { isAccessible=true }
                val register=managerClass.getDeclaredMethod(registerPoint.methodName,Runnable::class.java,Int::class.javaPrimitiveType)
                val unregister=managerClass.getDeclaredMethod(unregisterPoint.methodName,Int::class.javaPrimitiveType)
                val backRegisterPoint=resolveDexKitMethodInjectionPoint(bridge,"SSC_REAR_APP_BACK_REGISTER") {
                    findMethod { matcher { declaredClass=managerName;paramTypes("java.lang.Runnable","int");returnType="void";usingNumbers(9) } }.singleOrNull()
                }?:error("Stock rear Back action registration missing")
                val backUnregisterPoint=resolveDexKitMethodInjectionPoint(bridge,"SSC_REAR_APP_BACK_UNREGISTER") {
                    findMethod { matcher { declaredClass=managerName;paramTypes("int");returnType="void";usingNumbers(9) } }.singleOrNull()
                }?:error("Stock rear Back action removal missing")
                val registerBack=managerClass.getDeclaredMethod(backRegisterPoint.methodName,Runnable::class.java,Int::class.javaPrimitiveType)
                val unregisterBack=managerClass.getDeclaredMethod(backUnregisterPoint.methodName,Int::class.javaPrimitiveType)
                var registeredManager:Any?=null
                var registeredBack=false
                fun clearAppHomeGesture() {
                    registeredManager?.let {
                        unregister.invoke(it,100)
                        if(registeredBack)unregisterBack.invoke(it,7)
                    }
                    registeredBack=false;registeredManager=null
                }
                registerAppHomeGesture={
                    managerField.get(singleton.get(null))?.let {
                        val maps = managerClass.declaredFields.filter { field -> java.util.SortedMap::class.java.isAssignableFrom(field.type) }
                            .mapNotNull { field -> field.isAccessible = true; field.get(it) as? Map<*, *> }
                        val backMap = maps.firstOrNull { map -> map[0] is Runnable }
                        val homeMap = maps.firstOrNull { map -> map !== backMap }
                        if (homeMap?.get(100) == null) register.invoke(it,returnHome,100)
                        registeredManager=it
                        // A paused native panel may retain a default-back suppression
                        // token. Register its existing Back action as an app action;
                        // the factory recognizer and key dispatch remain unchanged.
                        val nativeBack=backMap?.get(0) as? Runnable
                        if(nativeBack!=null&&backMap[7]==null) {
                            // The factory action injects Back on display 1. The
                            // window policy dispatches to the rear app's callback.
                            registerBack.invoke(it,Runnable {
                                val task = rearAppTask()
                                if (active && task != null) {
                                    YLog.debug("[RearAppBack] native key target=" + task.taskId + " app=" + task.topActivity)
                                    nativeBack.run()
                                }
                            },7);registeredBack=true
                        }
                    }
                }
                fun syncNavigation(activity:Activity) {
                    if(!active||!hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, RearAppDefaults.ENABLED))return
                    val appVisible = rearAppTask()?.let { hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter.call(it, "getWindowingMode") == 5 } == true
                    if(appVisible)registerAppHomeGesture?.invoke()
                    else clearAppHomeGesture()
                    syncRearBlur(activity, appVisible)
                }
                syncAfterLaunch = { (currentLauncher.invoke(null) as? Activity)?.let(::syncNavigation) }
                // Route at ACTION_DOWN as well: a focus/task handoff may finish
                // after the last launcher focus callback. Let the ROM choose its
                // existing Home-action recognizer before it can start recents.
                "X1.e".toClass().resolve().firstMethod {
                    name = "g"; parameters(android.view.MotionEvent::class.java)
                }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    before {
                        if (!active || !hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, RearAppDefaults.ENABLED)) return@before
                        val event = args(0).value as? android.view.MotionEvent ?: return@before
                        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN && rearAppForeground()) {
                            registerAppHomeGesture?.invoke()
                            result = false
                        }
                    }
                }
                launcher.resolve().firstMethod { name="onWindowFocusChanged";parameters(Boolean::class.javaPrimitiveType!!) }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after {
                        if(args(0).boolean()) {
                            val activity=instance as? Activity
                            if(activity!=null&&rearAppForeground()) { syncNavigation(activity);return@after }
                            clearAppHomeGesture();NativeRearAppLaunch.restoreOriginIfNeeded()
                            if(restoreNotificationOnFocus) {
                                restoreNotificationOnFocus=false
                                (instance as? Activity)?.let(::showNotificationOrigin)
                            }
                        } else (instance as? Activity)?.let { activity->activity.window.decorView.post { syncNavigation(activity) } }
                    }
                }
                // Adopt apps already in front after a module/launcher reload.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    (currentLauncher.invoke(null) as? Activity)?.let(::syncNavigation)
                }
                runtime.onClose {
                    clearAppHomeGesture()
                    blurOwner?.let { syncRearBlur(it, false) }
                }
                val gestureFinish=resolveDexKitMethodInjectionPoint(bridge,"SSC_REAR_APP_GESTURE_FINISH") {
                    findMethod { matcher {
                        paramTypes(Boolean::class.java,Boolean::class.java);returnType="void"
                        usingStrings("finish: toHome-sendUserLeaveHint : ")
                    } }.singleOrNull()
                }?:error("Native rear Home gesture completion missing")
                gestureFinish.className.toClass().resolve().firstMethod {
                    name=gestureFinish.methodName;parameters(Boolean::class.javaPrimitiveType!!,Boolean::class.javaPrimitiveType!!)
                }.hook {
                    hotReloadPolicy=HotReloadPolicy.REINSTALL
                    after {
                        if(active&&args(0).boolean()&&hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, RearAppDefaults.ENABLED)) {
                            nativeGestureFinished=true
                            try { returnHome.run() } finally { nativeGestureFinished=false }
                        }
                    }
                }

                val disappear = resolveDexKitMethodInjectionPoint(bridge,"SSC_REAR_APP_DISAPPEAR_RECT") {
                    findMethod { matcher {
                        paramCount(0); returnType="android.graphics.Rect"
                        usingStrings("getClosingAppDefaultDisappearRect, rect=")
                    } }.singleOrNull()
                }
                disappear?.let { point ->
                    point.className.toClass().resolve().firstMethod { name=point.methodName;parameterCount=0 }.hook {
                        hotReloadPolicy=HotReloadPolicy.REINSTALL
                        after {
                            val activity=currentLauncher.invoke(null) as? Activity
                            val origin=NativeRearAppLaunch.launchOrigin
                            if(active&&origin!=null&&activity!=null&&rearAppForeground()&&
                                hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, RearAppDefaults.ENABLED)) result=android.graphics.Rect(origin.bounds)
                        }
                    }
                }

                fun open(activity: Activity, request: Intent?) {
                    if (!active || !hookPrefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, RearAppDefaults.ENABLED) ||
                        request?.getBooleanExtra("hk.uwu.reareye.OPEN_REAR_HOME", false) != true ||
                        activity.display?.displayId != 1
                    ) return
                    // Consume once before posting: onNewIntent and onResume may both see the
                    // same request. Ordinary stock drags never carry this explicit navigation flag.
                    request.removeExtra("hk.uwu.reareye.OPEN_REAR_HOME")
                    activity.sendBroadcast(Intent(RearLaunchOrigin.CLOSING_ACTION).setPackage("com.android.systemui"))
                    activity.window.decorView.post {
                        runCatching {
                            if (!active || activity.isDestroyed || activity.isFinishing) return@runCatching
                            val panel = field.get(activity) as? View ?: return@runCatching
                            val controller = controllerField.get(activity) ?: return@runCatching
                            val gesture = gestureField.get(controller) ?: return@runCatching
                            // Use the same native close/show animation entry points as system
                            // navigation, leaving settling, layout and scroll offsets to the ROM.
                            closeCard?.invoke(panel, true)
                            show.invoke(gesture, true)
                            YLog.debug("Rear app requested native stock picker navigation")
                        }.onFailure { YLog.warn("Opening native rear app picker failed", it) }
                    }
                }
                launcher.resolve().firstMethod {
                    name = "onResume"
                    parameterCount = 0
                }.hook {
                    id = "reareye.rear.stock-home.resume"
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    after { (instance as? Activity)?.let { open(it, it.intent) } }
                }
                Activity::class.java.resolve().firstMethod {
                    name = "onNewIntent"
                    parameters(Intent::class.java)
                }.hook {
                    id = "reareye.rear.stock-home.new-intent"
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    after {
                        val activity = instance as? Activity ?: return@after
                        if (launcher.isInstance(activity)) open(activity, args(0).value as? Intent)
                    }
                }
                YLog.info("Native rear app picker home hook installed")
            }.onFailure { YLog.warn("Native rear app picker home unavailable", it) }
        }
    }
}
