package com.souleven.nothingos.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object SystemHooks {
    private const val KEY_ALLOW_180_ROTATION = "allow_180_rotation"

    fun init(classLoader: ClassLoader, prefs: Prefs) {
        val displayRotationClass = XposedHelpers.findClassIfExists("com.android.server.wm.DisplayRotation", classLoader)
        if (displayRotationClass != null) {
            try {
                XposedBridge.hookAllMethods(displayRotationClass, "getAllowAllRotations", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        prefs.forceReload()
                        if (prefs.getBoolean(KEY_ALLOW_180_ROTATION, false)) {
                            param.result = 1 // ALLOW_ALL_ROTATIONS_ENABLED
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [SystemHooks] FAILED to hook getAllowAllRotations: ${t.message}")
            }
        } else {
            XposedBridge.log("NothingTweaks: [SystemHooks] FAILED - DisplayRotation class not found!")
        }
    }
}
