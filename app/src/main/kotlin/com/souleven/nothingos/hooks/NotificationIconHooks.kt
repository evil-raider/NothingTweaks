package com.souleven.nothingos.hooks

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter

class NotificationIconHooks : HookModule {

    @Volatile private var widthLogCount = 0
    @Volatile private var xlateLogCount = 0

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

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        writeLog("=== handleLoadPackage ENTERED pkg=" + lpparam.packageName + " time=" + System.currentTimeMillis() + " ===")

        try {
            val candidateNames = arrayOf(
                "com.android.systemui.statusbar.phone.NotificationIconContainer",
                "com.android.systemui.statusbar.notification.NotificationIconContainer",
                "com.android.systemui.statusbar.notification.icon.NotificationIconContainer"
            )

            var containerClass: Class<*>? = null
            var foundName = "none"
            var i = 0
            while (i < candidateNames.size) {
                val name = candidateNames[i]
                var found: Class<*>? = null
                try {
                    found = XposedHelpers.findClassIfExists(name, lpparam.classLoader)
                } catch (t: Throwable) {
                    writeLog("Error checking class " + name + ": " + stackTraceString(t))
                }
                writeLog("Candidate class " + name + " found=" + (found != null))
                if (found != null && containerClass == null) {
                    containerClass = found
                    foundName = name
                }
                i = i + 1
            }

            if (containerClass == null) {
                writeLog("RESULT: NotificationIconContainer NOT FOUND under any candidate name. No icon hooks installed.")
            } else {
                val cls = containerClass!!
                writeLog("RESULT: using container class " + foundName)

                try {
                    val methods = cls.declaredMethods
                    writeLog("Declared method count: " + methods.size)
                    var mi = 0
                    while (mi < methods.size) {
                        val m = methods[mi]
                        writeLog("  METHOD " + mi + ": " + m.name + " paramCount=" + m.parameterTypes.size + " returns=" + m.returnType.simpleName)
                        mi = mi + 1
                    }
                } catch (t: Throwable) {
                    writeLog("Failed listing methods: " + stackTraceString(t))
                }

                try {
                    val fields = cls.declaredFields
                    writeLog("Declared field count: " + fields.size)
                    var fi = 0
                    while (fi < fields.size) {
                        val f = fields[fi]
                        writeLog("  FIELD " + fi + ": " + f.name + " type=" + f.type.simpleName)
                        fi = fi + 1
                    }
                } catch (t: Throwable) {
                    writeLog("Failed listing fields: " + stackTraceString(t))
                }

                var superCls = cls.superclass
                var depth = 0
                while (superCls != null && depth < 5) {
                    writeLog("Superclass[" + depth + "]: " + superCls!!.name)
                    superCls = superCls!!.superclass
                    depth = depth + 1
                }

                try {
                    val unhooks1 = XposedBridge.hookAllMethods(cls, "setMaxIconsAmount", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            writeLog("FIRED setMaxIconsAmount origArg0=" + param.args[0])
                            val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                            val maxIcons = maxIconsStr.toIntOrNull()
                            if (maxIcons != null) {
                                param.args[0] = maxIcons
                                writeLog("  overrode to " + maxIcons)
                            }
                        }
                    })
                    writeLog("Install setMaxIconsAmount -> unhooks=" + unhooks1.size)
                } catch (t: Throwable) {
                    writeLog("Install FAILED setMaxIconsAmount: " + stackTraceString(t))
                }

                try {
                    val unhooks2 = XposedBridge.hookAllMethods(cls, "initResources", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            writeLog("FIRED initResources")
                            val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                            val maxIcons = maxIconsStr.toIntOrNull()
                            if (maxIcons != null) {
                                try {
                                    XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnAod", maxIcons)
                                    XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnLockscreen", maxIcons)
                                    writeLog("  set mMaxIconsOnAod/mMaxIconsOnLockscreen=" + maxIcons)
                                } catch (t: Throwable) {
                                    writeLog("  field set failed: " + stackTraceString(t))
                                }
                            }
                        }
                    })
                    writeLog("Install initResources -> unhooks=" + unhooks2.size)
                } catch (t: Throwable) {
                    writeLog("Install FAILED initResources: " + stackTraceString(t))
                }

                try {
                    val unhooks3 = XposedBridge.hookAllMethods(cls, "getActualWidth", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (widthLogCount < 40) {
                                widthLogCount = widthLogCount + 1
                                val view = param.thisObject
                                var line = "FIRED getActualWidth #" + widthLogCount + " result=" + param.result
                                try {
                                    val v = view as View
                                    line = line + " class=" + v.javaClass.name + " id=" + safeResName(v) + " left=" + v.left + " right=" + v.right + " width=" + v.width
                                } catch (t: Throwable) {
                                    line = line + " (view cast failed: " + t.message + ")"
                                }
                                writeLog(line)

                                val result = param.result
                                if (result is Int && result == 0) {
                                    try {
                                        val realWidth = (view as View).width
                                        if (realWidth > 0) {
                                            param.result = realWidth
                                            writeLog("  patched zero width -> " + realWidth)
                                        }
                                    } catch (t: Throwable) {
                                    }
                                }
                            }
                        }
                    })
                    writeLog("Install getActualWidth -> unhooks=" + unhooks3.size)
                } catch (t: Throwable) {
                    writeLog("Install FAILED getActualWidth: " + stackTraceString(t))
                }

                try {
                    val unhooks4 = XposedBridge.hookAllMethods(cls, "calculateIconXTranslations", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (xlateLogCount < 40) {
                                xlateLogCount = xlateLogCount + 1
                                val view = param.thisObject
                                var line = "FIRED calculateIconXTranslations #" + xlateLogCount
                                try {
                                    val v = view as ViewGroup
                                    line = line + " class=" + v.javaClass.name + " id=" + safeResName(v) + " childCount=" + v.childCount + " left=" + v.left + " right=" + v.right + " width=" + v.width

                                    val parentView = v.parent
                                    if (parentView is View) {
                                        line = line + " | parent=" + parentView.javaClass.name + " parentId=" + safeResName(parentView) + " parentWidth=" + parentView.width
                                    }
                                } catch (t: Throwable) {
                                    line = line + " (inspect failed: " + t.message + ")"
                                }
                                writeLog(line)
                            }
                        }
                    })
                    writeLog("Install calculateIconXTranslations -> unhooks=" + unhooks4.size)
                } catch (t: Throwable) {
                    writeLog("Install FAILED calculateIconXTranslations: " + stackTraceString(t))
                }
            }
        } catch (t: Throwable) {
            writeLog("TOP LEVEL ERROR: " + stackTraceString(t))
        }

        try {
            val dataClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData", lpparam.classLoader)
            writeLog("NotificationIconsViewData found=" + (dataClass != null))
            if (dataClass != null) {
                try {
                    val unhooks5 = XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            writeLog("FIRED getIconLimit")
                            val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                            val maxIcons = maxIconsStr.toIntOrNull()
                            if (maxIcons != null) {
                                param.result = maxIcons
                            }
                        }
                    })
                    writeLog("Install getIconLimit -> unhooks=" + unhooks5.size)
                } catch (t: Throwable) {
                    writeLog("Install FAILED getIconLimit: " + stackTraceString(t))
                }
            }
        } catch (t: Throwable) {
            writeLog("ERROR checking NotificationIconsViewData: " + stackTraceString(t))
        }

        writeLog("=== handleLoadPackage FINISHED pkg=" + lpparam.packageName + " ===")
    }
}
