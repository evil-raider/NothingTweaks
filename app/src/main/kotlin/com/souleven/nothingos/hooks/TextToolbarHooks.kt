package com.souleven.nothingos.hooks

import android.app.AndroidAppHelper
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.souleven.nothingos.hooks.TextToolbarKeys as K
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Text selection toolbar ("Cut / Copy / Paste ..." floating menu) tweaks.
 *
 * The toolbar is built and drawn inside every app process by framework classes
 * (FloatingToolbar / LocalFloatingToolbarPopup / Editor), so these hooks only work
 * in apps that are enabled in the module scope in LSPosed.
 *
 * Also contains experimental support for Telegram's own copy of FloatingToolbar
 * (org.telegram.ui.ActionBar.FloatingToolbar) and auto-copy through the menu for
 * Chrome / WebView.
 */
class TextToolbarHooks : HookModule {

    companion object {
        private const val TAG = "NothingTweaks:TextToolbar"
        private const val FT_CLASS = "com.android.internal.widget.floatingtoolbar.FloatingToolbar"
        private const val POPUP_CLASS = "com.android.internal.widget.floatingtoolbar.LocalFloatingToolbarPopup"
        private const val FLOATING_ACTION_MODE = "com.android.internal.view.FloatingActionMode"
        private const val EDITOR_CB_CLASS = "android.widget.Editor\$TextActionModeCallback"
        private const val TG_FT_CLASS = "org.telegram.ui.ActionBar.FloatingToolbar"
        private const val TG_POPUP_CLASS = "org.telegram.ui.ActionBar.FloatingToolbar\$FloatingToolbarPopup"
        private const val TG_SELECTION_HELPER = "org.telegram.ui.Cells.TextSelectionHelper"
        private const val TG_MARKER_CLASS = "org.telegram.messenger.AndroidUtilities"
        private const val MENU_ITEM_IMPL = "com.android.internal.view.menu.MenuItemImpl"
        private const val AUTOCOPY_DELAY_MS = 350L
        private const val MENU_COPY_WINDOW_MS = 1500L
        private const val TOAST_DURATION_MS = 1300L

        @Volatile
        private var installed = false
    }

    private lateinit var prefs: Prefs
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pkg: String by lazy { runCatching { AndroidAppHelper.currentPackageName() }.getOrNull() ?: "?" }

    /** True while a main-panel layout is running (UI thread only). */
    private var inMainPanelLayout = false

    private var cachedLabelsRaw: String? = null
    private var cachedLabels: Map<String, String> = emptyMap()
    private var cachedOrderRaw: String? = null
    private var cachedRank: Map<String, Int> = emptyMap()
    private var titleMapLocale: Locale? = null
    private var titleMap: Map<String, String> = emptyMap()

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        // Framework classes are shared by all packages in a process: install once.
        if (installed) return
        installed = true
        this.prefs = prefs

        safe("doShow") { hookDoShow() }
        safe("layoutMainPanelItems") { hookMainPanelLayout() }
        safe("refreshCoordinates") { hookPosition() }
        safe("autocopy") { hookAutoCopy() }
        safe("menuAutocopy") { hookMenuAutoCopy() }
        if (XposedHelpers.findClassIfExists(TG_FT_CLASS, lpparam.classLoader) != null) {
            safe("telegram") { hookTelegram(lpparam.classLoader) }
        } else if (XposedHelpers.findClassIfExists(TG_MARKER_CLASS, lpparam.classLoader) != null) {
            // Obfuscated fork (e.g. Plus Messenger): work on the framework MenuItem level.
            safe("telegramGeneric") { hookTelegramGeneric() }
        }
        debug { "hooks installed in ${lpparam.packageName}" }
    }

    // ---------------------------------------------------------------------------------------
    // Settings helpers
    // ---------------------------------------------------------------------------------------

    private fun enabled() = prefs.getBoolean(K.ENABLED, false)
    private fun shortLabels() = prefs.getString(K.LABEL_MODE, K.LABEL_MODE_FULL) == K.LABEL_MODE_SHORT
    private fun noOverflow() = prefs.getBoolean(K.NO_OVERFLOW, false)
    private fun twoLines() = prefs.getString(K.LABEL_LINES, "1") == "2"

    private fun debug(msg: () -> String) {
        if (::prefs.isInitialized && prefs.getBoolean(K.DEBUG, false)) {
            XposedBridge.log("$TAG [$pkg] ${msg()}")
        }
    }

    private fun customLabels(): Map<String, String> {
        val raw = prefs.getString(K.LABELS, "")
        if (raw == cachedLabelsRaw) return cachedLabels
        cachedLabelsRaw = raw
        cachedLabels = try {
            val json = JSONObject(raw.ifEmpty { "{}" })
            json.keys().asSequence().associateWith { json.optString(it) }.filterValues { it.isNotBlank() }
        } catch (t: Throwable) {
            emptyMap()
        }
        return cachedLabels
    }

    private fun rank(): Map<String, Int> {
        val raw = prefs.getString(K.ORDER, "")
        if (raw == cachedOrderRaw) return cachedRank
        cachedOrderRaw = raw
        cachedRank = K.parseList(raw).withIndex().associate { it.value to it.index }
        return cachedRank
    }

    private fun hidden(): Set<String> = K.parseList(prefs.getString(K.HIDDEN, "")).toHashSet()

    // ---------------------------------------------------------------------------------------
    // Item identification
    // ---------------------------------------------------------------------------------------

    private fun keyOf(item: MenuItem): String? {
        item.intent?.let { intent ->
            if (intent.action == Intent.ACTION_PROCESS_TEXT) {
                val cn = intent.component
                if (cn != null) return K.processTextKey(cn.packageName, cn.className)
            }
        }
        when (item.itemId) {
            android.R.id.textAssist -> return K.ITEM_ASSIST
            android.R.id.cut -> return K.ITEM_CUT
            android.R.id.copy -> return K.ITEM_COPY
            android.R.id.paste -> return K.ITEM_PASTE
            android.R.id.pasteAsPlainText -> return K.ITEM_PASTE_PLAIN
            android.R.id.shareText -> return K.ITEM_SHARE
            android.R.id.selectAll -> return K.ITEM_SELECT_ALL
            android.R.id.replaceText -> return K.ITEM_REPLACE
            android.R.id.autofill -> return K.ITEM_AUTOFILL
        }
        if (item.groupId == android.R.id.textAssist) return K.ITEM_ASSIST_SECONDARY
        // Chrome / WebView use their own resource ids (select_action_menu_copy, ...).
        appIdKey(item.itemId)?.let { return it }
        // Fallback for menus with custom ids but system titles (e.g. Compose).
        val title = item.title?.toString()?.trim() ?: return null
        return systemTitleMap()[title]
    }

    private val appIdCache = HashMap<Int, String?>()

    private fun idName(id: Int): String? {
        if (id == 0 || id == -1) return null
        return runCatching {
            AndroidAppHelper.currentApplication()?.resources?.getResourceEntryName(id)
        }.getOrNull()?.lowercase()
    }

    private fun appIdKey(id: Int): String? = appIdCache.getOrPut(id) {
        val name = idName(id) ?: return@getOrPut null
        when {
            name.endsWith("paste_as_plain_text") -> K.ITEM_PASTE_PLAIN
            name.endsWith("_copy") -> K.ITEM_COPY
            name.endsWith("_cut") -> K.ITEM_CUT
            name.endsWith("_paste") -> K.ITEM_PASTE
            name.endsWith("_select_all") -> K.ITEM_SELECT_ALL
            name.endsWith("_share") -> K.ITEM_SHARE
            else -> null
        }
    }

    private fun systemTitleMap(): Map<String, String> {
        val res = Resources.getSystem()
        val locale = res.configuration.locales[0]
        if (locale == titleMapLocale) return titleMap
        titleMapLocale = locale
        val map = HashMap<String, String>()
        fun put(name: String, key: String) {
            val id = res.getIdentifier(name, "string", "android")
            if (id != 0) runCatching { map[res.getString(id).trim()] = key }
        }
        put("cut", K.ITEM_CUT)
        put("copy", K.ITEM_COPY)
        put("paste", K.ITEM_PASTE)
        put("paste_as_plain_text", K.ITEM_PASTE_PLAIN)
        put("selectAll", K.ITEM_SELECT_ALL)
        put("share", K.ITEM_SHARE)
        put("autofill", K.ITEM_AUTOFILL)
        titleMap = map
        return map
    }

    private fun MenuItem.flag(method: String): Boolean =
        runCatching { XposedHelpers.callMethod(this, method) as Boolean }.getOrDefault(false)

    private fun describe(items: List<MenuItem>) = items.joinToString { item ->
        "${keyOf(item) ?: "?"}(id=${idName(item.itemId) ?: item.itemId}, '${item.title}')"
    }

    /** Filter hidden items and sort by the user's order. Never returns an empty list. */
    private fun arrange(all: List<MenuItem>, keepOverflowRule: Boolean): MutableList<MenuItem> {
        val hidden = hidden()
        var items: MutableList<MenuItem> =
            all.filterTo(ArrayList()) { item -> keyOf(item)?.let { it !in hidden } ?: true }
        if (items.isEmpty()) items = ArrayList(all)
        items.sortWith(itemComparator(keepOverflowRule))
        return items
    }

    private fun itemComparator(keepOverflowRule: Boolean) = Comparator<MenuItem> { a, b ->
        if (keepOverflowRule) {
            val ao = a.flag("requiresOverflow")
            val bo = b.flag("requiresOverflow")
            if (ao != bo) return@Comparator if (ao) 1 else -1
        }
        val rank = rank()
        val ra = keyOf(a)?.let { rank[it] } ?: Int.MAX_VALUE
        val rb = keyOf(b)?.let { rank[it] } ?: Int.MAX_VALUE
        if (ra != rb) ra.compareTo(rb) else a.order.compareTo(b.order)
    }

    // ---------------------------------------------------------------------------------------
    // 1. Order + hidden items: FloatingToolbar.doShow()
    // ---------------------------------------------------------------------------------------

    /** Latest copy item / listener shown by the system toolbar (for menu auto-copy). */
    private var lastMenuCopyItem: WeakReference<MenuItem>? = null
    private var lastMenuListener: WeakReference<MenuItem.OnMenuItemClickListener>? = null

    private fun hookDoShow() {
        val ftClass = XposedHelpers.findClass(FT_CLASS, null)
        XposedHelpers.findAndHookMethod(ftClass, "doShow", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                try {
                    val ft = param.thisObject
                    val menu = XposedHelpers.getObjectField(ft, "mMenu") ?: return
                    @Suppress("UNCHECKED_CAST")
                    val all = XposedHelpers.callStaticMethod(
                        ftClass, "getVisibleAndEnabledMenuItems", menu
                    ) as MutableList<MenuItem>
                    debug { "menu: ${describe(all)}" }

                    val items = arrange(all, keepOverflowRule = !noOverflow())
                    val listener = XposedHelpers.getObjectField(ft, "mMenuItemClickListener")
                    val popup = XposedHelpers.getObjectField(ft, "mPopup")
                    XposedHelpers.callMethod(
                        popup, "show", items, listener, XposedHelpers.getObjectField(ft, "mContentRect")
                    )
                    param.result = null
                    val anchor = runCatching {
                        (XposedHelpers.getObjectField(ft, "mWindow") as? android.view.Window)?.decorView
                    }.getOrNull()
                    scheduleMenuAutoCopy(all, listener as? MenuItem.OnMenuItemClickListener, anchor)
                } catch (t: Throwable) {
                    // Fall back to the original implementation.
                    XposedBridge.log("$TAG doShow failed: ${t.message}")
                }
            }
        })
    }

    // ---------------------------------------------------------------------------------------
    // 2. Short square buttons + no overflow: LocalFloatingToolbarPopup
    // ---------------------------------------------------------------------------------------

    private fun hookMainPanelLayout() {
        val popupClass = XposedHelpers.findClass(POPUP_CLASS, null)
        val res = Resources.getSystem()
        val textId = res.getIdentifier("floating_toolbar_menu_item_text", "id", "android")

        // Style main-panel buttons right when they are created, so that the original width
        // calculation already sees the narrow buttons. Overflow list items stay untouched.
        XposedBridge.hookAllMethods(popupClass, "updateMenuItemButton", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!inMainPanelLayout || !shortLabels()) return
                try {
                    val button = param.args[0] as? View ?: return
                    val item = param.args[1] as? MenuItem ?: return
                    val showIcon = param.args.getOrNull(3) as? Boolean ?: false
                    val text = if (textId != 0) button.findViewById<TextView>(textId) else null
                    styleShortButton(button, text, item, showIcon)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG updateMenuItemButton failed: ${t.message}")
                }
            }
        })

        XposedBridge.hookAllMethods(popupClass, "layoutMainPanelItems", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                inMainPanelLayout = enabled()
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    if (!inMainPanelLayout || !noOverflow()) return
                    @Suppress("UNCHECKED_CAST")
                    val remaining = param.result as? List<MenuItem> ?: return
                    if (remaining.isEmpty()) return
                    val popup = param.thisObject
                    val context = XposedHelpers.getObjectField(popup, "mContext") as Context
                    val spacing = XposedHelpers.getIntField(popup, "mIconTextSpacing")
                    fillMainPanel(
                        panel = XposedHelpers.getObjectField(popup, "mMainPanel") as ViewGroup,
                        remaining = remaining,
                        toolbarWidth = param.args[1] as Int,
                        textOf = { v -> if (textId != 0) v.findViewById(textId) else null },
                        create = { item ->
                            XposedHelpers.callStaticMethod(
                                popupClass, "createMenuItemButton", context, item, spacing, false
                            ) as View
                        },
                        bind = { v, item -> XposedHelpers.callMethod(popup, "setButtonTagAndClickListener", v, item) },
                        remeasure = {
                            XposedHelpers.setObjectField(
                                popup, "mMainPanelSize",
                                XposedHelpers.callStaticMethod(popupClass, "measure",
                                    XposedHelpers.getObjectField(popup, "mMainPanel"))
                            )
                        }
                    )
                    param.result = ArrayList<MenuItem>()
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG no-overflow failed: ${t.message}")
                } finally {
                    inMainPanelLayout = false
                }
            }
        })
    }

    /**
     * Short label. One line: first N letters of the first word ("ВСТА").
     * Two lines: one word is split into two chunks ("ВСТА\nВИТЬ"), several words give
     * first + last word ("ВСТА\nТЕКС"). Custom labels may use "|" as a line break.
     */
    private fun shortLabel(item: MenuItem, oneLine: Boolean = false): String {
        val upper = prefs.getBoolean(K.LABEL_UPPER, true)
        fun finish(label: String) = if (upper) label.uppercase() else label
        keyOf(item)?.let { key ->
            customLabels()[key]?.let { return finish(it.replace("|", if (oneLine) "" else "\n")) }
        }
        val len = prefs.getString(K.LABEL_LEN, "4").toIntOrNull()?.coerceIn(1, 8) ?: 4
        val title = item.title?.toString().orEmpty()
            .replace("\u2026", "").replace("...", "").trim()
        val words = title.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val first = words.firstOrNull().orEmpty()
        if (oneLine || !twoLines()) return finish(first.take(len))
        val label = when {
            words.size >= 2 -> first.take(len) + "\n" + words.last().take(len)
            first.length > len -> first.take(len) + "\n" + first.drop(len).take(len)
            else -> first
        }
        return finish(label)
    }

    /** Text size in sp, or null to keep the original size. */
    private fun labelSizeSp(twoLineLabel: Boolean): Float? {
        val raw = prefs.getString(K.LABEL_SIZE, "auto")
        raw.toFloatOrNull()?.let { return it.coerceIn(8f, 20f) }
        return if (twoLineLabel) 13f else null
    }

    private fun styleShortButton(button: View, text: TextView?, item: MenuItem, showIcon: Boolean) {
        val dm = button.resources.displayMetrics
        val iconOnly = showIcon && item.icon != null
        var twoLineLabel = false
        if (iconOnly) {
            text?.visibility = View.GONE
        } else {
            text?.apply {
                visibility = View.VISIBLE
                this.text = shortLabel(item)
                setPaddingRelative(0, 0, 0, 0)
                twoLineLabel = this.text.contains('\n')
                if (twoLineLabel) {
                    isSingleLine = false
                    maxLines = 2
                    setLineSpacing(0f, 0.85f)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    ellipsize = null
                }
                labelSizeSp(twoLineLabel)?.let { setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
            }
        }
        val pad = ((if (twoLineLabel) 2 else 4) * dm.density).toInt()
        button.setPaddingRelative(pad, button.paddingTop, pad, button.paddingBottom)
        button.minimumWidth = (48 * dm.density).toInt()
        (button as? LinearLayout)?.gravity = Gravity.CENTER
    }

    /** Adds [remaining] items to the main panel and shrinks all buttons evenly if needed. */
    private fun fillMainPanel(
        panel: ViewGroup,
        remaining: List<MenuItem>,
        toolbarWidth: Int,
        textOf: (View) -> TextView?,
        create: (MenuItem) -> View,
        bind: (View, MenuItem) -> Unit,
        remeasure: () -> Unit,
    ) {
        for (item in remaining) {
            val button = create(item)
            (button as? LinearLayout)?.gravity = Gravity.CENTER
            bind(button, item)
            runCatching { button.tooltipText = item.title }
            panel.addView(button)
        }
        panel.setPaddingRelative(0, 0, 0, 0) // no space reserved for the overflow button

        val count = panel.childCount
        val widths = IntArray(count)
        var total = 0
        for (i in 0 until count) {
            val child = panel.getChildAt(i)
            child.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            widths[i] = child.measuredWidth
            total += widths[i]
        }
        val shrink = total > toolbarWidth
        val each = toolbarWidth / count.coerceAtLeast(1)
        for (i in 0 until count) {
            val child = panel.getChildAt(i)
            child.minimumWidth = 0
            val lp = child.layoutParams ?: ViewGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.width = if (shrink) each else widths[i]
            child.layoutParams = lp
            if (shrink) textOf(child)?.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        remeasure()
    }

    // ---------------------------------------------------------------------------------------
    // 3. Position: refreshCoordinatesAndOverflowDirection(Rect)
    // ---------------------------------------------------------------------------------------

    private fun hookPosition() {
        val popupClass = XposedHelpers.findClass(POPUP_CLASS, null)
        XposedBridge.hookAllMethods(popupClass, "refreshCoordinatesAndOverflowDirection", positionHook())
    }

    private fun positionHook() = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (!enabled()) return
            val mode = prefs.getString(K.POSITION, K.POSITION_DEFAULT)
            if (mode == K.POSITION_DEFAULT) return
            try {
                applyPosition(param.thisObject, param.args[0] as Rect, mode)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG position failed: ${t.message}")
            }
        }
    }

    private fun applyPosition(popup: Any, content: Rect, mode: String) {
        val viewPort = XposedHelpers.getObjectField(popup, "mViewPortOnScreen") as Rect
        val window = XposedHelpers.getObjectField(popup, "mPopupWindow") as PopupWindow
        val coords = XposedHelpers.getObjectField(popup, "mCoordsOnWindow") as Point
        val parent = XposedHelpers.getObjectField(popup, "mParent") as View
        val hasOverflow = XposedHelpers.getObjectField(popup, "mOverflowPanelSize") != null

        // Same window-origin calculation as AOSP.
        val tmp = IntArray(2)
        val root = parent.rootView
        root.getLocationOnScreen(tmp)
        val rootLeftOnScreen = tmp[0]
        val rootTopOnScreen = tmp[1]
        root.getLocationInWindow(tmp)
        val windowLeft = rootLeftOnScreen - tmp[0]
        val windowTop = rootTopOnScreen - tmp[1]

        if (mode == K.POSITION_ABOVE_RIGHT || mode == K.POSITION_BELOW_RIGHT) {
            val x = (viewPort.right - window.width).coerceAtLeast(viewPort.left)
            coords.x = (x - windowLeft).coerceAtLeast(0)
        }

        // Vertical change only for a single-row toolbar; with an overflow panel the
        // original code also decides the overflow opening direction, which we leave alone.
        if (hasOverflow) return
        val margin = 2 * XposedHelpers.getIntField(popup, "mMarginVertical")
        val height = XposedHelpers.getIntField(popup, "mLineHeight") + margin
        val fitsAbove = content.top - viewPort.top >= height
        val fitsBelow = viewPort.bottom - content.bottom >= height
        val wantBelow = mode == K.POSITION_BELOW || mode == K.POSITION_BELOW_RIGHT
        val y = when {
            wantBelow && fitsBelow -> content.bottom
            !wantBelow && fitsAbove -> content.top - height
            else -> return // keep the original fallback
        }
        coords.y = (y - windowTop).coerceAtLeast(0)
    }

    // ---------------------------------------------------------------------------------------
    // 4. Auto-copy on selection (TextView / EditText)
    // ---------------------------------------------------------------------------------------

    private var pendingView: WeakReference<TextView>? = null
    private var lastCopied: String? = null
    private var toastWindow: PopupWindow? = null
    private val copyRunnable = Runnable { doAutoCopy() }

    private fun autoCopyOn() = enabled() && prefs.getBoolean(K.AUTOCOPY, false)

    private fun hookAutoCopy() {
        val cbClass = XposedHelpers.findClass(EDITOR_CB_CLASS, null)

        // Called on every toolbar (re)positioning, i.e. whenever the selection changes.
        XposedBridge.hookAllMethods(cbClass, "onGetContentRect", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!autoCopyOn()) return
                try {
                    if (!XposedHelpers.getBooleanField(param.thisObject, "mHasSelection")) return
                    val editor = XposedHelpers.getObjectField(param.thisObject, "this\$0")
                    val tv = XposedHelpers.getObjectField(editor, "mTextView") as? TextView ?: return
                    pendingView = WeakReference(tv)
                    mainHandler.removeCallbacks(copyRunnable)
                    mainHandler.postDelayed(copyRunnable, AUTOCOPY_DELAY_MS)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG autocopy schedule failed: ${t.message}")
                }
            }
        })

        XposedBridge.hookAllMethods(cbClass, "onDestroyActionMode", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                mainHandler.removeCallbacks(copyRunnable)
                pendingView = null
                lastCopied = null
            }
        })
    }

    private fun doAutoCopy() {
        val tv = pendingView?.get() ?: return
        try {
            if (!tv.isAttachedToWindow || !tv.hasSelection()) return
            val canCopy = runCatching { XposedHelpers.callMethod(tv, "canCopy") as Boolean }
                .getOrDefault(false) // false for password fields
            if (!canCopy) return
            val start = minOf(tv.selectionStart, tv.selectionEnd).coerceAtLeast(0)
            val end = maxOf(tv.selectionStart, tv.selectionEnd).coerceAtMost(tv.text.length)
            if (start >= end) return
            copyText(tv, tv.text.subSequence(start, end).toString())
        } catch (t: Throwable) {
            XposedBridge.log("$TAG autocopy failed: ${t.message}")
        }
    }

    private fun copyText(anchor: View, text: String) {
        if (text.isEmpty() || text == lastCopied) return
        val cm = anchor.context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(K.AUTOCOPY_CLIP_LABEL, text))
        lastCopied = text
        debug { "auto-copied ${text.length} chars" }
        if (prefs.getString(K.AUTOCOPY_FEEDBACK, K.FEEDBACK_TOP) == K.FEEDBACK_TOP) showTopToast(anchor)
    }

    /** Toast.setGravity() is ignored for text toasts on Android 11+, so draw our own. */
    private fun showTopToast(anchor: View) {
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val label = if (ctx.resources.configuration.locales[0].language == "ru") "Скопировано" else "Copied"
        val view = TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xE6202020.toInt())
            }
        }
        toastWindow?.let { runCatching { it.dismiss() } }
        val window = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = false
            isOutsideTouchable = false
            animationStyle = android.R.style.Animation_Toast
        }
        val statusBar = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            anchor.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
        } else {
            @Suppress("DEPRECATION")
            anchor.rootWindowInsets?.systemWindowInsetTop ?: 0
        }
        runCatching {
            window.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, statusBar + dp(12))
        }
        toastWindow = window
        mainHandler.postDelayed({
            runCatching { window.dismiss() }
            if (toastWindow === window) toastWindow = null
        }, TOAST_DURATION_MS)
    }

    // ---------------------------------------------------------------------------------------
    // 5. EXPERIMENTAL: auto-copy through the menu (Chrome / WebView)
    //    Chrome keeps the selected text inside the web engine, so we "press" its own Copy
    //    item and block the ActionMode.finish() that Chrome calls right after it.
    // ---------------------------------------------------------------------------------------

    private var blockFinish = false
    private var menuCopyAt = 0L
    private val menuCopyRunnable = Runnable { doMenuAutoCopy() }

    private fun menuAutoCopyOn() = autoCopyOn() && prefs.getBoolean(K.EXP_MENU_AUTOCOPY, false)

    private fun hookMenuAutoCopy() {
        val famClass = XposedHelpers.findClass(FLOATING_ACTION_MODE, null)
        XposedBridge.hookAllMethods(famClass, "finish", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (blockFinish) param.result = null
            }
        })

        // Relabel the clip Chrome writes (asynchronously) after our synthetic click, so that
        // SystemUI suppression and our toast work like for normal auto-copy.
        XposedBridge.hookAllMethods(ClipboardManager::class.java, "setPrimaryClip", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (menuCopyAt == 0L || SystemClock.uptimeMillis() - menuCopyAt > MENU_COPY_WINDOW_MS) return
                menuCopyAt = 0L
                val clip = param.args[0] as? ClipData ?: return
                if (clip.itemCount == 0) return
                val text = clip.getItemAt(0).text?.toString() ?: return
                val relabeled = ClipData(ClipDescription(K.AUTOCOPY_CLIP_LABEL, arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)), ClipData.Item(text))
                param.args[0] = relabeled
                val isNew = text != lastCopied
                lastCopied = text
                debug { "menu auto-copy: ${text.length} chars" }
                if (isNew && prefs.getString(K.AUTOCOPY_FEEDBACK, K.FEEDBACK_TOP) == K.FEEDBACK_TOP) {
                    val anchor = lastToolbarAnchor?.get() ?: return
                    mainHandler.post { showTopToast(anchor) }
                }
            }
        })
    }

    private var lastToolbarAnchor: WeakReference<View>? = null

    /** Called from doShow: only for menus whose Copy item is not the framework one. */
    private fun scheduleMenuAutoCopy(all: List<MenuItem>, listener: MenuItem.OnMenuItemClickListener?, anchor: View?) {
        if (!menuAutoCopyOn() || listener == null) return
        val copy = all.firstOrNull { it.itemId != android.R.id.copy && idName(it.itemId)?.startsWith("select_action_menu_copy") == true }
            ?: return
        lastMenuCopyItem = WeakReference(copy)
        lastMenuListener = WeakReference(listener)
        lastToolbarAnchor = anchor?.let { WeakReference(it) }
        mainHandler.removeCallbacks(menuCopyRunnable)
        mainHandler.postDelayed(menuCopyRunnable, AUTOCOPY_DELAY_MS)
    }

    private fun doMenuAutoCopy() {
        val item = lastMenuCopyItem?.get() ?: return
        val listener = lastMenuListener?.get() ?: return
        try {
            blockFinish = true
            menuCopyAt = SystemClock.uptimeMillis()
            listener.onMenuItemClick(item)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG menu auto-copy failed: ${t.message}")
        } finally {
            blockFinish = false
        }
    }

    // ---------------------------------------------------------------------------------------
    // 6. EXPERIMENTAL: Telegram and forks (own copy of FloatingToolbar + TextSelectionHelper)
    // ---------------------------------------------------------------------------------------

    private fun telegramOn() = enabled() && prefs.getBoolean(K.EXP_TELEGRAM, false)

    private fun hookTelegram(cl: ClassLoader) {
        val ft = XposedHelpers.findClass(TG_FT_CLASS, cl)
        val popupClass = XposedHelpers.findClassIfExists(TG_POPUP_CLASS, cl)

        // Order + hidden: filter the item list, and replace the (order-only) comparator.
        XposedBridge.hookAllMethods(ft, "getVisibleAndEnabledMenuItems", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!telegramOn()) return
                @Suppress("UNCHECKED_CAST")
                val list = param.result as? MutableList<MenuItem> ?: return
                // Only the outermost call carries the Menu given to the toolbar.
                if (param.args.getOrNull(0) !== XposedHelpers.getObjectField(param.thisObject, "mMenu")) return
                debug { "telegram menu: ${describe(list)}" }
                param.result = arrange(list, keepOverflowRule = false)
            }
        })
        XposedBridge.hookAllConstructors(ft, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val original = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    XposedHelpers.getObjectField(param.thisObject, "mMenuItemComparator") as Comparator<MenuItem>
                }.getOrNull() ?: return
                val ours = itemComparator(keepOverflowRule = false)
                runCatching {
                    XposedHelpers.setObjectField(param.thisObject, "mMenuItemComparator", Comparator<MenuItem> { a, b ->
                        if (telegramOn()) ours.compare(a, b) else original.compare(a, b)
                    })
                }
            }
        })

        // Short labels: static updateMenuItemButton(View, MenuItem, int, boolean); text is child 0.
        XposedBridge.hookAllMethods(ft, "updateMenuItemButton", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!inMainPanelLayout || !shortLabels()) return
                try {
                    val button = param.args[0] as? ViewGroup ?: return
                    val item = param.args[1] as? MenuItem ?: return
                    styleShortButton(button, button.getChildAt(0) as? TextView, item, false)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG telegram button failed: ${t.message}")
                }
            }
        })

        if (popupClass != null) {
            XposedBridge.hookAllMethods(popupClass, "layoutMainPanelItems", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    inMainPanelLayout = telegramOn()
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (!inMainPanelLayout || !noOverflow()) return
                        @Suppress("UNCHECKED_CAST")
                        val remaining = param.result as? List<MenuItem> ?: return
                        if (remaining.isEmpty()) return
                        val popup = param.thisObject
                        val outer = XposedHelpers.getObjectField(popup, "this\$0")
                        val context = XposedHelpers.getObjectField(popup, "mContext") as Context
                        val spacing = XposedHelpers.getIntField(popup, "mIconTextSpacing")
                        val panel = (runCatching { XposedHelpers.getObjectField(popup, "mMainPanelButtons") }.getOrNull()
                            ?: XposedHelpers.getObjectField(popup, "mMainPanel")) as ViewGroup
                        fillMainPanel(
                            panel = panel,
                            remaining = remaining,
                            toolbarWidth = param.args[1] as Int,
                            textOf = { v -> (v as? ViewGroup)?.getChildAt(0) as? TextView },
                            create = { item ->
                                XposedHelpers.callMethod(
                                    outer, "createMenuItemButton", context, item, spacing, false, false, false
                                ) as View
                            },
                            bind = { v, item -> XposedHelpers.callMethod(popup, "setButtonTagAndClickListener", v, item) },
                            remeasure = {
                                XposedHelpers.setObjectField(
                                    popup, "mMainPanelSize",
                                    XposedHelpers.callMethod(popup, "measure", XposedHelpers.getObjectField(popup, "mMainPanel"))
                                )
                            }
                        )
                        param.result = java.util.LinkedList<MenuItem>()
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG telegram no-overflow failed: ${t.message}")
                    } finally {
                        inMainPanelLayout = false
                    }
                }
            })

            XposedBridge.hookAllMethods(popupClass, "refreshCoordinatesAndOverflowDirection", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!telegramOn()) return
                    val mode = prefs.getString(K.POSITION, K.POSITION_DEFAULT)
                    if (mode == K.POSITION_DEFAULT) return
                    try {
                        applyPosition(param.thisObject, param.args[0] as Rect, mode)
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG telegram position failed: ${t.message}")
                    }
                }
            })
        }

        // Auto-copy for message text selection: showActions() runs once the selection settles.
        val helper = XposedHelpers.findClassIfExists(TG_SELECTION_HELPER, cl)
        if (helper != null) {
            XposedBridge.hookAllMethods(helper, "showActions", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!telegramOn() || !prefs.getBoolean(K.AUTOCOPY, false)) return
                    try {
                        val text = XposedHelpers.callMethod(param.thisObject, "getSelectedText") as? CharSequence ?: return
                        val anchor = XposedHelpers.getObjectField(param.thisObject, "textSelectionOverlay") as? View ?: return
                        copyText(anchor, text.toString())
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG telegram autocopy failed: ${t.message}")
                    }
                }
            })
            XposedBridge.hookAllMethods(helper, "clear", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    lastCopied = null
                }
            })
        } else {
            debug { "telegram TextSelectionHelper not found (obfuscated fork?)" }
        }
        debug { "telegram hooks installed" }
    }

    // ---------------------------------------------------------------------------------------
    // 7. EXPERIMENTAL: obfuscated Telegram forks (Plus Messenger, ...)
    //    Their toolbar classes are renamed, but the menu itself is a framework MenuBuilder
    //    (PopupMenu.getMenu()). Order = getOrder(), hidden = isVisible(), short label = getTitle().
    //    Position / no-overflow / two-line labels are not available this way.
    // ---------------------------------------------------------------------------------------

    private var inOriginalTitle = false

    private fun isTextMenuItem(item: Any): Boolean {
        val menu = runCatching { XposedHelpers.getObjectField(item, "mMenu") as? Menu }.getOrNull() ?: return false
        return menu.findItem(android.R.id.copy) != null || menu.findItem(android.R.id.paste) != null ||
            menu.findItem(android.R.id.selectAll) != null
    }

    private fun hookTelegramGeneric() {
        val cls = XposedHelpers.findClass(MENU_ITEM_IMPL, null)

        XposedHelpers.findAndHookMethod(cls, "getOrder", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (inOriginalTitle || !telegramOn() || !isTextMenuItem(param.thisObject)) return
                val item = param.thisObject as MenuItem
                val original = param.result as Int
                val key = withOriginalTitle { keyOf(item) }
                param.result = key?.let { rank()[it] } ?: (1000 + original)
            }
        })

        XposedHelpers.findAndHookMethod(cls, "isVisible", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.result != true || inOriginalTitle || !telegramOn()) return
                if (!isTextMenuItem(param.thisObject)) return
                val key = withOriginalTitle { keyOf(param.thisObject as MenuItem) } ?: return
                if (key in hidden()) param.result = false
            }
        })

        XposedHelpers.findAndHookMethod(cls, "getTitle", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (inOriginalTitle || !telegramOn() || !shortLabels()) return
                if (!isTextMenuItem(param.thisObject)) return
                param.result = withOriginalTitle { shortLabel(param.thisObject as MenuItem, oneLine = true) }
            }
        })
        debug { "telegram (generic MenuItem) hooks installed" }
    }

    private inline fun <T> withOriginalTitle(block: () -> T): T {
        val prev = inOriginalTitle
        inOriginalTitle = true
        try {
            return block()
        } finally {
            inOriginalTitle = prev
        }
    }

    private inline fun safe(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hook '$name' not installed: ${t.message}")
        }
    }
}
