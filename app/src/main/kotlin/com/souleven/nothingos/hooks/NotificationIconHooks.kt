package com.souleven.nothingos.hooks

import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference

/**
 * Управляет иконками уведомлений в статусбаре:
 *  - лимит иконок берётся из pref "pref_max_notif_icons" в формате "статусбар,аод";
 *  - показывает не более N иконок, (N+1)-ю превращает в точку переполнения,
 *    остальные прячет;
 *  - точку прижимает вплотную к последней иконке и удерживает её пер-кадрово.
 *
 * ХРУПКИЕ ЗАВИСИМОСТИ (могут отвалиться при обновлении ROM/AOSP; всё в try/catch,
 * поэтому поломка проявится как «твик молча перестал работать»):
 *  - класс com.android.systemui.statusbar.phone.NotificationIconContainer;
 *  - id контейнера "notificationIcons";
 *  - поля mIconStates / mMaxStaticIcons / mMaxIcons / mActualLayoutWidth /
 *    mActualPaddingStart / mIconSize;
 *  - метод StatusBarIconView.getVisibleState()/setVisibleState().
 */
class NotificationIconHooks : HookModule {

    private val DOT_ROOM_PX = 20
    private val DOT_GAP_PX = 14         // зазор точки от края последней иконки; меньше = ближе, отрицательное = ещё ближе
    private val BIND_HEADROOM = 20
    private val ICON_SIZE_FALLBACK = 66

    private val STATE_ICON = 0
    private val STATE_DOT = 1
    private val STATE_HIDDEN = 2

    private var enforcing = false
    private val lifecycleHooked = java.util.WeakHashMap<View, Boolean>()

    // Кэш точки для дешёвого пер-кадрового удержания (без рефлексии/Prefs в горячем пути).
    private var dotRef: WeakReference<View>? = null
    private var dotTargetX: Float = 0f

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

    private fun visibleStateOf(child: View): Int {
        return try {
            (XposedHelpers.callMethod(child, "getVisibleState") as? Int) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    // Ставит состояние иконки (icon/dot/hidden) максимально надёжно под разные ROM.
    private fun setChildState(container: ViewGroup, child: View, state: Int) {
        var ok = false
        try {
            XposedHelpers.callMethod(child, "setVisibleState", state, false)
            ok = true
        } catch (_: Throwable) {
        }
        if (!ok) {
            try {
                XposedHelpers.callMethod(child, "setVisibleState", state)
                ok = true
            } catch (_: Throwable) {
            }
        }
        if (!ok) {
            try {
                XposedHelpers.setIntField(child, "mVisibleState", state)
            } catch (_: Throwable) {
            }
        }
        try {
            val states = XposedHelpers.getObjectField(container, "mIconStates")
            val st = if (states != null) XposedHelpers.callMethod(states, "get", child) else null
            if (st != null) setIntSafe(st, "visibleState", state)
        } catch (_: Throwable) {
        }
    }

    private fun positionDot(container: ViewGroup, child: View, targetX: Float) {
        try {
            val states = XposedHelpers.getObjectField(container, "mIconStates")
            val st = if (states != null) XposedHelpers.callMethod(states, "get", child) else null
            if (st != null) setFloatSafe(st, "xTranslation", targetX)
        } catch (_: Throwable) {
        }
        if (child.translationX != targetX) {
            try {
                child.translationX = targetX
            } catch (_: Throwable) {
            }
        }
    }

    // Держит видимыми не более max иконок: лишнюю превращает в точку, остальные прячет.
    // Запускается на layout-частоте (applyIconStates), не пер-кадрово.
    private fun enforceMax(container: ViewGroup, prefs: Prefs) {
        if (enforcing) return
        enforcing = true
        try {
            val max = statusBarMax(prefs) ?: return
            val iconSize = iconSizeOf(container)
            val targetX = max * iconSize - iconSize / 2f + DOT_GAP_PX
            val n = container.childCount
            var shown = 0
            var dotChild: View? = null

            for (i in 0 until n) {
                val child = container.getChildAt(i) ?: continue
                when (visibleStateOf(child)) {
                    STATE_ICON -> {
                        if (shown < max) {
                            shown += 1
                        } else if (dotChild == null) {
                            setChildState(container, child, STATE_DOT)
                            dotChild = child
                        } else {
                            setChildState(container, child, STATE_HIDDEN)
                        }
                    }
                    STATE_DOT -> {
                        dotChild = child
                    }
                }
            }

            val dc = dotChild
            if (dc != null) {
                positionDot(container, dc, targetX)
                dotRef = WeakReference(dc)
                dotTargetX = targetX
            } else {
                dotRef = null
            }
        } finally {
            enforcing = false
        }
    }

    // Пер-кадровый якорь: O(1), без рефлексии/Prefs, пока точка не сдвинулась.
    private fun holdDot() {
        val child = dotRef?.get() ?: return
        val target = dotTargetX
        if (child.translationX != target && visibleStateOf(child) == STATE_DOT) {
            try {
                child.translationX = target
            } catch (_: Throwable) {
            }
        }
    }

    // Регистрирует пер-кадровый слушатель с привязкой к жизненному циклу view:
    // переживает detach/reattach (смена темы, рестарт SystemUI) без двойной подписки.
    private fun ensurePreDraw(container: ViewGroup) {
        if (lifecycleHooked.containsKey(container)) return
        lifecycleHooked[container] = true

        val predraw = ViewTreeObserver.OnPreDrawListener {
            try {
                holdDot()
            } catch (_: Throwable) {
            }
            true
        }

        try {
            if (container.isAttachedToWindow) {
                container.viewTreeObserver.addOnPreDrawListener(predraw)
            }
        } catch (_: Throwable) {
        }

        try {
            container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    try {
                        v.viewTreeObserver.addOnPreDrawListener(predraw)
                    } catch (_: Throwable) {
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {
                    try {
                        v.viewTreeObserver.removeOnPreDrawListener(predraw)
                    } catch (_: Throwable) {
                    }
                }
            })
        } catch (_: Throwable) {
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
                    val slots = max + 1
                    // maxStatic=max+1 даёт родные "max иконок + точка" при большом числе уведомлений.
                    setIntSafe(container, "mMaxStaticIcons", slots)
                    // Достаточный headroom, чтобы (max+1)-я view существовала и её можно было
                    // превратить в точку на границе.
                    setIntSafe(container, "mMaxIcons", BIND_HEADROOM)
                    // Щедрая ширина: ROM раскладывает все иконки, лишнюю в точку превращаем сами.
                    widenParents(view, fullWidth(view, slots))
                    setIntSafe(container, "mActualLayoutWidth", layoutWidth(view, slots))
                    // Иконки должны начинаться с x=0, иначе ломается расчёт targetX точки.
                    setFloatSafe(container, "mActualPaddingStart", 0f)
                    setFloatSafe(container, "mActualPaddingEnd", 0f)
                }
            })
        } catch (_: Throwable) {
        }

        try {
            XposedBridge.hookAllMethods(cls, "applyIconStates", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject as? ViewGroup ?: return
                    if (!isStatusBarIcons(container)) return
                    ensurePreDraw(container)
                    // Состояния уже применены к view — getVisibleState() актуален.
                    enforceMax(container, prefs)
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
                    param.result = layoutWidth(view, max + 1)
                }
            })
        } catch (_: Throwable) {
        }
    }
}
