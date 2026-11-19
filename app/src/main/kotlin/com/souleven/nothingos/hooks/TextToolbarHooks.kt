package com.souleven.nothingos.hooks

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Point
import android.graphics.Rect
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.souleven.nothingos.hooks.TextToolbarKeys as K
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONObject
import java.util.Locale

/**
 * Text selection toolbar ("Cut / Copy / Paste ..." floating menu) tweaks.
 *
 * The toolbar is built and drawn inside every app process by framework classes
 * (FloatingToolbar / LocalFloatingToolbarPopup / Editor), so these hooks only work
 * in apps that are enabled in the module scope in LSPosed.
 *
 * Features: custom order, hidden items, short square labels, no overflow ("⋮"),
 * custom position. Selection never writes to the clipboard; Copy remains an explicit action.
 */
class TextToolbarHooks : HookModule {

    companion object {
        private const val TAG = "NothingTweaks:TextToolbar"
        private const val FT_CLASS = "com.android.internal.widget.floatingtoolbar.FloatingToolbar"
        private const val POPUP_CLASS = "com.android.internal.widget.floatingtoolbar.LocalFloatingToolbarPopup"

        @Volatile
        private var installed = false
    }

    private lateinit var prefs: Prefs

    /** True while LocalFloatingToolbarPopup.layoutMainPanelItems is running (UI thread only). */
    private var inMainPanelLayout = false

    private var cachedLabelsRaw: String? = null
    private var cachedLabels: Map<String, String> = emptyMap()
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
    }

    // ---------------------------------------------------------------------------------------
    // Settings helpers
    // ---------------------------------------------------------------------------------------

    private fun enabled() = prefs.getBoolean(K.ENABLED, false)
    private fun shortLabels() = prefs.getString(K.LABEL_MODE, K.LABEL_MODE_FULL) == K.LABEL_MODE_SHORT
    private fun noOverflow() = prefs.getBoolean(K.NO_OVERFLOW, false)

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
        // Fallback for apps that build the menu with their own ids (e.g. Chromium/WebView).
        val title = item.title?.toString()?.trim() ?: return null
        return systemTitleMap()[title]
    }

    private val appIdCache = HashMap<Int, String?>()

    private fun appIdKey(id: Int): String? {
        if (id == 0 || id == -1) return null
        return appIdCache.getOrPut(id) {
            val name = runCatching {
                android.app.AndroidAppHelper.currentApplication()?.resources?.getResourceEntryName(id)
            }.getOrNull()?.lowercase() ?: return@getOrPut null
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

    // ---------------------------------------------------------------------------------------
    // 1. Order + hidden items: FloatingToolbar.doShow()
    // ---------------------------------------------------------------------------------------

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

                    val hidden = K.parseList(prefs.getString(K.HIDDEN, "")).toHashSet()
                    var items: MutableList<MenuItem> =
                        all.filterTo(ArrayList()) { item -> keyOf(item)?.let { it !in hidden } ?: true }
                    if (items.isEmpty()) items = all // never show an empty toolbar

                    val rank = K.parseList(prefs.getString(K.ORDER, ""))
                        .withIndex().associate { it.value to it.index }
                    val keepOverflowRule = !noOverflow()
                    items.sortWith { a, b ->
                        if (keepOverflowRule) {
                            val ao = a.flag("requiresOverflow")
                            val bo = b.flag("requiresOverflow")
                            if (ao != bo) return@sortWith if (ao) 1 else -1
                        }
                        val ra = keyOf(a)?.let { rank[it] } ?: Int.MAX_VALUE
                        val rb = keyOf(b)?.let { rank[it] } ?: Int.MAX_VALUE
                        if (ra != rb) ra.compareTo(rb) else a.order.compareTo(b.order)
                    }

                    val popup = XposedHelpers.getObjectField(ft, "mPopup")
                    XposedHelpers.callMethod(
                        popup, "show", items,
                        XposedHelpers.getObjectField(ft, "mMenuItemClickListener"),
                        XposedHelpers.getObjectField(ft, "mContentRect")
                    )
                    param.result = null
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
                    styleShortButton(button, item, showIcon, textId)
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
                    val toolbarWidth = param.args[1] as Int
                    putEverythingIntoMainPanel(param.thisObject, popupClass, remaining, toolbarWidth, textId)
                    param.result = ArrayList<MenuItem>()
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG no-overflow failed: ${t.message}")
                } finally {
                    inMainPanelLayout = false
                }
            }
        })
    }

    private fun twoLines() = prefs.getString(K.LABEL_LINES, "1") == "2"

    /**
     * Short label. One line: first N letters of the first word ("ВСТА").
     * Two lines: one word is split into two chunks ("ВСТА\nВИТЬ"), several words give
     * first + last word ("ВСТА\nТЕКС"). Custom labels may use "|" as a line break.
     */
    private fun shortLabel(item: MenuItem): String {
        val upper = prefs.getBoolean(K.LABEL_UPPER, true)
        fun finish(label: String) = if (upper) label.uppercase() else label
        keyOf(item)?.let { key ->
            customLabels()[key]?.let { return finish(it.replace("|", "\n")) }
        }
        val len = prefs.getString(K.LABEL_LEN, "4").toIntOrNull()?.coerceIn(1, 8) ?: 4
        val title = item.title?.toString().orEmpty()
            .replace("\u2026", "").replace("...", "").trim()
        val words = title.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val first = words.firstOrNull().orEmpty()
        if (!twoLines()) return finish(first.take(len))
        val label = when {
            words.size >= 2 -> first.take(len) + "\n" + words.last().take(len)
            first.length > len -> first.take(len) + "\n" + first.drop(len).take(len)
            else -> first
        }
        return finish(label)
    }

    /** Text size in sp, or null to keep the original size. */
    private fun labelSizeSp(twoLineLabel: Boolean): Float? {
        prefs.getString(K.LABEL_SIZE, "auto").toFloatOrNull()?.let { return it.coerceIn(8f, 20f) }
        return if (twoLineLabel) 13f else null
    }

    private fun styleShortButton(button: View, item: MenuItem, showIcon: Boolean, textId: Int) {
        val dm = button.resources.displayMetrics
        val text = if (textId != 0) button.findViewById<TextView>(textId) else null
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
                labelSizeSp(twoLineLabel)?.let { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, it) }
            }
        }
        val pad = ((if (twoLineLabel) 2 else 4) * dm.density).toInt()
        button.setPaddingRelative(pad, button.paddingTop, pad, button.paddingBottom)
        button.minimumWidth = (48 * dm.density).toInt()
        (button as? LinearLayout)?.gravity = Gravity.CENTER
    }

    private fun putEverythingIntoMainPanel(
        popup: Any, popupClass: Class<*>, remaining: List<MenuItem>, toolbarWidth: Int, textId: Int
    ) {
        val mainPanel = XposedHelpers.getObjectField(popup, "mMainPanel") as ViewGroup
        val context = XposedHelpers.getObjectField(popup, "mContext") as Context
        val spacing = XposedHelpers.getIntField(popup, "mIconTextSpacing")

        for (item in remaining) {
            val button = XposedHelpers.callStaticMethod(
                popupClass, "createMenuItemButton", context, item, spacing, false
            ) as View
            (button as? LinearLayout)?.gravity = Gravity.CENTER
            XposedHelpers.callMethod(popup, "setButtonTagAndClickListener", button, item)
            runCatching { button.tooltipText = item.title }
            mainPanel.addView(button)
        }
        mainPanel.setPaddingRelative(0, 0, 0, 0) // no space reserved for the overflow button

        // Fit all buttons into the toolbar width; shrink evenly if needed.
        val count = mainPanel.childCount
        val widths = IntArray(count)
        var total = 0
        for (i in 0 until count) {
            val child = mainPanel.getChildAt(i)
            child.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            widths[i] = child.measuredWidth
            total += widths[i]
        }
        val shrink = total > toolbarWidth
        val each = toolbarWidth / count.coerceAtLeast(1)
        for (i in 0 until count) {
            val child = mainPanel.getChildAt(i)
            child.minimumWidth = 0
            val lp = child.layoutParams ?: ViewGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.width = if (shrink) each else widths[i]
            child.layoutParams = lp
            if (shrink && textId != 0) {
                child.findViewById<TextView>(textId)?.ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
        val size = XposedHelpers.callStaticMethod(popupClass, "measure", mainPanel)
        XposedHelpers.setObjectField(popup, "mMainPanelSize", size)
    }

    // ---------------------------------------------------------------------------------------
    // 3. Position: LocalFloatingToolbarPopup.refreshCoordinatesAndOverflowDirection(Rect)
    // ---------------------------------------------------------------------------------------

    private fun hookPosition() {
        val popupClass = XposedHelpers.findClass(POPUP_CLASS, null)
        XposedBridge.hookAllMethods(popupClass, "refreshCoordinatesAndOverflowDirection", object : XC_MethodHook() {
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
        })
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

        // Vertical change only for a single-row toolbar; with an overflow panel AOSP also
        // decides the overflow opening direction, which we leave alone.
        if (hasOverflow) return
        val margin = 2 * XposedHelpers.getIntField(popup, "mMarginVertical")
        val height = XposedHelpers.getIntField(popup, "mLineHeight") + margin
        val fitsAbove = content.top - viewPort.top >= height
        val fitsBelow = viewPort.bottom - content.bottom >= height
        val wantBelow = mode == K.POSITION_BELOW || mode == K.POSITION_BELOW_RIGHT
        val y = when {
            wantBelow && fitsBelow -> content.bottom
            !wantBelow && fitsAbove -> content.top - height
            else -> return // keep the AOSP fallback
        }
        coords.y = (y - windowTop).coerceAtLeast(0)
    }

    private inline fun safe(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hook '$name' not installed: ${t.message}")
        }
    }
}
