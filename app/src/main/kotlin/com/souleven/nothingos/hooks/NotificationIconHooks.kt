package com.souleven.nothingos.hooks

import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import java.io.FileWriter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {

    private val DOT_PADDING_PX = 2
    private val BIND_HEADROOM = 20
    private val ICON_SIZE_FALLBACK = 66
    private val CAMERA_MARGIN = 72
    private val MIN_ICON_SIZE = 40
    @Volatile private var logCount = 0
    @Volatile private var appended = false

    private fun writeLog(line: String) {
        try {
            val fw = FileWriter("/sdcard/nothingtweaks_debug.txt", appended)
            appended = true
            fw.write(line); fw.write("\n"); fw.close()
        } catch (t: Throwable) {}
        try { XposedBridge.log("NothingTweaks: " + line) } catch (t: Throwable) {}
    }

    private fun idName(v: View): String {
        return try {
            if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "no-id"
        } catch (t: Throwable) { "res-error" }
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
        } catch (t: Throwable) { null }
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

    private fun screenWidthOf(v: View): Int {
        return try { v.resources.displayMetrics.widthPixels } catch (t: Throwable) { 1080 }
    }
    private fun iconSizeOf(v: View): Int {
        return try {
            val s = XposedHelpers.getIntField(v, "mIconSize")
            if (s > 0) s else ICON_SIZE_FALLBACK
        } catch (t: Throwable) { ICON_SIZE_FALLBACK }
    }
    // Ужимаем шаг иконки так, чтобы N иконок влезли ДО центральной камеры.
    private fun cameraSafeAdvance(v: View, n: Int): Int {
        if (n <= 0) return iconSizeOf(v)
        val orig = iconSizeOf(v)
        val cameraSafe = screenWidthOf(v) / 2 - CAMERA_MARGIN
        val fit = cameraSafe / n
        var adv = if (fit < orig) fit else orig
        if (adv < MIN_ICON_SIZE) adv = MIN_ICON_SIZE
        return adv
    }
    private fun targetWidth(v: View, n: Int): Int {
        val advance = cameraSafeAdvance(v, n)
        val want = (n + 1) * advance
        val hardCap = screenWidthOf(v) / 2 - 16
        return if (want > hardCap) hardCap else want
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

    private fun observe(cls: Class<*>, method: String) {
        try {
            XposedBridge.hookAllMethods(cls, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if (!isStatusBarIcons(v) && !isAodIcons(v)) return
                    if (logCount >= 90) return
                    logCount += 1
                    val view = v as View
                    val cc = if (v is ViewGroup) v.childCount else -1
                    writeLog("OBS " + method + " id=" + idName(view) +
                        " result=" + param.result + " childCount=" + cc)
                }
            })
        } catch (t: Throwable) { writeLog("observe " + method + " failed: " + (t.message ?: "?")) }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        writeLog("=== ENTER pkg=" + lpparam.packageName + " t=" + System.currentTimeMillis() + " ===")
        val cls = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationIconContainer",
            lpparam.classLoader
        )
        if (cls == null) { writeLog("NotificationIconContainer NOT FOUND"); return }

        // (1) AOD/Lockscreen: возврат оригинала — лимиты = N. Чинит центрирование AOD.
        try {
            XposedBridge.hookAllMethods(cls, "initResources", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    val max = readMaxIcons(prefs) ?: return
                    setIntSafe(v, "mMaxIconsOnAod", max)
                    setIntSafe(v, "mMaxIconsOnLockscreen", max)
                    if (logCount < 90) {
                        logCount += 1
                        val id = if (v is View) idName(v) else "non-view"
                        writeLog("AOD-SET id=" + id +
                            " mMaxIconsOnAod=" + getIntStr(v, "mMaxIconsOnAod") +
                            " mMaxIconsOnLockscreen=" + getIntStr(v, "mMaxIconsOnLockscreen"))
                    }
                }
            })
        } catch (t: Throwable) { writeLog("hook initResources failed: " + (t.message ?: "?")) }

        // (2) Статус-бар: поля + ужатие иконки под камеру + ширина + точка.
        try {
            XposedBridge.hookAllMethods(cls, "calculateIconXTranslations", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if (!isStatusBarIcons(v)) return
                    val view = v as View
                    val max = readMaxIcons(prefs)
                    if (max != null) {
                        setIntSafe(v, "mMaxStaticIcons", max)
                        setIntSafe(v, "mMaxIcons", BIND_HEADROOM)
                        val advance = cameraSafeAdvance(view, max)
                        if (advance < iconSizeOf(view)) setIntSafe(v, "mIconSize", advance)
                        val target = targetWidth(view, max)
                        widenParents(view, target)
                        setIntSafe(v, "mActualLayoutWidth", target)
                    }
                    setIntSafe(v, "mDotPadding", DOT_PADDING_PX)
                    setFloatSafe(v, "mActualPaddingStart", 0f)
                    if (logCount < 90 && v is ViewGroup && v.childCount >= 3) {
                        logCount += 1
                        writeLog("STATE read=" + (max?.toString() ?: "NULL") +
                            " childCount=" + v.childCount + " width=" + view.width +
                            " maxStatic=" + getIntStr(v, "mMaxStaticIcons") +
                            " maxIcons=" + getIntStr(v, "mMaxIcons") +
                            " actualLayoutW=" + getIntStr(v, "mActualLayoutWidth") +
                            " padStart=" + getFloatStr(v, "mActualPaddingStart") +
                            " dotPad=" + getIntStr(v, "mDotPadding") +
                            " iconSize=" + getIntStr(v, "mIconSize"))
                    }
                }
            })
        } catch (t: Throwable) { writeLog("hook calculateIconXTranslations failed: " + (t.message ?: "?")) }

        // (3) Привязочный запас на контейнере статус-бара (не даём ROM сбросить лимит).
        try {
            XposedBridge.hookAllMethods(cls, "setMaxIconsAmount", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isStatusBarIcons(param.thisObject)) return
                    if (param.args.isNotEmpty()) param.args[0] = BIND_HEADROOM
                }
            })
        } catch (t: Throwable) { writeLog("hook setMaxIconsAmount failed: " + (t.message ?: "?")) }

        // (4) Расчёт переполнения видит расширенную ширину.
        try {
            XposedBridge.hookAllMethods(cls, "getActualWidth", object : XC_MethodHook() {
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
        } catch (t: Throwable) { writeLog("hook getActualWidth failed: " + (t.message ?: "?")) }

        // (5) Наблюдение (лог): где решается переполнение.
        observe(cls, "areIconsOverflowing")
        observe(cls, "isOverflowing")
        observe(cls, "shouldForceOverflow")

        writeLog("=== HOOKS SET ===")
    }
}
