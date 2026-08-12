package com.souleven.nothingos.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class MiscHooks : HookModule {

    companion object {
        const val KEY_TEMP_WARNING_BYPASS = "temp_warning_bypass"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val classLoader = lpparam.classLoader

        val powerUiClass = XposedHelpers.findClassIfExists("com.nothing.systemui.power.PowerUIEx", classLoader)
        if (powerUiClass != null) {
            try {
                XposedHelpers.findAndHookMethod(powerUiClass, "updateHighTemperatureWarning", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (prefs.getBoolean(KEY_TEMP_WARNING_BYPASS, false)) {
                            param.result = null
                        }
                    }
                })
            } catch (t: Throwable) {}
        }
    }
}
