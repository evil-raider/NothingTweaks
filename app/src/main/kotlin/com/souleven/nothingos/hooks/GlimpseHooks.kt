package com.souleven.nothingos.hooks

import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class GlimpseHooks : HookModule {

    private val className = "com.nothing.systemui.glimpse.NTGlimpseController"

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val clazz = try {
            XposedHelpers.findClass(className, lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Glimpse] class not found: $className — ${t.message}")
            return
        }

        try {
            XposedHelpers.findAndHookMethod(clazz, "isUseAdsWallpaper", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean("disable_glimpse_ads", false)) return
                    val orig = param.result as? Boolean ?: return
                    if (orig) {
                        param.result = false
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Glimpse] FAILED to hook isUseAdsWallpaper: ${t.message}")
            XposedBridge.log(t)
        }
    }
}
