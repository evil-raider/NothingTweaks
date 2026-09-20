package com.souleven.nothingos.hooks

import android.animation.Animator
import android.view.View
import com.souleven.nothingos.MainHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * Fixes the Nothing OS Quick Settings bug where inactive tiles turn grey-blue
 * after a theme switch (e.g. battery-saver dark theme -> back to light).
 *
 * ROOT CAUSE (proven by on-device field logging):
 *  - The visible inactive fill is Nothing's `colorUnderCover`; it recovers to
 *    the correct light value on its own after a theme switch.
 *  - The glitch is the base `backgroundColor`. For an inactive tile it must
 *    settle to `colorInactive` (#00000000, transparent) so the white undercover
 *    shows through. The tile animates backgroundColor via a reused ValueAnimator
 *    (AOSP `singleAnimator`). When a theme change (updateResources) lands mid
 *    fade, that animation is cancelled and backgroundColor FREEZES at an
 *    intermediate semi-transparent grey (observed #99C6C6CD), which sits over
 *    the white undercover -> the tile looks grey. Tapping starts a fresh
 *    animation that finishes at transparent, which is why a tap always fixed it.
 *
 * FIX (event-driven, no timers):
 *  - We listen to the tile's colour animator. `onAnimationEnd` fires both on a
 *    natural finish and on cancel. When it fires, if the tile is inactive and
 *    its base has not reached colorInactive, the fade was interrupted -> we snap
 *    the base to colorInactive. A normal fade ends exactly at colorInactive, so
 *    the check is a no-op and animations are never cut short.
 *  - The animator is found by TYPE (any Animator field), not by name, so this
 *    survives ROM renames. We attach our listener at most once per animator
 *    instance (tracked in a weak set).
 *  - If no animation is running when a state change lands, the colours are
 *    already final, so we run the same correction immediately.
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
        const val CORRECTION_LOG_LIMIT = 20

        @Volatile
        var firingLogged = false

        @Volatile
        var correctionLogs = 0

        // Animator fields per tile class (found by type once), and the set of
        // animator instances we already listen to. Everything here runs on the
        // SystemUI main thread, so no extra synchronisation is needed.
        val animatorFieldsCache = HashMap<Class<*>, List<Field>>()
        val listenedAnimators: MutableSet<Animator> =
            Collections.newSetFromMap(WeakHashMap<Animator, Boolean>())
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

            var anyRunning = false
            for (animator in collectAnimators(view)) {
                attachListenerOnce(view, animator)
                if (animator.isRunning) anyRunning = true
            }

            // With no animation in flight the colours are already final, so a
            // stale/frozen inactive base can be corrected right now.
            if (!anyRunning) correctInactiveBase(view, "static")
        } catch (_: Throwable) {
            // silent: never crash SystemUI
        }
    }

    private fun collectAnimators(view: View): List<Animator> {
        val fields = animatorFieldsCache.getOrPut(view.javaClass) {
            val list = ArrayList<Field>()
            var c: Class<*>? = view.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (Animator::class.java.isAssignableFrom(f.type)) {
                        try {
                            f.isAccessible = true
                            list.add(f)
                        } catch (_: Throwable) {
                        }
                    }
                }
                c = c.superclass
            }
            list
        }
        val result = ArrayList<Animator>()
        for (f in fields) {
            try {
                (f.get(view) as? Animator)?.let { result.add(it) }
            } catch (_: Throwable) {
            }
        }
        return result
    }

    private fun attachListenerOnce(view: View, animator: Animator) {
        if (!listenedAnimators.add(animator)) return
        try {
            animator.addListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator) = correctInactiveBase(view, "anim-end")
                override fun onAnimationCancel(animation: Animator) { /* onAnimationEnd follows */ }
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
        } catch (_: Throwable) {
            listenedAnimators.remove(animator)
        }
    }

    private fun correctInactiveBase(view: View, reason: String) {
        try {
            if (readIntOrNull(view, "lastState") != STATE_INACTIVE) return

            val target = readIntOrNull(view, "colorInactive") ?: return
            val current = readIntOrNull(view, "backgroundColor") ?: return
            // Fade reached its intended resting colour -> leave it alone.
            if (current == target) return

            // Inactive tile whose base never settled -> interrupted fade. Snap it.
            XposedHelpers.callMethod(view, "setColor", target)

            if (correctionLogs < CORRECTION_LOG_LIMIT) {
                correctionLogs++
                XposedBridge.log(
                    "${MainHook.TAG} QsTileColorHooks: settled inactive base ${hex(current)} -> ${hex(target)} ($reason)"
                )
            }
        } catch (_: Throwable) {
            // silent
        }
    }

    private fun readIntOrNull(view: Any, name: String): Int? = try {
        XposedHelpers.getIntField(view, name)
    } catch (_: Throwable) {
        null
    }

    private fun hex(color: Int): String = "#%08X".format(color)
}
