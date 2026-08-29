package com.souleven.nothingos.hooks

import android.content.Context
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object NotificationIconHooks {

    private const val PREF_KEY = "pref_max_notif_icons"
    private const val CONTAINER_CLASS =
        "com.android.systemui.statusbar.phone.NotificationIconContainer"
    private const val FALLBACK_STATUS_BAR_ID = 2131362411

    fun register(lpparam: XC_LoadPackage.LoadPackageParam, prefs: XSharedPreferences) {
        val maxIcons = prefs.getString(PREF_KEY, null)?.toIntOrNull() ?: return

        val containerClass = try {
            XposedHelpers.findClass(CONTAINER_CLASS, lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: container class not found: ${t.message}")
            return
        }

        // Hook 1 — setMaxIconsAmount -> значение из настроек
        try {
            XposedBridge.hookAllMethods(containerClass, "setMaxIconsAmount", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isNotEmpty() && param.args[0] is Int) {
                        param.args[0] = maxIcons
                    }
                }
            })
        } catch (t: Throwable) { XposedBridge.log("NothingTweaks: hook1 failed: ${t.message}") }

        // Hook 2 — initResources -> лимит на AOD/Lockscreen = значение из настроек
        try {
            XposedBridge.hookAllMethods(containerClass, "initResources", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try { XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnAod", maxIcons) } catch (e: Throwable) {}
                    try { XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnLockscreen", maxIcons) } catch (e: Throwable) {}
                }
            })
        } catch (t: Throwable) { XposedBridge.log("NothingTweaks: hook2 failed: ${t.message}") }

        // Hook 3 — getIconLimit -> значение из настроек
        try {
            XposedBridge.hookAllMethods(containerClass, "getIconLimit", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = maxIcons
                }
            })
        } catch (t: Throwable) { XposedBridge.log("NothingTweaks: hook3 failed: ${t.message}") }

        // Hook 4 — ГЛАВНЫЙ ФИКС ШИРИНЫ: getActualWidth()==0 -> реальная ширина (только статус-бар)
        try {
            XposedBridge.hookAllMethods(containerClass, "getActualWidth", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    val id = XposedHelpers.callMethod(v, "getId") as Int
                    if (id != statusBarId(v)) return
                    val actual = param.result as? Int ?: return
                    if (actual > 0) return
                    val real = XposedHelpers.callMethod(v, "getWidth") as Int
                    if (real > 0) param.result = real
                }
            })
        } catch (t: Throwable) { XposedBridge.log("NothingTweaks: hook4 failed: ${t.message}") }

        // Hook 5 — ЗАМЕР иерархии (NTX_PAR / NTX_SIB): кто режет/перекрывает
        try {
            XposedBridge.hookAllMethods(containerClass, "calculateIconXTranslations", object : XC_MethodHook() {
                private var last = 0L

                private fun idName(view: Any): String = try {
                    val id = XposedHelpers.callMethod(view, "getId") as Int
                    if (id <= 0) "no-id"
                    else {
                        val ctx = XposedHelpers.callMethod(view, "getContext") as Context
                        ctx.resources.getResourceEntryName(id)
                    }
                } catch (e: Throwable) { "?" }

                private fun dump(tag: String, view: Any): String {
                    val cn = view.javaClass.simpleName
                    val w = XposedHelpers.callMethod(view, "getWidth")
                    val l = XposedHelpers.callMethod(view, "getLeft")
                    val r = XposedHelpers.callMethod(view, "getRight")
                    val tx = XposedHelpers.callMethod(view, "getTranslationX")
                    val vis = XposedHelpers.callMethod(view, "getVisibility")
                    val clip = try { XposedHelpers.callMethod(view, "getClipBounds") } catch (e: Throwable) { null }
                    val cc = try { (XposedHelpers.callMethod(view, "getClipChildren") as Boolean).toString() } catch (e: Throwable) { "-" }
                    return "$tag[${idName(view)}/$cn w=$w l=$l r=$r tx=$tx vis=$vis clipCh=$cc clip=$clip]"
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject
                    val id = XposedHelpers.callMethod(v, "getId") as Int
                    if (id != statusBarId(v)) return
                    val now = System.currentTimeMillis()
                    if (now - last < 1000) return
                    last = now
                    try {
                        val sb = StringBuilder()
                        var cur: Any? = v
                        var lvl = 0
                        while (cur != null && lvl < 7) {
                            sb.append(dump("L$lvl", cur)).append("  ")
                            val p = try { XposedHelpers.callMethod(cur, "getParent") } catch (e: Throwable) { null }
                            if (p == null || p !is View) break
                            cur = p
                            lvl++
                        }
                        XposedBridge.log("NTX_PAR $sb")

                        val parent = XposedHelpers.callMethod(v, "getParent")
                        if (parent is ViewGroup) {
                            val n = XposedHelpers.callMethod(parent, "getChildCount") as Int
                            val sb2 = StringBuilder()
                            for (i in 0 until n) {
                                val ch = XposedHelpers.callMethod(parent, "getChildAt", i)
                                sb2.append("${idName(ch)}/${ch.javaClass.simpleName}(l=${XposedHelpers.callMethod(ch, "getLeft")},r=${XposedHelpers.callMethod(ch, "getRight")},vis=${XposedHelpers.callMethod(ch, "getVisibility")}) ")
                            }
                            XposedBridge.log("NTX_SIB $sb2")
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("NTX_PAR diag error: ${e.message}")
                    }
                }
            })
        } catch (t: Throwable) { XposedBridge.log("NothingTweaks: parent-diag hook failed: ${t.message}") }
    }

    private fun statusBarId(view: Any): Int {
        return try {
            val ctx = XposedHelpers.callMethod(view, "getContext") as Context
            val id = ctx.resources.getIdentifier("content", "id", ctx.packageName)
            if (id != 0) id else FALLBACK_STATUS_BAR_ID
        } catch (e: Throwable) { FALLBACK_STATUS_BAR_ID }
    }
}
