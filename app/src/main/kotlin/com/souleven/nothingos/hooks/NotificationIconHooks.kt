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
                        val maxIcons = prefs.getString("pref_max_notif_icons", "").toIntOrNull()
                        if (maxIcons != null) {
                            param.args[0] = maxIcons
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook setMaxIconsAmount: ${t.message}")
            }

            try {
                XposedBridge.hookAllMethods(containerClass, "initResources", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val maxIcons = prefs.getString("pref_max_notif_icons", "").toIntOrNull()
                        if (maxIcons != null) {
                            XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnAod", maxIcons)
                            XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnLockscreen", maxIcons)
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook initResources: ${t.message}")
            }

            try {
                val dataClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData", lpparam.classLoader)
                if (dataClass != null) {
                    XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val maxIcons = prefs.getString("pref_max_notif_icons", "").toIntOrNull()
                            if (maxIcons != null) {
                                param.result = maxIcons
                            }
                        }
                    })
                }
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook getIconLimit: ${t.message}")
            }

            // Исправленный фикс ширины + диагностика.
            // Оригинальные левые часы (mClock, ребёнок status_bar_start_side_container)
            // продолжают занимать ширину даже после того, как мод переноса (Iconify) визуально
            // увёл их вправо — из-за этого ряд иконок сжимается, и подъём лимита даёт
            // "4 иконки + точка". Схлопываем именно этот левый инстанс часов в GONE, но
            // ТОЛЬКО если он уже скрыт (alpha 0 или не VISIBLE), чтобы реально видимые/
            // перенесённые часы не пострадали. Лог NTX_DIAG показывает фактическое состояние.
            try {
                val contentExtClass = XposedHelpers.findClassIfExists("com.nothing.systemui.statusbar.widgets.NTStatusBarContentExt", lpparam.classLoader)
                if (contentExtClass != null) {
                    XposedBridge.hookAllMethods(contentExtClass, "onMeasure", object : XC_MethodHook() {
                        private var diagCount = 0
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val maxIcons = prefs.getString("pref_max_notif_icons", "").toIntOrNull() ?: return
                            val clock = XposedHelpers.getObjectField(param.thisObject, "mClock") as? View ?: return

                            if (diagCount < 10) {
                                diagCount++
                                XposedBridge.log("NTX_DIAG clock cls=${clock.javaClass.name} vis=${clock.visibility} alpha=${clock.alpha} txX=${clock.translationX} mW=${clock.measuredWidth} w=${clock.width}")
                            }

                            if ((clock.alpha == 0f || clock.visibility != View.VISIBLE) && clock.visibility != View.GONE) {
                                clock.visibility = View.GONE
                            }
                        }
                    })
                }
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook NTStatusBarContentExt.onMeasure: ${t.message}")
            }
        }
    }
}
