package com.souleven.nothingos.hooks

import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter

class NotificationIconHooks : HookModule {

    @Volatile private var logCount = 0

    private fun writeLog(line: String) {
        val paths = arrayOf("/sdcard/nothingtweaks_debug.txt", "/data/local/tmp/nothingtweaks_debug.txt")
        var written = false
        var pi = 0
        while (pi < paths.size) {
            if (!written) {
                try {
                    val writer = FileWriter(paths[pi], true)
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

    private fun logAncestors(v: View) {
        var p: ViewParent? = v.parent
        var level = 1
        while (level <= 6) {
            if (p == null) {
                level = 7
            } else {
                if (p is View) {
                    val pv = p as View
                    writeLog("  ANCESTOR[" + level + "] class=" + pv.javaClass.name + " id=" + safeResName(pv) + " left=" + pv.left + " right=" + pv.right + " width=" + pv.width)
                } else {
                    writeLog("  ANCESTOR[" + level + "] non-view: " + p.javaClass.name)
                }
                p = p.parent
                level = level + 1
            }
        }
    }

    private fun computeTargetWidth(v: View, current: Int): Int {
        var target = current
        val p2 = nthParentView(v, 2)
        if (p2 != null && p2.width > target) {
            target = p2.width
        }
        val p3 = nthParentView(v, 3)
        if (p3 != null && p3.width > target) {
            target = p3.width
        }
        try {
            val screen = v.resources.displayMetrics.widthPixels
            val cap = screen * 2 / 3
            if (target > cap) {
                target = cap
            }
        } catch (t: Throwable) {
        }
        return target
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        writeLog("=== handleLoadPackage ENTERED pkg=" + lpparam.packageName + " time=" + System.currentTimeMillis() + " ===")

        val cls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.NotificationIconContainer", lpparam.classLoader)
        if (cls == null) {
            writeLog("NotificationIconContainer NOT FOUND. Aborting.")
            return
        }
        writeLog("Container class found.")

        try {
            XposedBridge.hookAllMethods(cls, "setMaxIconsAmount", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if (v is View && safeResName(v) == "notificationIcons") {
                        writeLog("setMaxIconsAmount origArg0=" + param.args[0] + " -> forcing 20")
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
                        try {
                            XposedHelpers.setIntField(v, "mMaxStaticIcons", 20)
                            XposedHelpers.setIntField(v, "mMaxIcons", 20)
                        } catch (t: Throwable) {
                        }
                        val target = computeTargetWidth(v, v.width)
                        try {
                            XposedHelpers.setIntField(v, "mActualLayoutWidth", target)
                        } catch (t: Throwable) {
                        }
                        if (logCount < 30) {
                            logCount = logCount + 1
                            writeLog("calculateIconXTranslations notificationIcons childCount=" + (v as ViewGroup).childCount + " width=" + v.width + " forcedActualWidth=" + target)
                            logAncestors(v)
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
                        val target = computeTargetWidth(v, cur)
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

        writeLog("=== handleLoadPackage FINISHED pkg=" + lpparam.packageName + " ===")
    }
}
