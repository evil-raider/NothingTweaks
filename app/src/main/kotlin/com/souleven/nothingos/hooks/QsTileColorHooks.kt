package com.souleven.nothingos.hooks

import android.graphics.Color
import com.souleven.nothingos.MainHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Fixes a Nothing OS bug where inactive Quick Settings tiles occasionally lose
 * Iconify's white background and fall back to the stock grey-blue colour until
 * the tile is tapped once.
 *
 * Root cause: AOSP QSTileViewImpl caches the background colour (colorInactive)
 * and only re-applies it inside handleStateChanged WHEN THE STATE CHANGES
 * (`if (state.state != lastState ...)`). Iconify's light QS panel never hooks
 * the tile background itself — it relies on an RRO overlay that feeds
 * colorInactive at construction. On non-Pixel SystemUI various stock repaints
 * (Monet onColorsChanged, config changes, overlay toggles) reset the inactive
 * tile background without a state change, so the white is never restored until
 * the user toggles the tile (INACTIVE -> ACTIVE -> INACTIVE).
 *
 * Fix: an afterHook on handleStateChanged re-asserts the tile's OWN cached
 * colorInactive on every call, including same-state refreshes. Because the
 * value is read from the tile itself, this is harmless even without Iconify:
 * it just re-applies whatever colour the tile is already supposed to have.
 *
 * FRAGILE DEPENDENCIES (all wrapped in try/catch -> silent no-op on mismatch):
 *  - class com.android.systemui.qs.tileimpl.QSTileViewImpl;
 *  - field colorInactive, method setColor(int);
 *  - field `state` on QSTile.State (STATE_INACTIVE == 1).
 */
class QsTileColorHooks : HookModule {

    private companion object {
        const val PREF_KEY = "pref_fix_qs_tile_color"
        const val STATE_INACTIVE = 1
        const val QS_TILE_VIEW = "com.android.systemui.qs.tileimpl.QSTileViewImpl"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (!prefs.getBoolean(PREF_KEY, false)) return

        val cls = XposedHelpers.findClassIfExists(QS_TILE_VIEW, lpparam.classLoader)
        if (cls == null) {
            XposedBridge.log("${MainHook.TAG} QsTileColorHooks: $QS_TILE_VIEW not found")
            return
        }

        try {
            XposedBridge.hookAllMethods(cls, "handleStateChanged", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val state = param.args.getOrNull(0) ?: return
                        if (XposedHelpers.getIntField(state, "state") != STATE_INACTIVE) return
                        val color = try {
                            XposedHelpers.getIntField(param.thisObject, "colorInactive")
                        } catch (_: Throwable) {
                            Color.WHITE
                        }
                        XposedHelpers.callMethod(param.thisObject, "setColor", color)
                    } catch (_: Throwable) {
                        // silent: fragile ROM internals, never crash SystemUI
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("${MainHook.TAG} QsTileColorHooks failed: ${t.message}")
        }
    }
}
