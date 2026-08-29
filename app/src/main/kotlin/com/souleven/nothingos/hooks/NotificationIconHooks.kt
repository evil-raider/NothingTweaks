package com.souleven.nothingos.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {

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

    private fun prefLimit(prefs: Prefs): Int? =
        prefs.getString("pref_max_notif_icons", "").toIntOrNull()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val containerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationIconContainer",
            lpparam.classLoader
        ) ?: return

        // Hook 1 — setMaxIconsAmount -> pref
        try {
            XposedBridge.hookAllMethods(containerClass, "setMaxIconsAmount", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val maxIcons = prefLimit(prefs) ?: return
                    param.args[0] = maxIcons
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: setMaxIconsAmount hook failed: ${t.message}")
        }

        // Hook 2 — initResources -> AOD/Lockscreen = pref (исходное поведение NothingTweaks)
        try {
            XposedBridge.hookAllMethods(containerClass, "initResources", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val maxIcons = prefLimit(prefs) ?: return
                    XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnAod", maxIcons)
                    XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnLockscreen", maxIcons)
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: initResources hook failed: ${t.message}")
        }

        // Hook 3 — getIconLimit -> pref
        try {
            val dataClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData",
                lpparam.classLoader
            )
            if (dataClass != null) {
                XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val maxIcons = prefLimit(prefs) ?: return
                        param.result = maxIcons
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: getIconLimit hook failed: ${t.message}")
        }

        // Hook 4 — getActualWidth: 0 -> getWidth (страховка, только статус-бар)
        try {
            XposedBridge.hookAllMethods(containerClass, "getActualWidth", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if ((XposedHelpers.callMethod(v, "getId") as Int) != statusBarId(v)) return
                    val actual = param.result as? Int ?: return
                    if (actual > 0) return
                    val real = XposedHelpers.callMethod(v, "getWidth") as Int
                    if (real > 0) param.result = real
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: getActualWidth hook failed: ${t.message}")
        }

        // Hook 5 — ДИАГНОСТИКА: реальные ширины иконок, X, hidden/visibleState, геометрия.
        //          Фильтр: NTX_ICO
        try {
            XposedBridge.hookAllMethods(containerClass, "calculateIconXTranslations", object : XC_MethodHook() {
                private var last = 0L
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    val id = XposedHelpers.callMethod(v, "getId") as Int
                    if (id != statusBarId(v)) return
                    val now = System.currentTimeMillis()
                    if (now - last < 700) return
                    last = now
                    try {
                        val cc = XposedHelpers.callMethod(v, "getChildCount") as Int
                        val mMax = XposedHelpers.getIntField(v, "mMaxIcons")
                        val iconSize = XposedHelpers.getIntField(v, "mIconSize")
                        val vos = XposedHelpers.getFloatField(v, "mVisualOverflowStart")
                        val left = XposedHelpers.callMethod(v, "getLeftBound") as Float
                        val right = XposedHelpers.callMethod(v, "getRightBound") as Float
                        val states = XposedHelpers.getObjectField(v, "mIconStates") as java.util.HashMap<*, *>
                        val n = if (cc < 10) cc else 10
                        val sb = StringBuilder()
                        for (i in 0 until n) {
                            val child = XposedHelpers.callMethod(v, "getChildAt", i)
                            val w = XposedHelpers.callMethod(child, "getWidth") as Int
                            val st = states[child]
                            var x = -1f; var hid = 0; var vis = -1; var app = -1f
                            if (st != null) {
                                x = XposedHelpers.callMethod(st, "getXTranslation") as Float
                                hid = if (XposedHelpers.getBooleanField(st, "hidden")) 1 else 0
                                vis = XposedHelpers.getIntField(st, "visibleState")
                                app = XposedHelpers.getFloatField(st, "iconAppearAmount")
                            }
                            sb.append("#$i(w=$w,x=$x,h=$hid,vs=$vis,a=$app) ")
                        }
                        XposedBridge.log("NTX_ICO cc=$cc mMax=$mMax iconSize=$iconSize L=$left R=$right vos=$vos :: $sb")
                    } catch (e: Throwable) {
                        XposedBridge.log("NTX_ICO diag error: ${e.message}")
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: diag hook failed: ${t.message}")
        }
    }
}
