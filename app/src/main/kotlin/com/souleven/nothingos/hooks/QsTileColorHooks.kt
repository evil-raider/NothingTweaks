package com.souleven.nothingos.hooks

import com.souleven.nothingos.MainHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * DIAGNOSTIC (observe-only) build for the Nothing OS Quick Settings colour bug.
 *
 * On-device field dump (Nothing Phone 3a, tiles white / working state) showed:
 *   colorInactive          = #00000000  (stock inactive base is TRANSPARENT)
 *   backgroundColor        = #00000000  (matches colorInactive at rest)
 *   colorActive            = #FFFFFFFF
 *   colorUnderCover        = #FFE8E7EF  (opaque light -> the visible inactive fill)
 *   backgroundOverlayColor = #14E1E2EC  (very low alpha sheen)
 *   backgroundUndercoverDrawable = LayerDrawable
 *
 * Conclusion: the visible inactive tile fill is Nothing's `colorUnderCover`
 * (painted on backgroundUndercoverDrawable), NOT the base backgroundColor.
 * Re-asserting backgroundColor could never fix the grey-blue revert, and
 * forcing a resolved theme colour turned tiles black. Both approaches dropped.
 *
 * This build does NOT modify anything. It only:
 *   1. Logs the watched inactive colour fields whenever they change, so the
 *      exact field + value of the grey-blue state is captured when the bug
 *      reproduces.
 *   2. One-time dumps colour/cover/background/tint/state method signatures of
 *      the Nothing tile classes, so we learn which method re-applies the fill.
 *
 * Zero regression risk: tiles behave exactly like stock (white, with the
 * intermittent grey-blue still possible) until we ship the targeted fix.
 */
class QsTileColorHooks : HookModule {

    private companion object {
        const val PREF_KEY = "pref_fix_qs_tile_color"
        const val STATE_INACTIVE = 1
        const val QS_TILE_VIEW = "com.android.systemui.qs.tileimpl.QSTileViewImpl"
        const val SNAPSHOT_LOG_LIMIT = 80

        val WATCH_FIELDS = arrayOf(
            "colorInactive",
            "backgroundColor",
            "colorUnderCover",
            "backgroundOverlayColor",
            "overlayColorInactive",
            "colorUnavailable"
        )

        @Volatile
        var firingLogged = false

        @Volatile
        var methodsDumped = false

        @Volatile
        var lastSnapshot: String? = null

        @Volatile
        var snapshotLogs = 0
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
                observe(param.thisObject)
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
        XposedBridge.log("${MainHook.TAG} QsTileColorHooks: [diag] installed $installed hook(s) on ${cls.name}")
    }

    private fun observe(view: Any?) {
        if (view == null) return
        try {
            if (!firingLogged) {
                firingLogged = true
                XposedBridge.log("${MainHook.TAG} QsTileColorHooks: [diag] firing on ${view.javaClass.name}")
            }
            if (!methodsDumped) {
                methodsDumped = true
                dumpMethods(view)
            }

            val lastState = try {
                XposedHelpers.getIntField(view, "lastState")
            } catch (_: Throwable) {
                return
            }
            if (lastState != STATE_INACTIVE) return

            val sb = StringBuilder()
            for (name in WATCH_FIELDS) {
                val value = try {
                    hex(XposedHelpers.getIntField(view, name))
                } catch (_: Throwable) {
                    "n/a"
                }
                if (sb.isNotEmpty()) sb.append("  ")
                sb.append(name).append('=').append(value)
            }
            val snapshot = sb.toString()

            if (snapshot != lastSnapshot && snapshotLogs < SNAPSHOT_LOG_LIMIT) {
                lastSnapshot = snapshot
                snapshotLogs++
                XposedBridge.log("${MainHook.TAG} QsTileColorHooks: [diag] inactive $snapshot")
            }
        } catch (_: Throwable) {
            // silent: never crash SystemUI
        }
    }

    /** One-time dump of colour/cover/background/tint/state methods on the tile classes. */
    private fun dumpMethods(view: Any) {
        try {
            var c: Class<*>? = view.javaClass
            while (c != null && c != Any::class.java) {
                val cn = c.name
                if (cn.contains("TwinButtonsTileView") || cn.contains("QSTileViewImpl")) {
                    for (m in c.declaredMethods) {
                        val ml = m.name.lowercase()
                        val interesting = ml.contains("color") || ml.contains("cover") ||
                            ml.contains("background") || ml.contains("tint") ||
                            ml.contains("resource") || ml.contains("state")
                        if (!interesting) continue
                        val params = m.parameterTypes.joinToString(",") { it.simpleName }
                        XposedBridge.log("${MainHook.TAG}   method ${c.simpleName}.${m.name}($params)")
                    }
                }
                c = c.superclass
            }
        } catch (_: Throwable) {
            // silent
        }
    }

    private fun hex(color: Int): String = "#%08X".format(color)
}
