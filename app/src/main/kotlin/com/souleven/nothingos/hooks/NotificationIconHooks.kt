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
    private val DOT_GAP_PX = 12          // зазор точки от края последней иконки; 0 = вплотную, меньше/отрицательное = ближе
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

    // Находит точку (STATE_DOT = 1) и ставит её вплотную за последней иконкой.
    // Пишем и в IconState.xTranslation, и в саму view. Вызывается из нескольких
    // хуков; побеждает тот, что отрабатывает последним перед отрисовкой.
    private fun pinDot(container: ViewGroup, prefs: Prefs, tag: String) {
        val max = statusBarMax(prefs) ?: return
        val iconSize = iconSizeOf(container)
        val targetX = max * iconSize - iconSize / 2f + DOT_GAP_PX
        val n = container.childCount
        for (i in 0 until n) {
            val child = container.getChildAt(i) ?: continue
            val state = try {
                (XposedHelpers.callMethod(child, "getVisibleState") as? Int) ?: -1
            } catch (_: Throwable) {
                -1
            }
            if (state == 1) {
                try {
                    val states = XposedHelpers.getObjectField(container, "mIconStates")
                    val st = if (states != null) {
                        XposedHelpers.callMethod(states, "get", child)
                    } else {
                        null
                    }
                    if (st != null) setFloatSafe(st, "xTranslation", targetX)
                } catch (_: Throwable) {
                }
                try {
                    child.translationX = targetX
                } catch (_: Throwable) {
                }
                if (dotLogCount < 12) {
                    dotLogCount += 1
                    XposedBridge.log(
                        "NothingTweaks dot4[$tag]: targetX=${targetX.toInt()} " +
                            "dotX=${child.translationX.toInt()} idx=$i"
                    )
                }
                return
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
                    // ROM трактует mMaxStaticIcons как число слотов ВКЛЮЧАЯ слот под точку,
                    // поэтому для N иконок + точка нужно N+1 слотов.
                    val slots = max + 1
                    setIntSafe(container, "mMaxStaticIcons", slots)
                    setIntSafe(container, "mMaxIcons", BIND_HEADROOM)
                    widenParents(view, fullWidth(view, slots))
                    setIntSafe(container, "mActualLayoutWidth", layoutWidth(view, slots))
                    setIntSafe(container, "mDotPadding", DOT_PADDING_PX)
                    setFloatSafe(container, "mActualPaddingStart", 0f)
                    setFloatSafe(container, "mActualPaddingEnd", 0f)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject as? ViewGroup ?: return
                    if (!isStatusBarIcons(container)) return
                    pinDot(container, prefs, "calc")
                }
            })
        } catch (_: Throwable) {
        }

        // Ключевой хук: applyIconStates отрабатывает ПОСЛЕ расчёта и перезаписывает
        // translationX. Пишем здесь последними, чтобы точка осталась на месте.
        try {
            XposedBridge.hookAllMethods(cls, "applyIconStates", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject as? ViewGroup ?: return
                    if (!isStatusBarIcons(container)) return
                    pinDot(container, prefs, "apply")
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
                    // Ширина с запасом на N+1 слот, чтобы точку не срезало по краю.
                    param.result = layoutWidth(view, max + 1)
                }
            })
        } catch (_: Throwable) {
        }
    }
}
