package com.souleven.nothingos.hooks

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.callbacks.XCallback
import java.util.WeakHashMap

class NotificationIconHooks : HookModule {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val containerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationIconContainer", lpparam.classLoader
        ) ?: run {
            // Later callbacks can refer to a plugin's different class loader. This does not
            // remove hooks already installed in the original SystemUI class loader.
            log("Skipping loader without NotificationIconContainer in ${lpparam.processName}")
            return
        }
        val reportedResets = mutableSetOf<String>()
        val limiter = NotificationIconLimitOverride(containerClass) { field, observed, requested ->
            if (reportedResets.add("$field:$observed:$requested")) {
                log("Reasserting $field: observed=$observed requested=$requested")
            }
        }
        if (limiter.fieldNames.isEmpty()) {
            log("No supported icon-limit fields found; override disabled")
            return
        }
        var loggedSetting: String? = null
        var failureLogged = false
        val snapshots = WeakHashMap<Any, MutableSet<String>>()

        fun readLimit(): Int? {
            val raw = prefs.getString("pref_max_notif_icons", "")
            val limit = NotificationIconLimitOverride.parseLimit(raw)
            val setting = when {
                raw.isBlank() -> "unset (stock)"
                limit == null -> "invalid (stock)"
                else -> limit.toString()
            }
            // Log blank/invalid initial state too, not just changes to a numeric value.
            if (setting != loggedSetting) {
                log("setting=$setting; fields=${limiter.fieldNames.joinToString()}")
                loggedSetting = setting
            }
            return limit
        }

        fun reportFailure(t: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                log("Could not apply/inspect icon limit: $t")
                XposedBridge.log(t)
            }
        }

        fun apply(container: Any, refreshed: Set<String> = emptySet()) {
            try {
                limiter.apply(container, readLimit(), refreshed)
            } catch (t: Throwable) {
                reportFailure(t)
            }
        }

        fun snapshot(container: Any) {
            try {
                val view = container as? ViewGroup ?: return
                if (!limiter.isStatic(container) || view.childCount < 5) return
                fun field(name: String): Any? = runCatching {
                    XposedHelpers.getObjectField(container, name)
                }.getOrNull()
                val id = if (view.id == View.NO_ID) "no_id" else runCatching {
                    view.resources.getResourceEntryName(view.id)
                }.getOrDefault(view.id.toString())
                val state = "id=$id children=${view.childCount} width=${view.width}" +
                    " measured=${view.measuredWidth} actualWidth=${field("mActualLayoutWidth")}" +
                    " iconSize=${field("mIconSize")} dot=${field("mIsShowingOverflowDot")}" +
                    " static=${field("mIsStaticLayout")} lock=${field("mOnLockScreen")}" +
                    " ${limiter.describe(container)}"
                val seen = snapshots.getOrPut(container) { mutableSetOf() }
                if (seen.size < 24 && seen.add(state)) log("Layout $state")
            } catch (t: Throwable) {
                reportFailure(t)
            }
        }

        fun hook(name: String, after: Boolean = false, refreshed: Set<String> = emptySet()): Int {
            return try {
                // LOWEST before-hooks run last, immediately before the real method, after
                // normal-priority before-hooks from other modules (Iconify is also installed).
                XposedBridge.hookAllMethods(containerClass, name, object : XC_MethodHook(XCallback.PRIORITY_LOWEST) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!after) apply(param.thisObject, refreshed)
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        if (after) apply(param.thisObject, refreshed)
                    }
                }).size
            } catch (t: Throwable) {
                log("Could not hook $name: $t")
                0
            }
        }

        readLimit()
        val setter = hook("setMaxIconsAmount", after = true, refreshed = setOf("mMaxIcons"))
        val speedBump = hook("setSpeedBumpIndex", after = true, refreshed = setOf("mSpeedBumpIndex"))
        val resources = hook("initResources", after = true, refreshed = setOf(
            "mMaxStaticIcons", "mMaxIconsOnAod", "mMaxIconsOnLockscreen"
        ))
        val measure = hook("onMeasure")
        val layout = hook("onLayout")
        val translations = hook("calculateIconXTranslations")
        // Observe after icon states were applied, and after normal-priority after-hooks.
        // Keep this separate from LOWEST before-hooks used for enforcement above.
        val diagnostics = try {
            XposedBridge.hookAllMethods(containerClass, "applyIconStates",
                object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!param.hasThrowable()) snapshot(param.thisObject)
                    }
                }).size
        } catch (t: Throwable) {
            log("Could not hook layout diagnostics: $t")
            0
        }
        log("Installed: setter=$setter speedBump=$speedBump resources=$resources " +
            "measure=$measure layout=$layout translations=$translations diagnostics=$diagnostics; fields=${limiter.fieldNames.joinToString()}")
        if (measure == 0 || layout == 0 || translations == 0) log("WARNING: layout enforcement incomplete on this ROM")
    }

    private fun log(message: String) = XposedBridge.log("NothingTweaks: [NotificationIconHooks] $message")
}
