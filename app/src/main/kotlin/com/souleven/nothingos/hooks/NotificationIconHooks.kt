package com.souleven.nothingos.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val containerClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.NotificationIconContainer", lpparam.classLoader)
        if (containerClass != null) {

            // Hook 1 — setMaxIconsAmount (+ лог входящего значения от биндера)
            try {
                XposedBridge.hookAllMethods(containerClass, "setMaxIconsAmount", object : XC_MethodHook() {
                    private var last = 0L
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val maxIcons = prefs.getString("pref_max_notif_icons", "").toIntOrNull() ?: return
                        val now = System.currentTimeMillis()
                        if (now - last > 400) {
                            last = now
                            XposedBridge.log("NTX_SET incoming=${param.args[0]} override=$maxIcons")
                        }
                        param.args[0] = maxIcons
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook setMaxIconsAmount: ${t.message}")
            }

            // Hook 2 — initResources (AOD/Lockscreen)
            try {
                XposedBridge.hookAllMethods(containerClass, "initResources", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val maxIcons = prefs.getString("pref_max_notif_icons", "").toIntOrNull() ?: return
                        XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnAod", maxIcons)
                        XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnLockscreen", maxIcons)
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook initResources: ${t.message}")
            }

            // Hook 3 — getIconLimit (путь Android 14+)
            try {
                val dataClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData", lpparam.classLoader)
                if (dataClass != null) {
                    XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val maxIcons = prefs.getString("pref_max_notif_icons", "").toIntOrNull() ?: return
                            param.result = maxIcons
                        }
                    })
                }
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook getIconLimit: ${t.message}")
            }

            // ДИАГНОСТИКА — реальная ширина/границы контейнера в момент раскладки иконок.
            try {
                XposedBridge.hookAllMethods(containerClass, "calculateIconXTranslations", object : XC_MethodHook() {
                    private var last = 0L
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val now = System.currentTimeMillis()
                        if (now - last < 400) return
                        last = now
                        val v = param.thisObject
                        try {
                            val id = XposedHelpers.callMethod(v, "getId") as Int
                            val cc = XposedHelpers.callMethod(v, "getChildCount") as Int
                            val mMax = XposedHelpers.getIntField(v, "mMaxIcons")
                            val w = XposedHelpers.callMethod(v, "getWidth") as Int
                            val aw = XposedHelpers.callMethod(v, "getActualWidth") as Int
                            val lb = XposedHelpers.callMethod(v, "getLeftBound") as Float
                            val rb = XposedHelpers.callMethod(v, "getRightBound") as Float
                            val iconSize = XposedHelpers.getIntField(v, "mIconSize")
                            val dot = XposedHelpers.getBooleanField(v, "mIsShowingOverflowDot")
                            XposedBridge.log("NTX_ICO id=$id cc=$cc mMax=$mMax w=$w aw=$aw L=$lb R=$rb iconSize=$iconSize dot=$dot")
                        } catch (e: Throwable) {
                            XposedBridge.log("NTX_ICO diag error: ${e.message}")
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook calculateIconXTranslations: ${t.message}")
            }
        }
    }
}
