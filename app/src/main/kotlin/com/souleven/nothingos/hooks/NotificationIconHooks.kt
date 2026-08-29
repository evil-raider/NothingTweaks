package com.souleven.nothingos.hooks

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {

    // Зазор точки переполнения (px). 0 = вплотную к последней иконке.
    private val DOT_PADDING_PX = 2

    private fun isStatusBarIcons(obj: Any?): Boolean {
        if (obj !is View) return false
        return try {
            val id = obj.id
            if (id == View.NO_ID) false
            else obj.resources.getResourceEntryName(id) == "notificationIcons"
        } catch (t: Throwable) {
            false
        }
    }

    private fun readMaxIcons(prefs: Prefs): Int? {
        return prefs.getString("pref_max_notif_icons", "").toIntOrNull()
    }

    private fun setIntSafe(o: Any, name: String, value: Int) {
        try { XposedHelpers.setIntField(o, name, value) } catch (t: Throwable) {}
    }

    private fun setFloatSafe(o: Any, name: String, value: Float) {
        try { XposedHelpers.setFloatField(o, name, value) } catch (t: Throwable) {}
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val containerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationIconContainer",
            lpparam.classLoader
        )
        if (containerClass != null) {

            // (1) Лимит статус-бара = настройка. ТОЛЬКО notificationIcons.
            try {
                XposedBridge.hookAllMethods(containerClass, "setMaxIconsAmount", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isStatusBarIcons(param.thisObject)) return
                        val max = readMaxIcons(prefs) ?: return
                        if (param.args.isNotEmpty()) param.args[0] = max
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] setMaxIconsAmount failed: ${t.message}")
            }

            // (2) На каждом пересчёте раскладки статус-бара: счётчик=N (точка
            //     встаёт вплотную по логике AOSP), поджать точку, убрать
            //     фантомный стартовый отступ от уехавших часов.
            try {
                XposedBridge.hookAllMethods(containerClass, "calculateIconXTranslations", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject
                        if (!isStatusBarIcons(v)) return
                        val max = readMaxIcons(prefs)
                        if (max != null) {
                            setIntSafe(v, "mMaxStaticIcons", max)
                            setIntSafe(v, "mMaxIcons", max)
                        }
                        setIntSafe(v, "mDotPadding", DOT_PADDING_PX)
                        setFloatSafe(v, "mActualPaddingStart", 0f)
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] calculateIconXTranslations failed: ${t.message}")
            }

            // (3) getIconLimit — сколько иконок ВООБЩЕ привязывать (Android 14+).
            //     Оставляем глобально: AOD ограничен своим stock mMaxIconsOnAod
            //     и покажет «N + точка», как в оригинале.
            try {
                val dataClass = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData",
                    lpparam.classLoader
                )
                if (dataClass != null) {
                    XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val max = readMaxIcons(prefs) ?: return
                            param.result = max
                        }
                    })
                }
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] getIconLimit failed: ${t.message}")
            }

            // ВАЖНО: хук initResources, поднимавший mMaxIconsOnAod /
            // mMaxIconsOnLockscreen, УБРАН НАМЕРЕННО — именно он ломал
            // центрирование на AOD. Теперь AOD/локскрин = сток.
        }
    }
}
