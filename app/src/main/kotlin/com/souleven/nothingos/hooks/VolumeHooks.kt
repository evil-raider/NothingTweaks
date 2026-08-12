package com.souleven.nothingos.hooks

import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class VolumeHooks : HookModule {

    private val className = "com.android.systemui.volume.VolumeDialogImpl"

    private companion object {
        const val PREF_KEY = "volume_panel_timeout"
        const val MIN_TIMEOUT_MS = 500
        const val MAX_TIMEOUT_MS = 600_000
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val clazz = try {
            XposedHelpers.findClass(className, lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Volume] class not found: $className — ${t.message}")
            return
        }

        try {
            XposedHelpers.findAndHookMethod(clazz, "computeTimeoutH", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val wanted = readTimeoutPref(prefs)
                    if (wanted <= 0) return

                    // Leave the special cases (hover, safety warning, captions tooltip) at stock.
                    val hovering = try {
                        XposedHelpers.getBooleanField(param.thisObject, "mHovering")
                    } catch (t: Throwable) {
                        false
                    }
                    if (hovering) {
                        return
                    }

                    val orig = param.result as? Int ?: return
                    if (orig != wanted) {
                        param.result = wanted
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Volume] FAILED to hook computeTimeoutH: ${t.message}")
            XposedBridge.log(t)
        }
    }

    private fun readTimeoutPref(prefs: Prefs): Int {
        val raw = prefs.getString(PREF_KEY, "").trim()
        if (raw.isBlank()) return -1
        val parsed = raw.toIntOrNull()
        if (parsed == null) {
            XposedBridge.log("$TAG   [Volume] bad timeout pref value: '$raw' — ignoring")
            return -1
        }
        if (parsed <= 0) return -1
        val clamped = parsed.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        if (clamped != parsed) {
            XposedBridge.log("$TAG   [Volume] timeout ${parsed}ms clamped to ${clamped}ms")
        }
        return clamped
    }
}
