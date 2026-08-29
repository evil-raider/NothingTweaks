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
    private val DOT_ROOM_PX = 20
    private val BIND_HEADROOM = 20
    private val ICON_SIZE_FALLBACK = 66

    private var dotLogCount = 0

    private fun idName(v: View): String {
        return try {
            if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "no-id"
        } catch (_: Throwable) {
            "res-error"
        }
    }

    private fun isStatusBarIcons(obj: Any?): Boolean {
        return obj is View && idName(obj) == "notificationIcons"
    }

    private fun rawMax(prefs: Prefs): String {
        return try {
            prefs.getString("pref_max_notif_icons", "") ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun parseAt(raw: String, index: Int): Int? {
        val parts = raw.split(",")
        if (index >= parts.size) return null
        val value = parts[index].trim().toIntOrNull() ?: return null
        return value.takeIf { it in 1..50 }
    }

    private fun statusBarMax(prefs: Prefs): Int? = parseAt(rawMax(prefs), 0)

    private fun aodMax(prefs: Prefs): Int? {
        val raw = rawMax(prefs)
        return parseAt(raw, 1) ?: parseAt(raw, 0)
    }

    private fun setIntSafe(target: Any, field: String, value: Int) {
        try {
            XposedHelpers.setIntField(target, field, value)
        } catch (_: Throwable) {
        }
    }

    private fun setFloatSafe(target: Any, field: String, value: Float) {
        try {
            XposedHelpers.setFloatField(target, field, value)
        } catch (_: Throwable) {
        }
    }

    private fun getIntFieldSafe(target: Any, field: String): Int? {
        return try {
            XposedHelpers.getIntField(target, field)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getFloatFieldSafe(target: Any, field: String): Float? {
        return try {
            XposedHelpers.getFloatField(target, field)
        } catch (_: Throwable) {
            null
        }
    }

    private fun iconSizeOf(v: View): Int {
        return try {
            XposedHelpers.getIntField(v, "mIconSize").takeIf { it > 0 } ?: ICON_SIZE_FALLBACK
        } catch (_: Throwable) {
            ICON_SIZE_FALLBACK
        }
    }

    private fun screenWidthOf(v: View): Int {
        return try {
            v.resources.displayMetrics.widthPixels
        } catch (_: Throwable) {
            1080
        }
    }

    private fun fullWidth(v: View, n: Int): Int {
        val want = (n + 1) * iconSizeOf(v)
        val cap = screenWidthOf(v) * 3 / 4
        return minOf(want, cap)
    }

    private fun layoutWidth(v: View, n: Int): Int {
        val want = n * iconSizeOf(v) + DOT_ROOM_PX
        val cap = screenWidthOf(v) * 3 / 4
        return minOf(want, cap)
    }

    private fun nthParent(v: View, n: Int): View? {
        var parent: ViewParent? = v.parent
        var level = 1
        while (level < n) {
            parent = parent?.parent ?: return null
            level += 1
        }
        return parent as? View
    }

    private fun widenParents(v: View, target: Int) {
        for (level in 1..4) {
            val parent = nthParent(v, level) ?: continue
            try {
                val lp = parent.layoutParams ?: continue
                if (lp.width >= 0 && lp.width < target) {
                    lp.width = target
                    parent.layoutParams = lp
                }
            } catch (_: Throwable) {
            }
        }
    }

    override fun handleLoadPackage(
        lpparam: XC_LoadPackage.LoadPackageParam,
        prefs: Prefs
    ) {
        val cls = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationIconContainer",
            lpparam.classLoader
        ) ?: return

        try {
            XposedBridge.hookAllMethods(cls, "initResources", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val max = aodMax(prefs) ?: return
                    setIntSafe(param.thisObject, "mMaxIconsOnAod", max)
                    setIntSafe(param.thisObject, "mMaxIconsOnLockscreen", max)
                }
            })
        } catch (_: Throwable) {
        }

        try {
            val dataClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData",
                lpparam.classLoader
            )
            if (dataClass != null) {
                XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        aodMax(prefs)?.let { param.result = it }
                    }
                })
            }
        } catch (_: Throwable) {
        }

        try {
            XposedBridge.hookAllMethods(cls, "calculateIconXTranslations", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject
                    if (!isStatusBarIcons(container)) return

                    val view = container as View
                    val max = statusBarMax(prefs) ?: return
                    setIntSafe(container, "mMaxStaticIcons", max)
                    setIntSafe(container, "mMaxIcons", BIND_HEADROOM)
                    widenParents(view, fullWidth(view, max))
                    setIntSafe(container, "mActualLayoutWidth", layoutWidth(view, max))
                    setIntSafe(container, "mDotPadding", DOT_PADDING_PX)
                    setFloatSafe(container, "mActualPaddingStart", 0f)
                    setFloatSafe(container, "mActualPaddingEnd", 0f)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject as? ViewGroup ?: return
                    if (!isStatusBarIcons(container)) return
                    val max = statusBarMax(prefs) ?: return
                    if (container.childCount <= max) return

                    val iconSize = iconSizeOf(container)
                    val dotStart = (max * iconSize + DOT_PADDING_PX).toFloat()

                    val beforeF = getFloatFieldSafe(container, "mVisualOverflowStart")
                    val beforeI = getIntFieldSafe(container, "mVisualOverflowStart")

                    setFloatSafe(container, "mVisualOverflowStart", dotStart)
                    setIntSafe(container, "mVisualOverflowStart", dotStart.toInt())

                    if (dotLogCount < 15) {
                        dotLogCount += 1
                        XposedBridge.log(
                            "NothingTweaks dot-pin: max=$max iconSize=$iconSize " +
                                "dotStart=$dotStart beforeF=$beforeF beforeI=$beforeI " +
                                "count=${container.childCount}"
                        )
                    }

                    container.invalidate()
                }
            })
        } catch (_: Throwable) {
        }

        try {
            XposedBridge.hookAllMethods(cls, "setMaxIconsAmount", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isStatusBarIcons(param.thisObject)) return
                    if (param.args.isNotEmpty()) param.args[0] = BIND_HEADROOM
                }
            })
        } catch (_: Throwable) {
        }

        try {
            XposedBridge.hookAllMethods(cls, "getActualWidth", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject
                    if (!isStatusBarIcons(container)) return

                    val view = container as View
                    val max = statusBarMax(prefs) ?: return
                    param.result = layoutWidth(view, max)
                }
            })
        } catch (_: Throwable) {
        }
    }
}
