package com.souleven.nothingos.hooks

import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {

    private val DOT_PADDING_PX = 2
    private val BIND_HEADROOM = 20
    private val ICON_SIZE_FALLBACK = 66
    @Volatile private var logCount = 0

    private fun idName(v: View): String {
        return try {
            if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "no-id"
        } catch (t: Throwable) {
            "res-error"
        }
    }

    private fun isStatusBarIcons(obj: Any?): Boolean {
        return obj is View && idName(obj) == "notificationIcons"
    }

    private fun isAodIcons(obj: Any?): Boolean {
        return obj is View && idName(obj) == "aod_notification_icon_container"
    }

    private fun readMaxIcons(prefs: Prefs): Int? {
        return try {
            prefs.getString("pref_max_notif_icons", "")?.trim()?.toIntOrNull()?.takeIf { it in 1..50 }
        } catch (t: Throwable) {
            null
        }
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
    private fun getFloatStr(o: Any, name: String): String {
        return try { "" + XposedHelpers.getFloatField(o, name) } catch (t: Throwable) { "NA" }
    }
    private fun getBoolStr(o: Any, name: String): String {
        return try { "" + XposedHelpers.getBooleanField(o, name) } catch (t: Throwable) { "NA" }
    }

    private fun iconSizeOf(v: View): Int {
        return try {
            val s = XposedHelpers.getIntField(v, "mIconSize")
            if (s > 0) s else ICON_SIZE_FALLBACK
        } catch (t: Throwable) {
            ICON_SIZE_FALLBACK
        }
    }

    private fun targetWidth(v: View, n: Int): Int {
        var screen = 1080
        try { screen = v.resources.displayMetrics.widthPixels } catch (t: Throwable) {}
        val want = (n + 1) * iconSizeOf(v)
        val cap = screen * 3 / 4
        return if (want > cap) cap else want
    }

    private fun nthParent(v: View, n: Int): View? {
        var p: ViewParent? = v.parent
        var i = 1
        while (i < n) {
            if (p == null) return null
            p = p.parent
            i += 1
        }
        return if (p is View) p as View else null
    }

    private fun widenParents(v: View, target: Int) {
        var level = 1
        while (level <= 4) {
            val p = nthParent(v, level)
            if (p != null) {
                try {
                    val lp = p.layoutParams
                    if (lp != null && lp.width >= 0 && lp.width < target) {
                        lp.width = target
                        p.layoutParams = lp
                    }
                } catch (t: Throwable) {}
            }
            level += 1
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val containerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationIconContainer",
            lpparam.classLoader
        ) ?: return

        // (A) ГЛАВНЫЙ РЫЧАГ: видимое число статус-бара = N напрямую через
        //     getMaxVisibleIcons. Поле mMaxStaticIcons на этом ROM игнорируется
        //     (лог v7: maxStatic=8, а видно 4).
        try {
            XposedBridge.hookAllMethods(containerClass, "getMaxVisibleIcons", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    val sb = isStatusBarIcons(v)
                    val aod = isAodIcons(v)
                    if (!sb && !aod) return
                    val view = v as View
                    val max = readMaxIcons(prefs)
                    val orig = param.result
                    if (sb && max != null) {
                        param.result = max
                    }
                    if (logCount < 20) {
                        logCount += 1
                        val cc = if (v is ViewGroup) v.childCount else -1
                        XposedBridge.log(
                            "NothingTweaks: GETMAX id=" + idName(view) +
                            " orig=" + orig +
                            " forced=" + (if (sb && max != null) max.toString() else "no") +
                            " childCount=" + cc +
                            " onLock=" + getBoolStr(v, "mOnLockScreen") +
                            " staticLayout=" + getBoolStr(v, "mIsStaticLayout")
                        )
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: hook getMaxVisibleIcons failed: ${t.message}")
        }

        // (B) Физически вместить N + поджать точку + убрать фантомный отступ.
        //     Только статус-бар.
        try {
            XposedBridge.hookAllMethods(containerClass, "calculateIconXTranslations", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if (!isStatusBarIcons(v)) return
                    val view = v as View
                    val max = readMaxIcons(prefs)
                    if (max != null) {
                        setIntSafe(v, "mMaxStaticIcons", max)
                        setIntSafe(v, "mMaxIcons", BIND_HEADROOM)
                        val target = targetWidth(view, max)
                        widenParents(view, target)
                        setIntSafe(v, "mActualLayoutWidth", target)
                    }
                    setIntSafe(v, "mDotPadding", DOT_PADDING_PX)
                    setFloatSafe(v, "mActualPaddingStart", 0f)

                    if (logCount < 20 && v is ViewGroup && v.childCount >= 3) {
                        logCount += 1
                        XposedBridge.log(
                            "NothingTweaks: STATE read=" + (max?.toString() ?: "NULL") +
                            " childCount=" + v.childCount +
                            " width=" + view.width +
                            " maxStatic=" + getIntStr(v, "mMaxStaticIcons") +
                            " maxIcons=" + getIntStr(v, "mMaxIcons") +
                            " actualLayoutW=" + getIntStr(v, "mActualLayoutWidth") +
                            " padStart=" + getFloatStr(v, "mActualPaddingStart") +
                            " dotPad=" + getIntStr(v, "mDotPadding") +
                            " iconSize=" + getIntStr(v, "mIconSize")
                        )
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: hook calculateIconXTranslations failed: ${t.message}")
        }

        // (C) Чтобы расчёт переполнения видел расширенную область.
        try {
            XposedBridge.hookAllMethods(containerClass, "getActualWidth", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if (!isStatusBarIcons(v)) return
                    val view = v as View
                    val max = readMaxIcons(prefs) ?: return
                    val target = targetWidth(view, max)
                    val cur = param.result as? Int ?: 0
                    if (target > cur) param.result = target
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: hook getActualWidth failed: ${t.message}")
        }
    }
}
