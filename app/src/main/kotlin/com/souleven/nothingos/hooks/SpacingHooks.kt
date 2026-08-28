package com.souleven.nothingos.hooks

import android.graphics.Insets
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class SpacingHooks : HookModule {
    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (prefs.getBoolean("pref_reduce_edge_padding", false)) {
            
            try {
                val insetsClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.layout.StatusBarContentInsetsProviderImpl", lpparam.classLoader)
                if (insetsClass != null) {
                    XposedBridge.hookAllMethods(insetsClass, "getStatusBarContentInsetsForCurrentRotation", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = Insets.of(20, 0, 20, 0)
                        }
                    })
                    XposedBridge.hookAllMethods(insetsClass, "getStatusBarContentInsetsForRotation", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = Insets.of(20, 0, 20, 0)
                        }
                    })
                }
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [SpacingHooks] FAILED to hook StatusBarContentInsetsProviderImpl: ${t.message}")
            }
            
            try {
                val statusBarViewClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.PhoneStatusBarView", lpparam.classLoader)
                if (statusBarViewClass != null) {
                    XposedBridge.hookAllMethods(statusBarViewClass, "getPaddingStart", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = 40
                        }
                    })
                    XposedBridge.hookAllMethods(statusBarViewClass, "getPaddingEnd", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = 40
                        }
                    })
                }
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [SpacingHooks] FAILED to hook PhoneStatusBarView: ${t.message}")
            }
        }
    }
}
