package com.souleven.nothingos.hooks

import android.content.Context
import com.souleven.nothingos.MainHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Fixes a Nothing OS bug where inactive Quick Settings tiles lose Iconify's
 * white background and fall back to the stock grey-blue colour until the tile
 * is tapped once. Most reliably reproduced by a theme switch (e.g. battery
 * saver forcing dark theme, then returning to the light theme).
 *
 * Root cause (see AOSP QSTileViewImpl):
 *  - colorActive/colorInactive/colorUnavailable are `val`s resolved ONCE in the
 *    constructor from theme attrs (R.attr.shadeInactive etc).
 *  - handleStateChanged only re-applies colours WHEN THE STATE CHANGES
 *    (`if (state.state != lastState ...)`).
 *  - A theme/config change goes through onConfigurationChanged -> updateResources(),
 *    NOT handleStateChanged, and re-applies the stored backgroundColor.
 *  - Iconify's white inactive fill comes from an RRO overlay feeding
 *    shadeInactive. During a theme transition the tile can be (re)constructed
 *    while the light overlay isn't applied yet, so its cached colorInactive is
 *    the stock grey-blue and is never corrected without a state change.
 *
 * Fix strategy:
 *  1. Hook BOTH handleStateChanged (self-heals on any state refresh) AND
 *     updateResources (catches theme/config changes).
 *  2. Re-resolve shadeInactive LIVE from the tile's current theme instead of
 *     trusting the cached colorInactive val, so we pick up Iconify's overlay
 *     once it has settled. Fall back to the cached field (never a hard-coded
 *     white, which would wrongly whiten inactive tiles in dark theme).
 *  3. Only repaint when the tile is INACTIVE and the colour actually differs.
 *
 * Everything is wrapped in try/catch -> silent no-op on ROM mismatch, never
 * crashes SystemUI. Diagnostic lines are logged under MainHook.TAG so it is
 * possible to tell from LSPosed logs whether the hook loaded, on which class it
 * fires, and when it actually repaints a tile.
 */
class QsTileColorHooks : HookModule {

    private companion object {
        const val PREF_KEY = "pref_fix_qs_tile_color"
        const val STATE_INACTIVE = 1
        const val QS_TILE_VIEW = "com.android.systemui.qs.tileimpl.QSTileViewImpl"
        const val SETTINGS_UTILS = "com.android.settingslib.Utils"
        const val CORRECTION_LOG_LIMIT = 12

        @Volatile
        var firingLogged = false

        @Volatile
        var correctionLogs = 0
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (!prefs.getBoolean(PREF_KEY, false)) return

        val cls = XposedHelpers.findClassIfExists(QS_TILE_VIEW, lpparam.classLoader)
        if (cls == null) {
            XposedBridge.log("${MainHook.TAG} QsTileColorHooks: $QS_TILE_VIEW not found")
            return
        }

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                reassertInactiveColor(param.thisObject, lpparam.classLoader)
            }
        }

        var installed = 0
        try {
            installed += XposedBridge.hookAllMethods(cls, "handleStateChanged", hook).size
        } catch (t: Throwable) {
            XposedBridge.log("${MainHook.TAG} QsTileColorHooks: handleStateChanged hook failed: ${t.message}")
        }
        try {
            installed += XposedBridge.hookAllMethods(cls, "updateResources", hook).size
        } catch (t: Throwable) {
            XposedBridge.log("${MainHook.TAG} QsTileColorHooks: updateResources hook failed: ${t.message}")
        }

        XposedBridge.log("${MainHook.TAG} QsTileColorHooks: installed $installed hook(s) on ${cls.name}")
    }

    /**
     * Re-asserts the correct inactive background colour on the given tile view.
     * No-op unless the tile is currently INACTIVE and the applied colour differs
     * from the freshly resolved target.
     */
    private fun reassertInactiveColor(view: Any?, classLoader: ClassLoader) {
        if (view == null) return
        try {
            if (!firingLogged) {
                firingLogged = true
                XposedBridge.log("${MainHook.TAG} QsTileColorHooks: hook firing on ${view.javaClass.name}")
            }

            val lastState = try {
                XposedHelpers.getIntField(view, "lastState")
            } catch (_: Throwable) {
                return
            }
            if (lastState != STATE_INACTIVE) return

            val target = resolveInactiveColor(view, classLoader)
                ?: try {
                    XposedHelpers.getIntField(view, "colorInactive")
                } catch (_: Throwable) {
                    return
                }

            val current = try {
                XposedHelpers.getIntField(view, "backgroundColor")
            } catch (_: Throwable) {
                Int.MIN_VALUE
            }
            if (current == target) return

            XposedHelpers.callMethod(view, "setColor", target)

            if (correctionLogs < CORRECTION_LOG_LIMIT) {
                correctionLogs++
                XposedBridge.log(
                    "${MainHook.TAG} QsTileColorHooks: repainted inactive tile " +
                        "${hex(current)} -> ${hex(target)}"
                )
            }
        } catch (_: Throwable) {
            // silent: never crash SystemUI on fragile ROM internals
        }
    }

    /**
     * Freshly resolves R.attr.shadeInactive from the tile's *current* theme,
     * exactly like QSTileViewImpl's constructor. Reading it live (instead of the
     * cached colorInactive val) fixes the theme-switch race where the tile was
     * constructed while Iconify's light overlay wasn't applied yet. Returns null
     * on any mismatch so the caller falls back to the cached field.
     */
    private fun resolveInactiveColor(view: Any, classLoader: ClassLoader): Int? {
        return try {
            val ctx = XposedHelpers.callMethod(view, "getContext") as? Context ?: return null
            val attrId = ctx.resources.getIdentifier("shadeInactive", "attr", "com.android.systemui")
            if (attrId == 0) return null
            val utils = XposedHelpers.findClassIfExists(SETTINGS_UTILS, classLoader) ?: return null
            XposedHelpers.callStaticMethod(utils, "getColorAttrDefaultColor", ctx, attrId) as? Int
        } catch (_: Throwable) {
            null
        }
    }

    private fun hex(color: Int): String = "#%08X".format(color)
}
