package com.souleven.nothingos.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {

    // R.id.content на этой прошивке (контейнер иконок статус-бара). Резолвим по имени,
    // с запасным числовым значением на случай другой сборки.
    private var statusBarContainerId = 0

    private fun statusBarId(view: Any): Int {
        if (statusBarContainerId != 0) return statusBarContainerId
        statusBarContainerId = try {
            val ctx = XposedHelpers.callMethod(view, "getContext") as android.content.Context
            val r = ctx.resources.getIdentifier("content", "id", ctx.packageName)
            if (r != 0) r else 2131362411
        } catch (t: Throwable) {
            2131362411
        }
        return statusBarContainerId
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val containerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationIconContainer",
            lpparam.classLoader
        ) ?: return

        // Hook 1 — setMaxIconsAmount (запрашиваемая ширина в onMeasure; держим = лимиту)
        try {
            XposedBridge.hookAllMethods(containerClass, "setMaxIconsAmount", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val maxIcons = prefs.getString("pref_max_notif_icons", "").toIntOrNull() ?: return
                    param.args[0] = maxIcons
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [NotificationIconHooks] setMaxIconsAmount hook failed: ${t.message}")
        }

        // Hook 2 — initResources (лимиты AOD / Lockscreen)
        try {
            XposedBridge.hookAllMethods(containerClass, "initResources", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val maxIcons = prefs.getString("pref_max_notif_icons", "").toIntOrNull() ?: return
                    XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnAod", maxIcons)
                    XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnLockscreen", maxIcons)
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [NotificationIconHooks] initResources hook failed: ${t.message}")
        }

        // Hook 3 — getIconLimit (путь Android 14+)
        try {
            val dataClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData",
                lpparam.classLoader
            )
            if (dataClass != null) {
                XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val maxIcons = prefs.getString("pref_max_notif_icons", "").toIntOrNull() ?: return
                        param.result = maxIcons
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [NotificationIconHooks] getIconLimit hook failed: ${t.message}")
        }

        // Hook 4 — ГЛАВНЫЙ ФИКС. getActualWidth() периодически возвращает 0 (mActualLayoutWidth=0)
        // на контейнере статус-бара → getRightBound() уходит в минус → всё «переполняется».
        // Если пришёл 0 при реальной getWidth()>0 — отдаём реальную ширину.
        try {
            XposedBridge.hookAllMethods(containerClass, "getActualWidth", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    val id = XposedHelpers.callMethod(v, "getId") as Int
                    if (id != statusBarId(v)) return
                    val actual = param.result as? Int ?: return
                    if (actual > 0) return
                    val real = XposedHelpers.callMethod(v, "getWidth") as Int
                    if (real > 0) param.result = real
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [NotificationIconHooks] getActualWidth hook failed: ${t.message}")
        }

        // Hook 5 — ДИАГНОСТИКА (можно оставить для проверки; фильтр: NTX_ICO). Убери потом.
        try {
            XposedBridge.hookAllMethods(containerClass, "calculateIconXTranslations", object : XC_MethodHook() {
                private var last = 0L
                override fun afterHookedMethod(param: MethodHookParam) {
                    val now = System.currentTimeMillis()
                    if (now - last < 500) return
                    last = now
                    val v = param.thisObject
                    try {
                        val id = XposedHelpers.callMethod(v, "getId") as Int
                        val cc = XposedHelpers.callMethod(v, "getChildCount") as Int
                        val aw = XposedHelpers.callMethod(v, "getActualWidth") as Int
                        val rb = XposedHelpers.callMethod(v, "getRightBound") as Float
                        val dot = XposedHelpers.getBooleanField(v, "mIsShowingOverflowDot")
                        XposedBridge.log("NTX_ICO id=$id cc=$cc aw=$aw R=$rb dot=$dot")
                    } catch (e: Throwable) {
                        XposedBridge.log("NTX_ICO diag error: ${e.message}")
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [NotificationIconHooks] diag hook failed: ${t.message}")
        }
    }
}
