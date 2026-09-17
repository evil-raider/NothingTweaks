package com.souleven.nothingos.hooks

import com.souleven.nothingos.MainHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Fixes a Nothing OS bug where inactive Quick Settings tiles lose Iconify's
 * white background and fall back to the stock grey-blue colour.
 *
 * WHAT ON-DEVICE LOGS REVEALED (Nothing Phone 3a):
 *  - The real tile class is com.nothing.systemui.qs.TwinButtonsTileView, a
 *    Nothing subclass of QSTileViewImpl. Hooking the base class methods works
 *    because the subclass inherits them.
 *  - For an inactive tile the base `backgroundColor` field is #00000000
 *    (transparent). Nothing does NOT tint the base drawable for inactive tiles;
 *    the visible white comes from a layer behind it / Iconify's light panel
 *    showing through the transparent base.
 *  - Resolving R.attr.shadeInactive on this context returns a DARK colour
 *    (#FF2F3036), not Iconify's white. So the theme attribute is the wrong
 *    source here, and painting it turned every inactive tile black.
 *
 * CORRECTION STRATEGY:
 *  - The natural, correct inactive state is a transparent base. The grey-blue
 *    glitch is an unexpected opaque tint landing on the base drawable. So we
 *    only ever RESET the base tint back to transparent, and only when it isn't
 *    already transparent. In the normal case this is a no-op, so we never fight
 *    Nothing's own rendering and never force a wrong colour.
 *  - Runs on both handleStateChanged (state refreshes) and updateResources
 *    (theme/config changes).
 *
 * DIAGNOSTICS:
 *  - A one-time reflective dump of every int / Drawable / Color field across the
 *    tile's class hierarchy is logged under MainHook.TAG. If the grey-blue tint
 *    turns out to live on a separate Nothing field rather than the base
 *    backgroundColor, that dump pinpoints the exact field to target next.
 *
 * Everything is wrapped in try/catch -> silent no-op on ROM mismatch.
 */
class QsTileColorHooks : HookModule {

    private companion object {
        const val PREF_KEY = "pref_fix_qs_tile_color"
        const val STATE_INACTIVE = 1
        const val TRANSPARENT = 0
        const val QS_TILE_VIEW = "com.android.systemui.qs.tileimpl.QSTileViewImpl"
        const val CORRECTION_LOG_LIMIT = 8

        @Volatile
        var firingLogged = false

        @Volatile
        var dumped = false

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
                onTileRefresh(param.thisObject)
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

    private fun onTileRefresh(view: Any?) {
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

            if (!dumped) {
                dumped = true
                dumpColorState(view)
            }

            val current = try {
                XposedHelpers.getIntField(view, "backgroundColor")
            } catch (_: Throwable) {
                return
            }
            // Normal inactive state is already transparent -> nothing to do.
            // Only undo a stray opaque tint (the grey-blue glitch).
            if (current == TRANSPARENT) return

            XposedHelpers.callMethod(view, "setColor", TRANSPARENT)

            if (correctionLogs < CORRECTION_LOG_LIMIT) {
                correctionLogs++
                XposedBridge.log(
                    "${MainHook.TAG} QsTileColorHooks: reset inactive tile ${hex(current)} -> #00000000"
                )
            }
        } catch (_: Throwable) {
            // silent: never crash SystemUI on fragile ROM internals
        }
    }

    /**
     * One-time reflective dump of colour/drawable state across the tile's class
     * hierarchy, to design the real fix if resetting the base isn't enough.
     */
    private fun dumpColorState(view: Any) {
        try {
            XposedBridge.log("${MainHook.TAG} QsTileColorHooks: DUMP for ${view.javaClass.name}")
            var c: Class<*>? = view.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    try {
                        val type = f.type
                        val isInt = type == Int::class.javaPrimitiveType
                        val nameL = type.name.lowercase()
                        val interesting = isInt ||
                            nameL.contains("drawable") ||
                            nameL.contains("color")
                        if (!interesting) continue
                        f.isAccessible = true
                        val v = f.get(view)
                        val shown = if (isInt && v is Int) hex(v) else (v?.javaClass?.name ?: "null")
                        XposedBridge.log("${MainHook.TAG}   ${c.simpleName}.${f.name}: ${type.simpleName} = $shown")
                    } catch (_: Throwable) {
                        // skip unreadable field
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
