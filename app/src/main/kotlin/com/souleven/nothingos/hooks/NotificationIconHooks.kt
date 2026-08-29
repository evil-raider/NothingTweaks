package com.souleven.nothingos.hooks

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {

    private val DOT_PADDING_PX = 2
    private val BIND_LIMIT = 20
    @Volatile private var logCount = 0

    private fun isStatusBarIcons(obj: Any?): Boolean {
        if (obj !is View) return false
        return try {
            val id = obj.id
            if (id == View.NO_ID) false
            else obj.resources.getResourceEntryName(id) == "notificationIcons"
        } catch (t: Throwable) {
            false
        }
    }

    private fun readMaxIcons(prefs: Prefs): Int? {
        val v = prefs.getInt("pref_max_notif_icons", -1)
        return if (v > 0) v else null
    }

    private fun setIntSafe(o: Any, name: String, value: Int) {
        try { XposedHelpers.setIntField(o, name, value) } catch (t: Throwable) {}
    }
    private fun setFloatSafe(o: Any, name: String, value: Float) {
        try { XposedHelpers.setFloatField(o, name, value) } catch (t: Throwable) {}
    }
    private fun getIntStr(o: Any, name: String): String {
        return try { "" + XposedHelpers.getIntField(o, name) } catch (t: Throwable) { "NA" }
    }
    private fun getBoolStr(o: Any, name: String): String {
        return try { "" + XposedHelpers.getBooleanField(o, name) } catch (t: Throwable) { "NA" }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val containerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationIconContainer",
            lpparam.classLoader
        ) ?: return

        try {
            XposedBridge.hookAllMethods(containerClass, "calculateIconXTranslations", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if (!isStatusBarIcons(v)) return
                    setIntSafe(v, "mDotPadding", DOT_PADDING_PX)
                    setFloatSafe(v, "mActualPaddingStart", 0f)
                    val max = readMaxIcons(prefs)
                    if (max != null) {
                        setIntSafe(v, "mMaxStaticIcons", max)
                        setIntSafe(v, "mMaxIcons", max)
                    }
                    if (logCount < 12 && v is ViewGroup && v.childCount >= 3) {
                        logCount += 1
                        XposedBridge.log(
                            "NothingTweaks: STATE read=" + (max?.toString() ?: "NULL") +
                            " childCount=" + v.childCount +
                            " maxStatic=" + getIntStr(v, "mMaxStaticIcons") +
                            " maxIcons=" + getIntStr(v, "mMaxIcons") +
                            " dotPad=" + getIntStr(v, "mDotPadding") +
                            " staticLayout=" + getBoolStr(v, "mIsStaticLayout") +
                            " onLock=" + getBoolStr(v, "mOnLockScreen") +
                            " iconSize=" + getIntStr(v, "mIconSize")
                        )
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [NotificationIconHooks] calculateIconXTranslations failed: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(containerClass, "setMaxIconsAmount", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isStatusBarIcons(param.thisObject)) return
                    val max = readMaxIcons(prefs) ?: return
                    if (param.args.isNotEmpty()) param.args[0] = max
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [NotificationIconHooks] setMaxIconsAmount failed: ${t.message}")
        }

        try {
            val dataClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData",
                lpparam.classLoader
            )
            if (dataClass != null) {
                XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = BIND_LIMIT
                    }
                })
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [NotificationIconHooks] getIconLimit failed: ${t.message}")
        }
    }
}
