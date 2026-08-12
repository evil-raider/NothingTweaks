package com.souleven.nothingos.hooks

import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class LockScreenHooks : HookModule {

    private val className = "com.nothing.keyguard.KeyguardSecurityContainerControllerEx"

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val clazz = try {
            XposedHelpers.findClass(className, lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [LockScreen] class not found: $className — ${t.message}")
            return
        }

        // disable_power_off_verify -> getShouldPowerOffVerify() = false
        hookBool(clazz, "getShouldPowerOffVerify", "disable_power_off_verify", prefs, forceValue = false)
    }

    private fun hookBool(
        clazz: Class<*>,
        method: String,
        prefKey: String,
        prefs: Prefs,
        forceValue: Boolean
    ) {
        try {
            XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean(prefKey, false)) return
                    val orig = param.result as? Boolean ?: return
                    if (orig != forceValue) {
                        param.result = forceValue
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [LockScreen] FAILED to hook $method: ${t.message}")
            XposedBridge.log(t)
        }
    }
}
