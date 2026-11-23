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
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Управляет иконками уведомлений в статусбаре и на AOD:
 *  - лимит иконок берётся из pref "pref_max_notif_icons" в формате "статусбар,аод";
 *  - в статусбаре показывает не более N иконок, (N+1)-ю превращает в точку переполнения,
 *    остальные прячет, при необходимости сжимая шаг иконок до выреза камеры;
 *  - на AOD снимает ограничение Android 17 / Nothing OS 5 (max_notif_icons_on_aod = 5)
 *    как для обычных, так и для центрированных часов.
 */
class NotificationIconHooks : HookModule {

    private val DOT_ROOM_PX = 20
    private val DOT_GAP_PX = 14         // зазор точки от края последней иконки (без сжатия)
    private val MIN_BIND_HEADROOM = 20
    private val ICON_SIZE_FALLBACK = 66
    private val DEFAULT_AOD_SLOTS = 5

    private val STATE_ICON = 0
    private val STATE_DOT = 1
    private val STATE_HIDDEN = 2

    private var enforcing = false
    private val lifecycleHooked = java.util.WeakHashMap<View, Boolean>()

    // [P3] Кэш целочисленных resource ID контейнеров (0 = ещё не инициализирован, -1 = не найден).
    @Volatile
    private var notifIconsResId = 0
    @Volatile
    private var aodIconsResId = 0

    // [P4] Кэш распарсенного значения настройки "pref_max_notif_icons", чтобы не делать split/toIntOrNull
    // по 5 раз за один layout-проход.
    @Volatile
    private var cachedRawPref: String? = null
    @Volatile
    private var cachedSbMax: Int? = null
    @Volatile
    private var cachedAodMax: Int? = null

    // [R1] Переиспользуемые буферы без аллокаций на каждом applyIconStates / OnPreDraw.
    private val tmpLoc = IntArray(2)
    private val tmpShownIcons = ArrayList<View>(32)
    private var iconRefs: Array<WeakReference<View>?> = arrayOfNulls(16)
    private var iconTargetsX: FloatArray = FloatArray(16)
    private var compactedIconCount: Int = 0

    // Кэш точки для дешёвого пер-кадрового удержания (без рефлексии/Prefs в горячем пути).
    private var dotRef: WeakReference<View>? = null
    private var dotTargetX: Float = 0f

    // [P1, P2] Закэшированные Field / Method для NotificationIconContainer, StatusBarIconView и IconState.
    private var fLogTag: Field? = null
    private var fIconSize: Field? = null
    private var fIconStates: Field? = null
    private var fMaxStaticIcons: Field? = null
    private var fMaxIcons: Field? = null
    private var fMaxIconsOnAod: Field? = null
    private var fMaxIconsOnLockscreen: Field? = null
    private var fActualLayoutWidth: Field? = null
    private var fActualPaddingStart: Field? = null
    private var fActualPaddingEnd: Field? = null
    private var fFirstOverflowIndex: Field? = null
    private var fIsShowingOverflowDot: Field? = null
    private var fWasOverflowForced: Field? = null
    private var fVisualOverflowStart: Field? = null
    private var fFirstVisibleIconState: Field? = null
    private var fLastVisibleIconState: Field? = null
    private var fOverrideIconColor: Field? = null
    private var fStaticDotDiameter: Field? = null
    private var fDotPadding: Field? = null

    private var mGetActualPaddingStart: Method? = null
    private var mGetActualPaddingEnd: Method? = null
    private var mGetDrawingScale: Method? = null
    private var mShouldForceOverflow: Method? = null
    private var mIsOverflowing: Method? = null
    private var mGetRtlIconTranslationX: Method? = null
    private var mPhysicalNotificationMovement: Method? = null

    // Кэш для StatusBarIconView (инициализируется лениво по первому экземпляру child).
    @Volatile
    private var sbivResolved = false
    private var mSbivGetVisibleState: Method? = null
    private var mSbivSetVisibleState2: Method? = null
    private var mSbivSetVisibleState1: Method? = null
    private var fSbivVisibleState: Field? = null
    private var fSbivDrawingSize: Field? = null
    private var fSbivNewIconSize: Field? = null
    private var fSbivStaticDotRadius: Field? = null

    // Кэш для NotificationIconContainer$IconState и его суперкласса ViewState.
    @Volatile
    private var iconStateResolved = false
    private var fStateVisibleState: Field? = null
    private var fStateHidden: Field? = null
    private var fStateIconAppearAmount: Field? = null
    private var fStateXTranslation: Field? = null
    private var mStateSetXTranslation: Method? = null
    private var mStateGetXTranslation: Method? = null

    private fun resolveContainerMembers(cls: Class<*>, flagsClass: Class<*>?) {
        try {
            fLogTag = XposedHelpers.findFieldIfExists(cls, "mLogTag")
            fIconSize = XposedHelpers.findFieldIfExists(cls, "mIconSize")
            fIconStates = XposedHelpers.findFieldIfExists(cls, "mIconStates")
            fMaxStaticIcons = XposedHelpers.findFieldIfExists(cls, "mMaxStaticIcons")
            fMaxIcons = XposedHelpers.findFieldIfExists(cls, "mMaxIcons")
            fMaxIconsOnAod = XposedHelpers.findFieldIfExists(cls, "mMaxIconsOnAod")
            fMaxIconsOnLockscreen = XposedHelpers.findFieldIfExists(cls, "mMaxIconsOnLockscreen")
            fActualLayoutWidth = XposedHelpers.findFieldIfExists(cls, "mActualLayoutWidth")
            fActualPaddingStart = XposedHelpers.findFieldIfExists(cls, "mActualPaddingStart")
            fActualPaddingEnd = XposedHelpers.findFieldIfExists(cls, "mActualPaddingEnd")
            fFirstOverflowIndex = XposedHelpers.findFieldIfExists(cls, "mFirstOverflowIndex")
            fIsShowingOverflowDot = XposedHelpers.findFieldIfExists(cls, "mIsShowingOverflowDot")
            fWasOverflowForced = XposedHelpers.findFieldIfExists(cls, "mWasOverflowForced")
            fVisualOverflowStart = XposedHelpers.findFieldIfExists(cls, "mVisualOverflowStart")
            fFirstVisibleIconState = XposedHelpers.findFieldIfExists(cls, "mFirstVisibleIconState")
            fLastVisibleIconState = XposedHelpers.findFieldIfExists(cls, "mLastVisibleIconState")
            fOverrideIconColor = XposedHelpers.findFieldIfExists(cls, "mOverrideIconColor")
            fStaticDotDiameter = XposedHelpers.findFieldIfExists(cls, "mStaticDotDiameter")
            fDotPadding = XposedHelpers.findFieldIfExists(cls, "mDotPadding")

            mGetActualPaddingStart = XposedHelpers.findMethodExactIfExists(cls, "getActualPaddingStart")
            mGetActualPaddingEnd = XposedHelpers.findMethodExactIfExists(cls, "getActualPaddingEnd")
            mGetDrawingScale = XposedHelpers.findMethodExactIfExists(cls, "getDrawingScale", View::class.java)
            mShouldForceOverflow = XposedHelpers.findMethodExactIfExists(
                cls, "shouldForceOverflow", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            mIsOverflowing = XposedHelpers.findMethodExactIfExists(
                cls, "isOverflowing",
                Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            val iconStateCls = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.phone.NotificationIconContainer\$IconState",
                cls.classLoader
            )
            if (iconStateCls != null) {
                mGetRtlIconTranslationX = XposedHelpers.findMethodExactIfExists(
                    cls, "getRtlIconTranslationX", iconStateCls, View::class.java
                )
            }
            if (flagsClass != null) {
                mPhysicalNotificationMovement = XposedHelpers.findMethodExactIfExists(flagsClass, "physicalNotificationMovement")
            }
        } catch (_: Throwable) {
        }
    }

    private fun ensureSbivMembers(child: View) {
        if (sbivResolved) return
        try {
            val c = child.javaClass
            mSbivGetVisibleState = XposedHelpers.findMethodExactIfExists(c, "getVisibleState")
            mSbivSetVisibleState2 = XposedHelpers.findMethodExactIfExists(
                c, "setVisibleState", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            )
            mSbivSetVisibleState1 = XposedHelpers.findMethodExactIfExists(
                c, "setVisibleState", Int::class.javaPrimitiveType
            )
            fSbivVisibleState = XposedHelpers.findFieldIfExists(c, "mVisibleState")
            fSbivDrawingSize = XposedHelpers.findFieldIfExists(c, "mStatusBarIconDrawingSize")
            fSbivNewIconSize = XposedHelpers.findFieldIfExists(c, "mNewStatusBarIconSize")
            fSbivStaticDotRadius = XposedHelpers.findFieldIfExists(c, "mStaticDotRadius")
        } catch (_: Throwable) {
        }
        sbivResolved = true
    }

    private fun ensureIconStateMembers(state: Any) {
        if (iconStateResolved) return
        try {
            val c = state.javaClass
            fStateVisibleState = XposedHelpers.findFieldIfExists(c, "visibleState")
            fStateHidden = XposedHelpers.findFieldIfExists(c, "hidden")
            fStateIconAppearAmount = XposedHelpers.findFieldIfExists(c, "iconAppearAmount")
            fStateXTranslation = XposedHelpers.findFieldIfExists(c, "mXTranslation")
                ?: XposedHelpers.findFieldIfExists(c, "xTranslation")
            mStateSetXTranslation = XposedHelpers.findMethodExactIfExists(c, "setXTranslation", Float::class.javaPrimitiveType)
            mStateGetXTranslation = XposedHelpers.findMethodExactIfExists(c, "getXTranslation")
        } catch (_: Throwable) {
        }
        iconStateResolved = true
    }

    // [P3] Быстрая проверка ID контейнера без вызовов Resources.getResourceEntryName на каждый хук.
    private fun isStatusBarIcons(obj: Any?): Boolean {
        if (obj !is View) return false
        val id = obj.id
        if (id == View.NO_ID) return false
        var expected = notifIconsResId
        if (expected == 0) {
            expected = try {
                obj.resources.getIdentifier("notificationIcons", "id", obj.context.packageName)
                    .takeIf { it != 0 } ?: -1
            } catch (_: Throwable) {
                -1
            }
            notifIconsResId = expected
        }
        return id == expected
    }

    private fun isAodIcons(obj: Any?): Boolean {
        if (obj !is View) return false
        val logTag = try {
            fLogTag?.get(obj) as? String
        } catch (_: Throwable) {
            null
        }
        if ("aod".equals(logTag, ignoreCase = true)) return true
        val id = obj.id
        if (id == View.NO_ID) return false
        var expected = aodIconsResId
        if (expected == 0) {
            expected = try {
                obj.resources.getIdentifier("aod_notification_icon_container", "id", obj.context.packageName)
                    .takeIf { it != 0 } ?: -1
            } catch (_: Throwable) {
                -1
            }
            aodIconsResId = expected
        }
        return id == expected
    }

    // [P4] Кэшированный разбор "статусбар,аод" без лишних аллокаций строк и списков.
    private fun updatePrefCache(prefs: Prefs) {
        val raw = try {
            prefs.getString("pref_max_notif_icons", "")
        } catch (_: Throwable) {
            ""
        }
        if (raw == cachedRawPref) return
        cachedSbMax = NotificationIconLimits.statusBar(raw)
        cachedAodMax = NotificationIconLimits.aod(raw)
        cachedRawPref = raw
    }

    private fun statusBarMax(prefs: Prefs): Int? {
        updatePrefCache(prefs)
        return cachedSbMax
    }

    private fun aodMax(prefs: Prefs): Int? {
        updatePrefCache(prefs)
        return cachedAodMax
    }

    // [I1] Динамический headroom, чтобы при любом заданном лимите N в контейнере
    // всегда хватало привязанных view для N иконок + 1 точки переполнения.
    private fun bindHeadroom(prefs: Prefs): Int {
        val sb = statusBarMax(prefs) ?: 0
        return maxOf(MIN_BIND_HEADROOM, sb + 2)
    }

    private fun setIntField(field: Field?, target: Any, value: Int) {
        if (field == null) return
        try {
            field.setInt(target, value)
        } catch (_: Throwable) {
        }
    }

    private fun setFloatField(field: Field?, target: Any, value: Float) {
        if (field == null) return
        try {
            field.setFloat(target, value)
        } catch (_: Throwable) {
        }
    }

    private fun iconSizeOf(v: View): Int {
        return try {
            fIconSize?.getInt(v)?.takeIf { it > 0 } ?: ICON_SIZE_FALLBACK
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
        ensureSbivMembers(child)
        return try {
            (mSbivGetVisibleState?.invoke(child) as? Int)
                ?: fSbivVisibleState?.getInt(child)
                ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun iconStateOf(container: ViewGroup, child: View): Any? {
        return try {
            (fIconStates?.get(container) as? Map<View, Any>)?.get(child)
        } catch (_: Throwable) {
            null
        }
    }

    // Ставит состояние иконки (icon/dot/hidden) через закэшированные Method/Field.
    private fun setChildState(container: ViewGroup, child: View, state: Int) {
        ensureSbivMembers(child)
        var ok = false
        val m2 = mSbivSetVisibleState2
        if (m2 != null) {
            try {
                m2.invoke(child, state, false)
                ok = true
            } catch (_: Throwable) {
            }
        }
        if (!ok) {
            val m1 = mSbivSetVisibleState1
            if (m1 != null) {
                try {
                    m1.invoke(child, state)
                    ok = true
                } catch (_: Throwable) {
                }
            }
        }
        if (!ok) {
            setIntField(fSbivVisibleState, child, state)
        }
        val st = iconStateOf(container, child)
        if (st != null) {
            ensureIconStateMembers(st)
            setIntField(fStateVisibleState, st, state)
        }
    }

    private fun setStateX(state: Any, targetX: Float) {
        ensureIconStateMembers(state)
        val m = mStateSetXTranslation
        if (m != null) {
            try {
                m.invoke(state, targetX)
                return
            } catch (_: Throwable) {
            }
        }
        setFloatField(fStateXTranslation, state, targetX)
    }

    private fun getStateX(state: Any, fallback: Float): Float {
        ensureIconStateMembers(state)
        val m = mStateGetXTranslation
        if (m != null) {
            try {
                return (m.invoke(state) as? Float) ?: fallback
            } catch (_: Throwable) {
            }
        }
        return try {
            fStateXTranslation?.getFloat(state) ?: fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun positionChild(container: ViewGroup, child: View, targetX: Float) {
        val st = iconStateOf(container, child)
        if (st != null) {
            setStateX(st, targetX)
        }
        if (child.translationX != targetX) {
            try {
                child.translationX = targetX
            } catch (_: Throwable) {
            }
        }
    }

    // [L2, R1] Определяет доступную ширину от левого края notificationIcons до физического выреза камеры.
    private fun availableWidthBeforeCutout(container: ViewGroup): Float {
        if (container.layoutDirection == View.LAYOUT_DIRECTION_RTL) return -1f
        return try {
            val cutout = container.rootWindowInsets?.displayCutout ?: return -1f
            val topRect = cutout.boundingRectTop
            if (topRect.isEmpty) return -1f
            val screenW = screenWidthOf(container)
            // Нужен именно центральный вырез камеры сверху (topRect.top <= 0), не угловой и не перевёрнутый на 180° экран.
            if (topRect.top > 0 || topRect.left <= 0 || topRect.right >= screenW) return -1f
            container.getLocationOnScreen(tmpLoc)
            // Физическая линза расположена по центру экрана (screenW / 2) с полурадиусом ~3.5% ширины экрана
            // (~38px на 1080p); boundingRectTop у Nothing даёт заметно более широкую программную зону.
            val physicalLeft = screenW / 2f - screenW * 0.035f
            val avail = physicalLeft - tmpLoc[0]
            if (avail > 0f) avail else -1f
        } catch (_: Throwable) {
            -1f
        }
    }

    private fun intFieldOr(field: Field?, obj: Any, fallback: Int): Int {
        if (field == null) return fallback
        return try {
            field.getInt(obj).takeIf { it > 0 } ?: fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun ensureIconBuffersCapacity(needed: Int) {
        if (iconRefs.size >= needed) return
        val newSize = maxOf(needed, iconRefs.size * 2)
        iconRefs = iconRefs.copyOf(newSize)
        iconTargetsX = iconTargetsX.copyOf(newSize)
    }

    // Держит видимыми не более max иконок: лишнюю превращает в точку, остальные прячет.
    // Если max иконок + точка не помещаются до выреза камеры, сжимает пустые промежутки
    // между иконками так, чтобы и все иконки, и точка были видны левее камеры.
    // Запускается на layout-частоте (applyIconStates), не пер-кадрово.
    private fun enforceMax(container: ViewGroup, prefs: Prefs) {
        if (enforcing) return
        enforcing = true
        try {
            val max = statusBarMax(prefs) ?: return
            val iconSize = iconSizeOf(container)
            val n = container.childCount
            val shownIcons = tmpShownIcons
            shownIcons.clear()
            var dotChild: View? = null

            for (i in 0 until n) {
                val child = container.getChildAt(i) ?: continue
                when (visibleStateOf(child)) {
                    STATE_ICON -> {
                        if (shownIcons.size < max) {
                            shownIcons.add(child)
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

            val shownCount = shownIcons.size
            val sample = dotChild ?: shownIcons.firstOrNull()
            if (sample != null) ensureSbivMembers(sample)
            val drawingSize = if (sample != null) {
                intFieldOr(fSbivDrawingSize, sample, (iconSize * 0.72f).toInt()).toFloat()
            } else {
                iconSize * 0.72f
            }
            val dotDrawOffset = if (sample != null) {
                intFieldOr(fSbivNewIconSize, sample, iconSize) / 2f
            } else {
                iconSize / 2f
            }
            val dotRadius = if (sample != null) {
                intFieldOr(fSbivStaticDotRadius, sample, 8).toFloat()
            } else {
                8f
            }

            val availPx = availableWidthBeforeCutout(container)
            val cutoutMargin = 8f
            val dotGap = 7f
            // Всегда резервируем фиксированное место под точку, чтобы иконки стояли на одних
            // и тех же координатах и не прыгали при появлении/исчезновении точки переполнения.
            val dotReserve = 11f + 2f * dotRadius

            val startX = 0f
            var step = iconSize.toFloat()
            var compacted = false

            if (availPx > 0f && max > 0) {
                val uncompactedRight = (max - 1) * iconSize + (iconSize + drawingSize) / 2f + dotReserve
                if (uncompactedRight > availPx - cutoutMargin) {
                    compacted = true
                    if (max > 1) {
                        val spaceForSteps = availPx - cutoutMargin - dotReserve - startX - (iconSize + drawingSize) / 2f
                        step = (spaceForSteps / (max - 1)).coerceIn(drawingSize + 2f, iconSize.toFloat())
                    }
                }
            }

            // [L1, R1] Записываем координаты иконок без аллокаций Pair/Array.
            // При переходе из compacted -> non-compacted восстанавливаем штатный шаг idx * iconSize.
            if (compacted && shownCount > 0) {
                ensureIconBuffersCapacity(shownCount)
                for (idx in 0 until shownCount) {
                    val child = shownIcons[idx]
                    val x = startX + idx * step
                    positionChild(container, child, x)
                    val curRef = iconRefs[idx]
                    if (curRef == null || curRef.get() !== child) {
                        iconRefs[idx] = WeakReference(child)
                    }
                    iconTargetsX[idx] = x
                }
                for (idx in shownCount until compactedIconCount) {
                    iconRefs[idx] = null
                }
                compactedIconCount = shownCount
            } else {
                if (compactedIconCount > 0) {
                    for (idx in 0 until shownCount) {
                        positionChild(container, shownIcons[idx], idx * iconSize.toFloat())
                    }
                    for (idx in 0 until compactedIconCount) {
                        iconRefs[idx] = null
                    }
                    compactedIconCount = 0
                }
            }

            val dc = dotChild
            var targetX = max * iconSize - iconSize / 2f + DOT_GAP_PX
            if (dc != null) {
                if (compacted && shownCount > 0) {
                    val lastIconRight = startX + (shownCount - 1) * step + (iconSize + drawingSize) / 2f
                    targetX = lastIconRight + dotGap + dotRadius - dotDrawOffset
                }
                if (availPx > 0f) {
                    val maxDotX = availPx - cutoutMargin - dotRadius - dotDrawOffset
                    if (targetX > maxDotX) {
                        targetX = maxDotX
                    }
                }
                positionChild(container, dc, targetX)
                val curDot = dotRef
                if (curDot == null || curDot.get() !== dc) {
                    dotRef = WeakReference(dc)
                }
                dotTargetX = targetX
            } else {
                dotRef = null
            }
            shownIcons.clear()
        } finally {
            enforcing = false
        }
    }

    // [P5] Пер-кадровый якорь: O(1), мгновенный выход если нет ни сжатых иконок, ни точки;
    // без рефлексии/Prefs/аллокаций в горячем пути 120 Гц.
    private fun holdDot() {
        val count = compactedIconCount
        val dRef = dotRef
        if (count == 0 && dRef == null) return

        val refs = iconRefs
        val targets = iconTargetsX
        for (i in 0 until count) {
            val v = refs[i]?.get() ?: continue
            val tx = targets[i]
            if (v.translationX != tx) {
                try {
                    v.translationX = tx
                } catch (_: Throwable) {
                }
            }
        }
        val child = dRef?.get() ?: return
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

    private fun callPaddingStart(container: ViewGroup): Float {
        return try {
            (mGetActualPaddingStart?.invoke(container) as? Float) ?: container.paddingStart.toFloat()
        } catch (_: Throwable) {
            0f
        }
    }

    private fun callPaddingEnd(container: ViewGroup): Float {
        return try {
            (mGetActualPaddingEnd?.invoke(container) as? Float) ?: container.paddingEnd.toFloat()
        } catch (_: Throwable) {
            0f
        }
    }

    private fun callDrawingScale(container: ViewGroup, child: View): Float {
        return try {
            (mGetDrawingScale?.invoke(container, child) as? Float) ?: 1.0f
        } catch (_: Throwable) {
            1.0f
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
        val flagsClass = XposedHelpers.findClassIfExists("com.android.systemui.Flags", lpparam.classLoader)

        resolveContainerMembers(cls, flagsClass)

        // [P1] Хукаем initResources только если на данной прошивке реально существуют legacy-поля.
        if (fMaxIconsOnAod != null || fMaxIconsOnLockscreen != null) {
            try {
                XposedBridge.hookAllMethods(cls, "initResources", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val max = aodMax(prefs) ?: return
                        val slots = max + 1
                        setIntField(fMaxIconsOnAod, param.thisObject, slots)
                        setIntField(fMaxIconsOnLockscreen, param.thisObject, slots)
                    }
                })
            } catch (_: Throwable) {
            }
        }

        // [G1] Подменяем getIconLimit только для AOD-данных (где дефолтный лимит <= DEFAULT_AOD_SLOTS
        // или уже равен нашему aodMax + 1), чтобы не затрагивать StatusBar (40) и Shelf.
        try {
            val dataClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData",
                lpparam.classLoader
            )
            if (dataClass != null) {
                val fIconLimit = XposedHelpers.findFieldIfExists(dataClass, "iconLimit")
                XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val max = aodMax(prefs) ?: return
                        val targetSlots = max + 1
                        val rawLimit = try {
                            fIconLimit?.getInt(param.thisObject) ?: DEFAULT_AOD_SLOTS
                        } catch (_: Throwable) {
                            DEFAULT_AOD_SLOTS
                        }
                        if (rawLimit <= DEFAULT_AOD_SLOTS || rawLimit == targetSlots) {
                            param.result = targetSlots
                        }
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
            val fVmMaxIcons = if (aodVmClass != null) XposedHelpers.findFieldIfExists(aodVmClass, "maxIcons") else null
            if (aodVmClass != null && fVmMaxIcons != null) {
                XposedBridge.hookAllConstructors(aodVmClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val max = aodMax(prefs) ?: return
                        setIntField(fVmMaxIcons, param.thisObject, max + 1)
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
                    val container = param.thisObject as? ViewGroup ?: return
                    if (!isAodIcons(container)) return
                    val max = aodMax(prefs) ?: return
                    val iconSize = try {
                        fIconSize?.getInt(container) ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                    if (iconSize <= 0) return

                    val slots = max + 1
                    val padStart = callPaddingStart(container)
                    val padEnd = callPaddingEnd(container)
                    val drawingScale = if (container.childCount > 0) {
                        callDrawingScale(container, container.getChildAt(0))
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

        try {
            XposedBridge.hookAllMethods(cls, "calculateIconXTranslations", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject
                    if (!isStatusBarIcons(container)) return

                    val view = container as View
                    val max = statusBarMax(prefs) ?: return
                    val slots = max + 1
                    // [P1] fMaxStaticIcons == null на Android 17 -> setIntField мгновенно выходит без NoSuchFieldError.
                    setIntField(fMaxStaticIcons, container, slots)
                    setIntField(fMaxIcons, container, bindHeadroom(prefs))
                    widenParents(view, fullWidth(view, slots))
                    setIntField(fActualLayoutWidth, container, layoutWidth(view, slots))
                    setFloatField(fActualPaddingStart, container, 0f)
                    setFloatField(fActualPaddingEnd, container, 0f)
                }

                // Android 17 / Nothing OS 5: для центрированных часов на AOD захардкожено `i3 <= 5`,
                // что обрезает раскладку до 4 иконок + точка. Пересчитываем под aodMax + 1 слотов.
                override fun afterHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject as? ViewGroup ?: return
                    if (!isAodIcons(container)) return
                    val max = aodMax(prefs) ?: return
                    val slots = max + 1
                    if (slots <= DEFAULT_AOD_SLOTS) return
                    if (container.childCount <= DEFAULT_AOD_SLOTS || container.width <= 0) return

                    val firstVisible = try {
                        fFirstVisibleIconState?.get(container)
                    } catch (_: Throwable) {
                        null
                    }
                    val padStart = callPaddingStart(container)
                    val firstX = if (firstVisible != null) getStateX(firstVisible, padStart) else padStart
                    val isRtl = container.layoutDirection == View.LAYOUT_DIRECTION_RTL
                    val isCentered = if (!isRtl) {
                        firstX > padStart + 1f
                    } else {
                        val firstChildW = container.getChildAt(0)?.width ?: 0
                        firstX < (container.width - padStart - firstChildW) - 1f
                    }
                    if (!isCentered) return

                    recalculateCenteredAod(container, slots)
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
                        param.args[0] = bindHeadroom(prefs)
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

    // [E1, I1] Безопасный пересчёт центрированного AOD через закэшированные Field/Method без жёстких кастов.
    private fun recalculateCenteredAod(container: ViewGroup, slots: Int) {
        if (slots <= 0) return
        try {
            @Suppress("UNCHECKED_CAST")
            val iconStates = fIconStates?.get(container) as? Map<View, Any> ?: return
            val childCount = container.childCount
            val width = container.width
            val iconSize = fIconSize?.getInt(container) ?: 0
            if (iconSize <= 0 || childCount <= 0) return

            val firstState = iconStates[container.getChildAt(0)]
            if (firstState != null) ensureIconStateMembers(firstState)
            val fHidden = fStateHidden ?: return
            val fAppear = fStateIconAppearAmount ?: return
            val fVisState = fStateVisibleState ?: return

            val padStart = callPaddingStart(container)
            val padEnd = callPaddingEnd(container)
            val maxContentWidth = (width - padStart - padEnd).coerceAtLeast(0f)

            var totalWidth = 0f
            var visibleCount = 0
            for (i in 0 until childCount) {
                val child = container.getChildAt(i) ?: continue
                val state = iconStates[child] ?: continue
                if (!fHidden.getBoolean(state)) visibleCount++
                if (visibleCount <= slots) {
                    val appear = fAppear.getFloat(state)
                    val scale = callDrawingScale(container, child)
                    val step = appear * child.width * scale
                    if (totalWidth + step <= maxContentWidth + 0.5f) {
                        totalWidth += step
                    }
                }
            }

            var x = ((width - totalWidth) / 2f).coerceAtLeast(padStart)
            val rightBound = x + totalWidth + 0.5f

            val overrideIconColor = fOverrideIconColor?.getBoolean(container) ?: false
            var firstOverflowIndex = -1
            var visualOverflowStart = 0f
            var wasOverflowForced = false
            var isShowingOverflowDot = false
            var firstVisibleState: Any? = null
            var lastVisibleState: Any? = null

            for (i in 0 until childCount) {
                val child = container.getChildAt(i) ?: continue
                val state = iconStates[child] ?: continue
                val appear = fAppear.getFloat(state)
                if (appear == 1.0f) {
                    setStateX(state, x)
                }
                if (firstVisibleState == null) {
                    firstVisibleState = state
                }
                fVisState.setInt(state, if (fHidden.getBoolean(state)) STATE_HIDDEN else STATE_ICON)

                val forced = (mShouldForceOverflow?.invoke(container, i, appear, slots) as? Boolean)
                    ?: (i >= slots && appear > 0f)
                val isLast = i == childCount - 1
                val isOvf = (mIsOverflowing?.invoke(container, isLast, x, rightBound, iconSize.toFloat()) as? Boolean)
                    ?: (x + iconSize > rightBound)
                val overflowing = (forced || isOvf) && appear > 0f

                if (firstOverflowIndex == -1 && overflowing) {
                    wasOverflowForced = forced
                    if (overrideIconColor) {
                        firstOverflowIndex = i
                        visualOverflowStart = x
                    } else {
                        val idx = if (i > 0) i - 1 else i
                        firstOverflowIndex = idx
                        val prevState = iconStates[container.getChildAt(idx)]
                        visualOverflowStart = if (prevState != null) getStateX(prevState, x) else x
                    }
                }

                val scale = callDrawingScale(container, child)
                x += appear * child.width * scale
            }

            val physicalMovement = try {
                (mPhysicalNotificationMovement?.invoke(null) as? Boolean) ?: false
            } catch (_: Throwable) {
                false
            }

            if (firstOverflowIndex != -1) {
                var dotX = visualOverflowStart
                val dotStep = (fStaticDotDiameter?.getInt(container) ?: 12) +
                    (fDotPadding?.getInt(container) ?: 6)
                for (i in firstOverflowIndex until childCount) {
                    val state = iconStates[container.getChildAt(i)] ?: continue
                    val appear = fAppear.getFloat(state)
                    setStateX(state, dotX)
                    val isLast = i == childCount - 1
                    if (!isShowingOverflowDot) {
                        if (appear < 0.8f && (isLast || !physicalMovement)) {
                            fVisState.setInt(state, STATE_ICON)
                        } else {
                            fVisState.setInt(state, if (isLast || !physicalMovement) STATE_DOT else STATE_HIDDEN)
                            isShowingOverflowDot = true
                        }
                        if (!physicalMovement) {
                            dotX += dotStep * appear
                        }
                        lastVisibleState = state
                    } else {
                        fVisState.setInt(state, if (isLast && physicalMovement) STATE_DOT else STATE_HIDDEN)
                    }
                }
            } else {
                lastVisibleState = iconStates[container.getChildAt(childCount - 1)]
                firstVisibleState = iconStates[container.getChildAt(0)]
            }

            if (container.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                val mRtl = mGetRtlIconTranslationX
                for (i in 0 until childCount) {
                    val child = container.getChildAt(i) ?: continue
                    val state = iconStates[child] ?: continue
                    val rtlX = (mRtl?.invoke(container, state, child) as? Float)
                        ?: ((width - getStateX(state, 0f)) - child.width)
                    setStateX(state, rtlX)
                }
            }

            setIntField(fFirstOverflowIndex, container, firstOverflowIndex)
            fIsShowingOverflowDot?.setBoolean(container, isShowingOverflowDot)
            fWasOverflowForced?.setBoolean(container, wasOverflowForced)
            setFloatField(fVisualOverflowStart, container, visualOverflowStart)
            fFirstVisibleIconState?.set(container, firstVisibleState)
            fLastVisibleIconState?.set(container, lastVisibleState)
        } catch (_: Throwable) {
        }
    }
}
