package com.souleven.nothingos.hooks

import android.view.View
import android.view.ViewParent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {

    private val DOT_PADDING_PX = 2
    private val DOT_ROOM_PX = 22
    private val BIND_HEADROOM = 20
    private val ICON_SIZE_FALLBACK = 66

    private fun idName(v: View): String {
        return try {
            if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "no-id"
        } catch (t: Throwable) { "res-error" }
    }
    private fun isStatusBarIcons(obj: Any?): Boolean {
        return obj is View && idName(obj) == "notificationIcons"
    }

    // --- Настройка: "статусбар,аод" ---
    private fun rawMax(prefs: Prefs): String {
        return try { prefs.getString("pref_max_notif_icons", "") ?: "" } catch (t: Throwable) { "" }
    }
    private fun parseAt(raw: String, index: Int): Int? {
        val parts = raw.split(",")
        if (index >= parts.size) return null
        val n = parts[index].trim().toIntOrNull() ?: return null
        return if (n in 1..50) n else null
    }
    private fun statusBarMax(prefs: Prefs): Int? {
        return parseAt(rawMax(prefs), 0)
    }
    private fun aodMax(prefs: Prefs): Int? {
        val raw = rawMax(prefs)
        return parseAt(raw, 1) ?: parseAt(raw, 0)
    }

    private fun setIntSafe(o: Any, name: String, value: Int) {
        try { XposedHelpers.setIntField(o, name, value) } catch (t: Throwable) {}
    }
    private fun setFloatSafe(o: Any, name: String, value: Float) {
        try { XposedHelpers.setFloatField(o, name, value) } catch (t: Throwable) {}
    }

    private fun iconSizeOf(v: View): Int {
        return try {
            val s = XposedHelpers.getIntField(v, "mIconSize")
            if (s > 0) s else ICON_SIZE_FALLBACK
        } catch (t: Throwable) { ICON_SIZE_FALLBACK }
    }
    private fun screenWidthOf(v: View): Int {
        return try { v.resources.displayMetrics.widthPixels } catch (t: Throwable) { 1080 }
    }
    // Широкая ширина для родителей/переполнения — чтобы N не обрезались.
    private fun fullWidth(v: View, n: Int): Int {
        val want = (n + 1) * iconSizeOf(v)
        val cap = screenWidthOf(v) * 3 / 4
        return if (want > cap) cap else want
    }
    // Тесная ширина раскладки — чтобы точка прижималась к последней иконке.
    private fun layoutWidth(v: View, n: Int): Int {
        val want = n * iconSizeOf(v) + DOT_ROOM_PX
        val cap = screenWidthOf(v) * 3 / 4
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
        val cls = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationIconContainer",
            lpparam.classLoader
        ) ?: return

        // (1) AOD/Lockscreen: лимиты контейнера = второе число.
        try {
            XposedBridge.hookAllMethods(cls, "initResources", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val max = aodMax(prefs) ?: return
                    setIntSafe(param.thisObject, "mMaxIconsOnAod", max)
                    setIntSafe(param.thisObject, "mMaxIconsOnLockscreen", max)
                }
            })
        } catch (t: Throwable) {}

        // (2) AOD (ViewModel Android 14+): третий хук из оригинала = второе число.
        try {
            val dataClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData",
                lpparam.classLoader
            )
            if (dataClass != null) {
                XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val max = aodMax(prefs) ?: return
                        param.result = max
                    }
                })
            }
        } catch (t: Throwable) {}

        // (3) Статус-бар: первое число + тесная точка.
        try {
            XposedBridge.hookAllMethods(cls, "calculateIconXTranslations", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if (!isStatusBarIcons(v)) return
                    val view = v as View
                    val max = statusBarMax(prefs)
                    if (max != null) {
                        setIntSafe(v, "mMaxStaticIcons", max)
                        setIntSafe(v, "mMaxIcons", BIND_HEADROOM)
                        widenParents(view, fullWidth(view, max))
                        setIntSafe(v, "mActualLayoutWidth", layoutWidth(view, max))
                    }
                    setIntSafe(v, "mDotPadding", DOT_PADDING_PX)
                    setFloatSafe(v, "mActualPaddingStart", 0f)
                    setFloatSafe(v, "mActualPaddingEnd", 0f)
                }
            })
        } catch (t: Throwable) {}

        // (4) Привязочный запас статус-бара.
        try {
            XposedBridge.hookAllMethods(cls, "setMaxIconsAmount", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isStatusBarIcons(param.thisObject)) return
                    if (param.args.isNotEmpty()) param.args[0] = BIND_HEADROOM
                }
            })
        } catch (t: Throwable) {}

        // (5) Переполнение статус-бара видит широкую ширину.
        try {
            XposedBridge.hookAllMethods(cls, "getActualWidth", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if (!isStatusBarIcons(v)) return
                    val view = v as View
                    val max = statusBarMax(prefs) ?: return
                    val target = fullWidth(view, max)
                    val cur = param.result as? Int ?: 0
                    if (target > cur) param.result = target
                }
            })
        } catch (t: Throwable) {}
    }
}
