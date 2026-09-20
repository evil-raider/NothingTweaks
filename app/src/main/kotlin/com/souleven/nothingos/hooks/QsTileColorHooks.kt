package com.souleven.nothingos.hooks

import android.view.View
import com.souleven.nothingos.MainHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Fixes the Nothing OS Quick Settings bug where inactive tiles turn grey-blue
 * after a theme switch (e.g. battery-saver dark theme -> back to light).
 *
 * ROOT CAUSE (proven by on-device field logging):
 *  - The visible inactive fill is Nothing's `colorUnderCover`; it recovers to
 *    the correct light value (#FFE8E7EF) on its own after a theme switch.
 *  - The actual glitch is the base `backgroundColor`. For an inactive tile it
 *    must settle to `colorInactive` (#00000000, transparent) so the white
 *    undercover shows through. But when a theme change (updateResources) lands
 *    mid tile-fade, the backgroundColor animation is interrupted and FREEZES at
 *    an intermediate semi-transparent grey (observed #99C6C6CD). That frozen
 *    grey sits on top of the white undercover -> the tile looks grey.
 *  - Tapping the tile starts a fresh animation that finishes at transparent,
 *    which is why a tap always "fixed" it.
 *
 * FIX:
 *  - After each inactive state change we wait SETTLE_DELAY_MS (long enough for a
 *    legitimate fade to finish) and then check the base again. If it still has
 *    not settled to colorInactive, the animation froze -> we call setColor with
 *    colorInactive to snap it to the correct transparent base.
 *  - Normal taps are untouched: their fade completes within the delay, so the
 *    deferred check finds the base already settled and does nothing.
 *  - We only ever write colorInactive (the tile's own resting colour), never a
 *    resolved theme attribute, so this cannot paint tiles black.
 *
 * All reflection is wrapped in try/catch -> silent no-op on ROM mismatch.
 */
class QsTileColorHooks : HookModule {

    private companion object {
        const val PREF_KEY = "pref_fix_qs_tile_color"
        const val STATE_INACTIVE = 1
        const val QS_TILE_VIEW = "com.android.systemui.qs.tileimpl.QSTileViewImpl"
        // Long enough that a real tile fade has finished; short enough to be
        // imperceptible when correcting a frozen tile.
        const val SETTLE_DELAY_MS = 450L
        const val CORRECTION_LOG_LIMIT = 20

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
                onStateChange(param.thisObject)
            }
        }

        var installed = 0
        for (method in arrayOf("handleStateChanged", "updateResources")) {
            try {
                installed += XposedBridge.hookAllMethods(cls, method, hook).size
            } catch (t: Throwable) {
                XposedBridge.log("${MainHook.TAG} QsTileColorHooks: $method hook failed: ${t.message}")
            }
        }
        XposedBridge.log("${MainHook.TAG} QsTileColorHooks: installed $installed hook(s) on ${cls.name}")
    }

    private fun onStateChange(obj: Any?) {
        val view = obj as? View ?: return
        try {
            if (!firingLogged) {
                firingLogged = true
                XposedBridge.log("${MainHook.TAG} QsTileColorHooks: hook firing on ${view.javaClass.name}")
            }

            if (readInt(view, "lastState") != STATE_INACTIVE) return

            val target = readIntOrNull(view, "colorInactive") ?: return
            val current = readIntOrNull(view, "backgroundColor") ?: return
            // Already at the resting inactive colour -> nothing to watch.
            if (current == target) return

            // The base is mid-transition (a normal fade) or frozen. Re-check
            // after the fade should have finished; only then decide.
            view.postDelayed({ settleInactiveBase(view) }, SETTLE_DELAY_MS)
        } catch (_: Throwable) {
            // silent: never crash SystemUI
        }
    }

    private fun settleInactiveBase(view: View) {
        try {
            // Tile may have become active again in the meantime.
            if (readInt(view, "lastState") != STATE_INACTIVE) return

            val target = readIntOrNull(view, "colorInactive") ?: return
            val current = readIntOrNull(view, "backgroundColor") ?: return
            // The fade finished correctly on its own -> leave it alone.
            if (current == target) return

            // Still off-target after the fade window: the animation froze.
            // Snap the base to the tile's own resting inactive colour.
            XposedHelpers.callMethod(view, "setColor", target)

            if (correctionLogs < CORRECTION_LOG_LIMIT) {
                correctionLogs++
                XposedBridge.log(
                    "${MainHook.TAG} QsTileColorHooks: settled frozen inactive base ${hex(current)} -> ${hex(target)}"
                )
            }
        } catch (_: Throwable) {
            // silent
        }
    }

    private fun readInt(view: Any, name: String): Int = XposedHelpers.getIntField(view, name)

    private fun readIntOrNull(view: Any, name: String): Int? = try {
        XposedHelpers.getIntField(view, name)
    } catch (_: Throwable) {
        null
    }

    private fun hex(color: Int): String = "#%08X".format(color)
}
