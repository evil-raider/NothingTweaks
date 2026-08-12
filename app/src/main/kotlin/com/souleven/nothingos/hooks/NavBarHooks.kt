package com.souleven.nothingos.hooks

import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class NavBarHooks : HookModule {

    companion object {
        const val KEY_TRANSPARENT_NAV_BAR = "transparent_nav_bar"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val classLoader = lpparam.classLoader

        val stashedHandleClass = XposedHelpers.findClassIfExists("com.android.launcher3.taskbar.StashedHandleView", classLoader)
        if (stashedHandleClass != null) {
            try {
                XposedBridge.hookAllConstructors(stashedHandleClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_TRANSPARENT_NAV_BAR, false)) return
                        
                        val view = param.thisObject as? android.view.View ?: return
                        view.background = null

                        try {
                            XposedHelpers.setIntField(param.thisObject, "mStashedHandleDarkColor", 0)
                            XposedHelpers.setIntField(param.thisObject, "mStashedHandleLightColor", 0)
                        } catch (t: Throwable) {}
                    }
                })
            } catch (t: Throwable) {}
        }
    }
}
