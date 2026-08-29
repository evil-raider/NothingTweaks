package com.souleven.nothingos.hooks

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {

    private val DOT_PADDING_PX = 2      // зазор точки (меньше = ближе к иконкам)
    private val BIND_HEADROOM = 20      // запас привязки, чтобы точка появлялась при >N
    @Volatile private var logCount = 0

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

    // Настройка — текстовое поле (хранится строкой). Читаем и парсим в число.
    private fun readMaxIcons(prefs: Prefs): Int? {
        return try {
            prefs.getString("pref_max_notif_icons", "")?.trim()?.toIntOrNull()?.takeIf { it in 1..50 }
        } catch (t: Throwable) {
            null
        }
    }

    private fun setIntSafe(o: Any, name: String, value: Int) {
        try { XposedHelpers.setIntField(o, name, value) } catch (t: Throwable) {}
    }
    private fun setFloatSafe(o: Any, name: String, value: Float) {
        try { XposedHelpers.setFloatField(o, name, value) } catch (t: Throwable) {}
    }
    private fun getIntStr(o: Any, name: String): String {
        return try { "" + XposedHelpers.getIntField(o, name) } catch (t: Throwable) { "NA" }
    }
    private fun getBoolStr(o: Any, name: String): String {
        return try { "" + XposedHelpers.getBooleanField(o, name) } catch (t: Throwable) { "NA" }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val containerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationIconContainer",
            lpparam.classLoader
        ) ?: return

        try {
            XposedBridge.hookAllMethods(containerClass, "calculateIconXTranslations", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    if (!isStatusBarIcons(v)) return   // AOD/локскрин/полка НЕ трогаем

                    val max = readMaxIcons(prefs)
                    if (max != null) {
                        setIntSafe(v, "mMaxStaticIcons", max)      // видно ровно N
                        setIntSafe(v, "mMaxIcons", BIND_HEADROOM)  // запас -> точка при >N
                    }
                    setIntSafe(v, "mDotPadding", DOT_PADDING_PX)   // точка вплотную
                    setFloatSafe(v, "mActualPaddingStart", 0f)     // убрать фантомный отступ

                    if (logCount < 12 && v is ViewGroup && v.childCount >= 3) {
                        logCount += 1
                        XposedBridge.log(
                            "NothingTweaks: STATE read=" + (max?.toString() ?: "NULL") +
                            " childCount=" + v.childCount +
                            " maxStatic=" + getIntStr(v, "mMaxStaticIcons") +
                            " maxIcons=" + getIntStr(v, "mMaxIcons") +
                            " dotPad=" + getIntStr(v, "mDotPadding") +
                            " staticLayout=" + getBoolStr(v, "mIsStaticLayout") +
                            " onLock=" + getBoolStr(v, "mOnLockScreen") +
                            " iconSize=" + getIntStr(v, "mIconSize")
                        )
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [NotificationIconHooks] calculateIconXTranslations failed: ${t.message}")
        }

        // getIconLimit / initResources НЕ трогаем — AOD остаётся стоковым и
        // центрируется. Если STATE покажет childCount, упирающийся в N (точки
        // нет) — тогда точечно поднимем привязку, это следующий шаг по данным.
    }
}
