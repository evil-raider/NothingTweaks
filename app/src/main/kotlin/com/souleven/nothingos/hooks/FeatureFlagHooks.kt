package com.souleven.nothingos.hooks

import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class FeatureFlagHooks : HookModule {

    private val className = "com.nothing.NTFeaturesSystemUIUtils"

    companion object {
        const val STATE_SKIP = "skip"
        const val STATE_TRUE = "true"
        const val STATE_FALSE = "false"

        fun prefKeyFor(method: String) = "flag_$method"

        const val KEY_WATERMARK_FORCE_ON = "watermark_force_on"

        const val KEY_RECORD_LIMIT = "record_limit"
        const val RECORD_LIMIT_OFF = "off"
        const val RECORD_LIMIT_720P = "720p"
        const val RECORD_LIMIT_60FPS_1080P = "60fps1080p"
    }

    private val methods = listOf(
        "isEnableWatermark",
        "isScreenRecordBy720P",
        "isScreenRecordByLimit60FpsAnd1080P",
        "isSupportSteplessVolume",
        "isSupportSupperVolume",
        "isSupportPalmTouchSleep",
        "isSupportFlipToRecord"
    )

    private fun stateFor(method: String, prefs: Prefs): String = when (method) {
        "isEnableWatermark" ->
            if (prefs.getBoolean(KEY_WATERMARK_FORCE_ON, false)) STATE_TRUE else STATE_SKIP

        "isScreenRecordBy720P" -> when (prefs.getString(KEY_RECORD_LIMIT, RECORD_LIMIT_OFF)) {
            RECORD_LIMIT_720P -> STATE_TRUE
            RECORD_LIMIT_60FPS_1080P -> STATE_FALSE
            else -> STATE_SKIP
        }

        "isScreenRecordByLimit60FpsAnd1080P" -> when (prefs.getString(KEY_RECORD_LIMIT, RECORD_LIMIT_OFF)) {
            RECORD_LIMIT_60FPS_1080P -> STATE_TRUE
            RECORD_LIMIT_720P -> STATE_FALSE
            else -> STATE_SKIP
        }

        else -> if (prefs.getBoolean(prefKeyFor(method), false)) STATE_TRUE else STATE_SKIP
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val clazz = try {
            XposedHelpers.findClass(className, lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [FeatureFlags] class not found: $className — ${t.message}")
            return
        }

        var installed = 0
        var failed = 0
        for (method in methods) {
            try {
                XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = stateFor(method, prefs)
                        if (state == STATE_SKIP) return

                        val forced = when (state) {
                            STATE_TRUE -> true
                            STATE_FALSE -> false
                            else -> {
                                XposedBridge.log("$TAG   [FeatureFlags] $method(): unknown state '$state', skipping")
                                return
                            }
                        }

                        val orig = param.result as? Boolean
                        if (orig == null) {
                            XposedBridge.log("$TAG   [FeatureFlags] $method(): result was not Boolean, skipping")
                            return
                        }
                        if (orig != forced) {
                            // XposedBridge.log("$TAG   [FeatureFlags] $method(): orig=$orig -> forced=$forced")
                            param.result = forced
                        }
                    }
                })
                installed++
            } catch (t: Throwable) {
                failed++
                XposedBridge.log("$TAG   [FeatureFlags] FAILED to hook $method: ${t.message}")
            }
        }
    }
}
