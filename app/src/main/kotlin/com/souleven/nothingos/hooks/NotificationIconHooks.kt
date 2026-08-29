package com.souleven.nothingos.hooks

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val containerClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.NotificationIconContainer", lpparam.classLoader)
        if (containerClass != null) {

            try {
                XposedBridge.hookAllMethods(containerClass, "setMaxIconsAmount", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                        val maxIcons = maxIconsStr.toIntOrNull()
                        if (maxIcons != null) {
                            param.args[0] = maxIcons
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook setMaxIconsAmount: ${t.message}")
            }

            // Hook initResources to override AOD and Lockscreen limit
            try {
                XposedBridge.hookAllMethods(containerClass, "initResources", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                        val maxIcons = maxIconsStr.toIntOrNull()
                        if (maxIcons != null) {
                            XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnAod", maxIcons)
                            XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnLockscreen", maxIcons)
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook initResources: ${t.message}")
            }

            // Hook getIconLimit in NotificationIconsViewData (Android 14+ ViewModel flow)
            try {
                val dataClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData", lpparam.classLoader)
                if (dataClass != null) {
                    XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                            val maxIcons = maxIconsStr.toIntOrNull()
                            if (maxIcons != null) {
                                param.result = maxIcons
                            }
                        }
                    })
                }
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook getIconLimit: ${t.message}")
            }

            // Hook getLeftSideMinWidth in Nothing's own status bar content container so the
            // clock's width stops being reserved on the left once its slot is supposed to be
            // free. NTStatusBarContentExt only checks View.getVisibility() == VISIBLE, and most
            // clock-relocation mods (e.g. Iconify) move the clock via alpha/translationX while
            // keeping it VISIBLE, so this reservation silently caps how many icons actually fit
            // even after the icon-count limit above is lifted.
            try {
                val contentExtClass = XposedHelpers.findClassIfExists("com.nothing.systemui.statusbar.widgets.NTStatusBarContentExt", lpparam.classLoader)
                if (contentExtClass != null) {
                    XposedBridge.hookAllMethods(contentExtClass, "getLeftSideMinWidth", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                            val maxIcons = maxIconsStr.toIntOrNull()
                            if (maxIcons != null) {
                                val headsUp = XposedHelpers.getObjectField(param.thisObject, "mHeadsUp") as? View
                                val headsUpWidth = if (headsUp != null && headsUp.visibility == View.VISIBLE) {
                                    headsUp.measuredWidth
                                } else {
                                    0
                                }
                                param.result = headsUpWidth
                            }
                        }
                    })
                }
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook getLeftSideMinWidth: ${t.message}")
            }
        }
    }
}
