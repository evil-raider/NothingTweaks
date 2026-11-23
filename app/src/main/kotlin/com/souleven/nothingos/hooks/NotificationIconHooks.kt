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

    private fun isAodIcons(obj: Any?): Boolean {
        if (obj !is View) return false
        val logTag = try {
            XposedHelpers.getObjectField(obj, "mLogTag") as? String
        } catch (_: Throwable) {
            null
        }
        if ("aod".equals(logTag, ignoreCase = true)) return true
        return idName(obj) == "aod_notification_icon_container"
    }

    private fun rawMax(prefs: Prefs): String {
        return try {
            prefs.getString("pref_max_notif_icons", "") ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun statusBarMax(prefs: Prefs): Int? = NotificationIconLimits.statusBar(rawMax(prefs))

    private fun aodMax(prefs: Prefs): Int? = NotificationIconLimits.aod(rawMax(prefs))

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
            if (st != null) {
                var set = false
                try {
                    XposedHelpers.callMethod(st, "setXTranslation", targetX)
                    set = true
                } catch (_: Throwable) {
                }
                if (!set) {
                    setFloatSafe(st, "mXTranslation", targetX)
                    setFloatSafe(st, "xTranslation", targetX)
                }
            }
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
                        if (dotChild == null) {
                            dotChild = child
                        } else {
                            setChildState(container, child, STATE_HIDDEN)
                        }
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
                    // +1 слот под точку переполнения: заданное число = число иконок,
                    // точка добавляется сверху (как в статусбаре, mMaxStaticIcons = max + 1).
                    val slots = max + 1
                    setIntSafe(param.thisObject, "mMaxIconsOnAod", slots)
                    setIntSafe(param.thisObject, "mMaxIconsOnLockscreen", slots)
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
                        // +1 слот под точку переполнения, чтобы AOD показывал "N иконок + точка"
                        // как статусбар (иначе выходит "N-1 иконок + точка").
                        aodMax(prefs)?.let { param.result = it + 1 }
                    }
                })
            }
        } catch (_: Throwable) {
        }

        try {
            val aodVmClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel",
                lpparam.classLoader
            )
            if (aodVmClass != null && XposedHelpers.findFieldIfExists(aodVmClass, "maxIcons") != null) {
                XposedBridge.hookAllConstructors(aodVmClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val max = aodMax(prefs) ?: return
                        setIntSafe(param.thisObject, "maxIcons", max + 1)
                    }
                })
            }
        } catch (_: Throwable) {
        }

        // Android 17 / Nothing OS 5: calculateIconXTranslations() для AOD вызывает
        // setActualLayoutWidth(ceil(pad + R.integer.max_notif_icons_on_aod * mIconSize * scale)),
        // где max_notif_icons_on_aod = 5 (4 иконки + точка). Подменяем на aodMax + 1 слотов.
        try {
            XposedBridge.hookAllMethods(cls, "setActualLayoutWidth", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val max = aodMax(prefs) ?: return
                    val container = param.thisObject as? ViewGroup ?: return
                    if (!isAodIcons(container)) return
                    val iconSize = try {
                        XposedHelpers.getIntField(container, "mIconSize")
                    } catch (_: Throwable) {
                        0
                    }
                    if (iconSize <= 0) return

                    val slots = max + 1
                    val padStart = (XposedHelpers.callMethod(container, "getActualPaddingStart") as? Float) ?: 0f
                    val padEnd = (XposedHelpers.callMethod(container, "getActualPaddingEnd") as? Float) ?: 0f
                    val drawingScale = if (container.childCount > 0) {
                        (XposedHelpers.callMethod(container, "getDrawingScale", container.getChildAt(0)) as? Float) ?: 1.0f
                    } else {
                        1.0f
                    }
                    val desiredWidth = kotlin.math.ceil(
                        (padStart + padEnd + (slots * iconSize * drawingScale)).toDouble()
                    ).toInt()
                    val viewWidth = container.width
                    param.args[0] = if (viewWidth > 0) minOf(viewWidth, desiredWidth) else desiredWidth
                }
            })
        } catch (_: Throwable) {
        }

        val flagsClass = XposedHelpers.findClassIfExists("com.android.systemui.Flags", lpparam.classLoader)

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

                // Android 17 / Nothing OS 5: для центрированных часов на AOD захардкожено `i3 <= 5`,
                // что обрезает раскладку до 4 иконок + точка. Пересчитываем под aodMax + 1 слотов.
                override fun afterHookedMethod(param: MethodHookParam) {
                    val max = aodMax(prefs) ?: return
                    val slots = max + 1
                    if (slots <= 5) return
                    val container = param.thisObject as? ViewGroup ?: return
                    if (!isAodIcons(container)) return
                    if (container.childCount <= 5 || container.width <= 0) return

                    val firstVisible = try {
                        XposedHelpers.getObjectField(container, "mFirstVisibleIconState")
                    } catch (_: Throwable) {
                        null
                    }
                    val padStart = (XposedHelpers.callMethod(container, "getActualPaddingStart") as? Float) ?: 0f
                    val firstX = if (firstVisible != null) {
                        (XposedHelpers.callMethod(firstVisible, "getXTranslation") as? Float) ?: padStart
                    } else {
                        padStart
                    }
                    val isRtl = container.layoutDirection == View.LAYOUT_DIRECTION_RTL
                    val isCentered = if (!isRtl) {
                        firstX > padStart + 1f
                    } else {
                        val firstChildW = container.getChildAt(0)?.width ?: 0
                        firstX < (container.width - padStart - firstChildW) - 1f
                    }
                    if (!isCentered) return

                    recalculateCenteredAod(container, slots, flagsClass)
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
                    if (param.args.isEmpty()) return
                    if (isStatusBarIcons(param.thisObject)) {
                        param.args[0] = BIND_HEADROOM
                    } else if (isAodIcons(param.thisObject)) {
                        aodMax(prefs)?.let { param.args[0] = it + 1 }
                    }
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

    private fun recalculateCenteredAod(container: ViewGroup, slots: Int, flagsClass: Class<*>?) {
        try {
            @Suppress("UNCHECKED_CAST")
            val iconStates = XposedHelpers.getObjectField(container, "mIconStates") as? HashMap<View, Any> ?: return
            val childCount = container.childCount
            val width = container.width
            val iconSize = XposedHelpers.getIntField(container, "mIconSize")
            if (iconSize <= 0) return

            val padStart = XposedHelpers.callMethod(container, "getActualPaddingStart") as Float
            val padEnd = XposedHelpers.callMethod(container, "getActualPaddingEnd") as Float
            val maxContentWidth = (width - padStart - padEnd).coerceAtLeast(0f)

            var totalWidth = 0f
            var visibleCount = 0
            for (i in 0 until childCount) {
                val child = container.getChildAt(i)
                val state = iconStates[child] ?: continue
                val hidden = XposedHelpers.getBooleanField(state, "hidden")
                if (!hidden) visibleCount++
                if (visibleCount <= slots) {
                    val appear = XposedHelpers.getFloatField(state, "iconAppearAmount")
                    val scale = XposedHelpers.callMethod(container, "getDrawingScale", child) as Float
                    val step = appear * child.width * scale
                    if (totalWidth + step <= maxContentWidth + 0.5f) {
                        totalWidth += step
                    }
                }
            }

            var x = ((width - totalWidth) / 2f).coerceAtLeast(padStart)
            val rightBound = x + totalWidth + 0.5f

            val overrideIconColor = XposedHelpers.getBooleanField(container, "mOverrideIconColor")
            var firstOverflowIndex = -1
            var visualOverflowStart = 0f
            var wasOverflowForced = false
            var isShowingOverflowDot = false
            var firstVisibleState: Any? = null
            var lastVisibleState: Any? = null

            for (i in 0 until childCount) {
                val child = container.getChildAt(i)
                val state = iconStates[child] ?: continue
                val appear = XposedHelpers.getFloatField(state, "iconAppearAmount")
                if (appear == 1.0f) {
                    XposedHelpers.callMethod(state, "setXTranslation", x)
                }
                if (firstVisibleState == null) {
                    firstVisibleState = state
                }
                val hidden = XposedHelpers.getBooleanField(state, "hidden")
                XposedHelpers.setIntField(state, "visibleState", if (hidden) 2 else 0)

                val forced = XposedHelpers.callMethod(container, "shouldForceOverflow", i, appear, slots) as Boolean
                val isLast = i == childCount - 1
                val overflowing = (forced || (XposedHelpers.callMethod(
                    container, "isOverflowing", isLast, x, rightBound, iconSize.toFloat()
                ) as Boolean)) && appear > 0f

                if (firstOverflowIndex == -1 && overflowing) {
                    wasOverflowForced = forced
                    if (overrideIconColor) {
                        firstOverflowIndex = i
                        visualOverflowStart = x
                    } else {
                        val idx = if (i > 0) i - 1 else i
                        firstOverflowIndex = idx
                        val prevState = iconStates[container.getChildAt(idx)]
                        visualOverflowStart = if (prevState != null) {
                            XposedHelpers.callMethod(prevState, "getXTranslation") as Float
                        } else {
                            x
                        }
                    }
                }

                val scale = XposedHelpers.callMethod(container, "getDrawingScale", child) as Float
                x += appear * child.width * scale
            }

            val physicalMovement = try {
                flagsClass != null && (XposedHelpers.callStaticMethod(flagsClass, "physicalNotificationMovement") as Boolean)
            } catch (_: Throwable) {
                false
            }

            if (firstOverflowIndex != -1) {
                var dotX = visualOverflowStart
                val dotStep = XposedHelpers.getIntField(container, "mStaticDotDiameter") +
                    XposedHelpers.getIntField(container, "mDotPadding")
                for (i in firstOverflowIndex until childCount) {
                    val state = iconStates[container.getChildAt(i)] ?: continue
                    val appear = XposedHelpers.getFloatField(state, "iconAppearAmount")
                    XposedHelpers.callMethod(state, "setXTranslation", dotX)
                    val isLast = i == childCount - 1
                    if (!isShowingOverflowDot) {
                        if (appear < 0.8f && (isLast || !physicalMovement)) {
                            XposedHelpers.setIntField(state, "visibleState", 0)
                        } else {
                            XposedHelpers.setIntField(state, "visibleState", if (isLast || !physicalMovement) 1 else 2)
                            isShowingOverflowDot = true
                        }
                        if (!physicalMovement) {
                            dotX += dotStep * appear
                        }
                        lastVisibleState = state
                    } else {
                        XposedHelpers.setIntField(state, "visibleState", if (isLast && physicalMovement) 1 else 2)
                    }
                }
            } else if (childCount > 0) {
                lastVisibleState = iconStates[container.getChildAt(childCount - 1)]
                firstVisibleState = iconStates[container.getChildAt(0)]
            }

            if (container.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                for (i in 0 until childCount) {
                    val child = container.getChildAt(i)
                    val state = iconStates[child] ?: continue
                    val rtlX = XposedHelpers.callMethod(container, "getRtlIconTranslationX", state, child) as Float
                    XposedHelpers.callMethod(state, "setXTranslation", rtlX)
                }
            }

            XposedHelpers.setIntField(container, "mFirstOverflowIndex", firstOverflowIndex)
            XposedHelpers.setBooleanField(container, "mIsShowingOverflowDot", isShowingOverflowDot)
            XposedHelpers.setBooleanField(container, "mWasOverflowForced", wasOverflowForced)
            XposedHelpers.setFloatField(container, "mVisualOverflowStart", visualOverflowStart)
            XposedHelpers.setObjectField(container, "mFirstVisibleIconState", firstVisibleState)
            XposedHelpers.setObjectField(container, "mLastVisibleIconState", lastVisibleState)
        } catch (_: Throwable) {
        }
    }
}
