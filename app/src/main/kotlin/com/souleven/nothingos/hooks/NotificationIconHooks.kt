package com.souleven.nothingos.hooks

import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.FileWriter
import java.io.StringWriter
import java.io.PrintWriter

class NotificationIconHooks : HookModule {

    @Volatile private var logCount = 0
    @Volatile private var truncated = false

    private fun writeLog(line: String) {
        val append = truncated
        truncated = true
        val paths = arrayOf("/sdcard/nothingtweaks_debug.txt", "/data/local/tmp/nothingtweaks_debug.txt")
        var written = false
        var pi = 0
        while (pi < paths.size) {
            if (!written) {
                try {
                    val writer = FileWriter(paths[pi], append)
                    writer.write(line)
                    writer.write("\n")
                    writer.close()
                    written = true
                } catch (t: Throwable) {
                }
            }
            pi = pi + 1
        }
        try {
            XposedBridge.log("NothingTweaks: " + line)
        } catch (t: Throwable) {
        }
    }

    private fun stackTraceString(t: Throwable): String {
        return try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            t.printStackTrace(pw)
            sw.toString()
        } catch (t2: Throwable) {
            "no-stacktrace"
        }
    }

    private fun safeResName(view: View): String {
        return try {
            if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else "no-id"
        } catch (t: Throwable) {
            "res-error"
        }
    }

    private fun nthParentView(view: View, n: Int): View? {
        var p: ViewParent? = view.parent
        var i = 1
        while (i < n) {
            if (p == null) {
                return null
            }
            p = p.parent
            i = i + 1
        }
        if (p is View) {
            return p as View
        }
        return null
    }

    private fun setIntSafe(o: Any, name: String, value: Int) {
        try {
            XposedHelpers.setIntField(o, name, value)
        } catch (t: Throwable) {
        }
    }

    private fun setFloatSafe(o: Any, name: String, value: Float) {
        try {
            XposedHelpers.setFloatField(o, name, value)
        } catch (t: Throwable) {
        }
    }

    private fun getIntSafe(o: Any, name: String): String {
        return try {
            "" + XposedHelpers.getIntField(o, name)
        } catch (t: Throwable) {
            "NA"
        }
    }

    private fun getFloatSafe(o: Any, name: String): String {
        return try {
            "" + XposedHelpers.getFloatField(o, name)
        } catch (t: Throwable) {
            "NA"
        }
    }

    private fun getBoolSafe(o: Any, name: String): String {
        return try {
            "" + XposedHelpers.getBooleanField(o, name)
        } catch (t: Throwable) {
            "NA"
        }
    }

    private fun targetWidth(v: View): Int {
        var screen = 1080
        try {
            screen = v.resources.displayMetrics.widthPixels
        } catch (t: Throwable) {
        }
        return screen * 3 / 5
    }

    private fun widenContainer(v: View, target: Int) {
        var level = 1
        while (level <= 4) {
            val p = nthParentView(v, level)
            if (p != null) {
                try {
                    val lp = p.layoutParams
                    if (lp != null && lp.width >= 0 && lp.width < target) {
                        lp.width = target
                        p.layoutParams = lp
                    }
                } catch (t: Throwable) {
                }
            }
            level = level + 1
        }
    }

    private fun logState(v: View, target: Int) {
        val vg = v as ViewGroup
        writeLog("STATE childCount=" + vg.childCount +
            " width=" + v.width +
            " maxStatic=" + getIntSafe(v, "mMaxStaticIcons") +
            " maxIcons=" + getIntSafe(v, "mMaxIcons") +
            " maxLock=" + getIntSafe(v, "mMaxIconsOnLockscreen") +
            " actualLayoutW=" + getIntSafe(v, "mActualLayoutWidth") +
            " padStart=" + getFloatSafe(v, "mActualPaddingStart") +
            " padEnd=" + getFloatSafe(v, "mActualPaddingEnd") +
            " staticLayout=" + getBoolSafe(v, "mIsStaticLayout") +
            " onLock=" + getBoolSafe(v, "mOnLockScreen") +
            " iconSize=" + getIntSafe(v, "mIconSize") +
            " target=" + target)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        writeLog("=== ENTERED pkg=" + lpparam.packageName + " time=" + System.currentTimeMillis() + " ===")

        val cls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.NotificationIconContainer", lpparam.classLoader)
        if (cls == null) {
            writeLog("NotificationIconContainer NOT FOUND. Aborting this classloader.")
            return
        }
        writeLog("Container class found.")

        try {
            XposedBridge.hookAllMethods(cls, "setMaxIconsAmount", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if (v is View && safeResName(v) == "notificationIcons") {
                        param.args[0] = 20
                    }
                }
            })
        } catch (t: Throwable) {
            writeLog("hook setMaxIconsAmount failed: " + stackTraceString(t))
        }

        try {
            XposedBridge.hookAllMethods(cls, "calculateIconXTranslations", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if (v is View && safeResName(v) == "notificationIcons") {
                        setIntSafe(v, "mMaxStaticIcons", 20)
                        setIntSafe(v, "mMaxIcons", 20)
                        setIntSafe(v, "mMaxIconsOnLockscreen", 20)
                        setIntSafe(v, "mMaxIconsOnAod", 20)
                        setFloatSafe(v, "mActualPaddingStart", 0f)
                        val target = targetWidth(v)
                        widenContainer(v, target)
                        setIntSafe(v, "mActualLayoutWidth", target)
                        val vg = v as ViewGroup
                        if (logCount < 25 && vg.childCount >= 4) {
                            logCount = logCount + 1
                            logState(v, target)
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            writeLog("hook calculateIconXTranslations failed: " + stackTraceString(t))
        }

        try {
            XposedBridge.hookAllMethods(cls, "getActualWidth", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if (v is View && safeResName(v) == "notificationIcons") {
                        val result = param.result
                        val cur = if (result is Int) result else 0
                        val target = targetWidth(v)
                        if (target > cur) {
                            param.result = target
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            writeLog("hook getActualWidth failed: " + stackTraceString(t))
        }

        try {
            val dataClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData", lpparam.classLoader)
            if (dataClass != null) {
                XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = 20
                    }
                })
            }
        } catch (t: Throwable) {
            writeLog("hook getIconLimit failed: " + stackTraceString(t))
        }

        writeLog("=== FINISHED pkg=" + lpparam.packageName + " ===")
    }
}
