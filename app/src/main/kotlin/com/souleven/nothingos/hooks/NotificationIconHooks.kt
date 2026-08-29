package com.souleven.nothingos.hooks

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {

    @Volatile private var lastLogTime = 0L

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val containerClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.NotificationIconContainer", lpparam.classLoader)
        if (containerClass != null) {

            @Volatile var cachedContentId = 0

            fun isStatusBarContainer(view: Any): Boolean {
                return try {
                    if (view.javaClass != containerClass) return false
                    val v = view as View
                    if (cachedContentId == 0) {
                        cachedContentId = v.resources.getIdentifier("content", "id", lpparam.packageName)
                    }
                    cachedContentId != 0 && v.id == cachedContentId
                } catch (t: Throwable) {
                    false
                }
            }

            fun resNameOf(view: View): String {
                return try {
                    if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else "no-id"
                } catch (t: Throwable) {
                    "no-id"
                }
            }

            try {
                XposedBridge.hookAllMethods(containerClass, "setMaxIconsAmount", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                        val maxIcons = maxIconsStr.toIntOrNull()
                        if (maxIcons != null) {
                            param.args[0] = maxIcons
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook setMaxIconsAmount: ${t.message}")
            }

            // Hook initResources to override AOD and Lockscreen limit
            try {
                XposedBridge.hookAllMethods(containerClass, "initResources", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                        val maxIcons = maxIconsStr.toIntOrNull()
                        if (maxIcons != null) {
                            XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnAod", maxIcons)
                            XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnLockscreen", maxIcons)
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook initResources: ${t.message}")
            }

            // Hook 4: getActualWidth sometimes reports 0 before layout settles on the real
            // status-bar container; fall back to the container's real measured width.
            try {
                XposedBridge.hookAllMethods(containerClass, "getActualWidth", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject
                        if (!isStatusBarContainer(view)) return
                        val result = param.result
                        if (result is Int && result == 0) {
                            val realWidth = (view as View).width
                            if (realWidth > 0) {
                                param.result = realWidth
                            }
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook getActualWidth: ${t.message}")
            }

            // Hook 5: diagnostic-only. Logs per-icon states plus the parent chain / siblings of
            // the REAL status-bar icon container so we can find what clips it to ~4 icons.
            try {
                XposedBridge.hookAllMethods(containerClass, "calculateIconXTranslations", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject
                        if (!isStatusBarContainer(view)) return
                        val now = System.currentTimeMillis()
                        if (now - lastLogTime < 1000) return
                        lastLogTime = now
                        try {
                            val v = view as ViewGroup
                            val cc = v.childCount
                            val actualWidth = try { XposedHelpers.callMethod(v, "getActualWidth") } catch (t: Throwable) { null }

                            @Suppress("UNCHECKED_CAST")
                            val iconStates = try {
                                XposedHelpers.getObjectField(v, "mIconStates") as? Map<Any, Any>
                            } catch (t: Throwable) { null }

                            val sbIcons = StringBuilder("cc=$cc actualWidth=$actualWidth L=${v.left} R=${v.right} :: ")
                            iconStates?.entries?.forEachIndexed { idx, entry ->
                                val state = entry.value
                                val vs = try { XposedHelpers.getIntField(state, "visibleState") } catch (t: Throwable) { -1 }
                                val x = try { XposedHelpers.getFloatField(state, "xTranslation") } catch (t: Throwable) { -1f }
                                sbIcons.append("#$idx vs=$vs x=$x; ")
                            }
                            XposedBridge.log("NTX_ICO $sbIcons")

                            var parent = v.parent
                            var level = 0
                            val sbPar = StringBuilder()
                            while (parent is View && level < 7) {
                                sbPar.append("L$level[${resNameOf(parent)}/${parent.javaClass.simpleName} w=${parent.width} l=${parent.left} r=${parent.right] ")
                                parent = parent.parent
                                level++
                            }
                            XposedBridge.log("NTX_PAR $sbPar")

                            val directParent = v.parent
                            if (directParent is ViewGroup) {
                                val sbSib = StringBuilder()
                                for (i in 0 until directParent.childCount) {
                                    val child = directParent.getChildAt(i)
                                    sbSib.append("${resNameOf(child)}/${child.javaClass.simpleName} ")
                                }
                                XposedBridge.log("NTX_SIB $sbSib")
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("NothingTweaks: [NotificationIconHooks] NTX log error: ${t.message}")
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook calculateIconXTranslations: ${t.message}")
            }
        }

        // Hook getIconLimit in NotificationIconsViewData (Android 14+ ViewModel flow)
        try {
            val dataClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData", lpparam.classLoader)
            if (dataClass != null) {
                XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                        val maxIcons = maxIconsStr.toIntOrNull()
                        if (maxIcons != null) {
                            param.result = maxIcons
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook getIconLimit: ${t.message}")
        }
    }
}
