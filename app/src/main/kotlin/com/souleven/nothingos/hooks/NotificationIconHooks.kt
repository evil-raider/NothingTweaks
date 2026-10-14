package com.souleven.nothingos.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val containerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationIconContainer", lpparam.classLoader
        ) ?: run {
            log("NotificationIconContainer not found in ${lpparam.processName}")
            return
        }
        val reportedResets = mutableSetOf<String>()
        val limiter = NotificationIconLimitOverride(containerClass) { field, observed, requested ->
            // One entry per field/process, not a flood on every notification animation frame.
            if (reportedResets.add(field)) log("Reasserting $field: observed=$observed requested=$requested")
        }
        if (limiter.fieldNames.isEmpty()) {
            log("No supported icon-limit fields found; override disabled")
            return
        }
        var loggedLimit: Int? = null
        var failureLogged = false

        fun apply(container: Any, refreshed: Set<String> = emptySet()) {
            try {
                val limit = NotificationIconLimitOverride.parseLimit(
                    prefs.getString("pref_max_notif_icons", "")
                )
                limiter.apply(container, limit, refreshed)
                if (limit != loggedLimit) {
                    log("limit=${limit ?: "stock"}; fields=${limiter.fieldNames.joinToString()}")
                    loggedLimit = limit
                }
            } catch (t: Throwable) {
                // A renamed OEM field must not crash SystemUI on every layout pass.
                if (!failureLogged) {
                    failureLogged = true
                    log("Could not apply icon limit: $t")
                    XposedBridge.log(t)
                }
            }
        }

        fun hook(name: String, after: Boolean = false, refreshed: Set<String> = emptySet()): Int {
            return try {
                XposedBridge.hookAllMethods(containerClass, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!after) apply(param.thisObject, refreshed)
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (after && !param.hasThrowable()) apply(param.thisObject, refreshed)
                    }
                }).size
            } catch (t: Throwable) {
                log("Could not hook $name: $t")
                0
            }
        }

        // Keep the real setter intact: we need its stock value when the preference is cleared.
        // This is only an early application point, NOT the sole enforcement point.
        val setter = hook("setMaxIconsAmount", after = true, refreshed = setOf("mMaxIcons"))
        val resources = hook("initResources", after = true, refreshed = setOf(
            "mMaxStaticIcons", "mMaxIconsOnAod", "mMaxIconsOnLockscreen"
        ))
        // Reassert at the consumers of the fields. A setter/getIconLimit hook alone can be
        // bypassed by OEM direct writes or ART inlining and misses the legacy static icon cap.
        // Measure must use the same limit as translations, otherwise width stays at 4 + dot.
        val measure = hook("onMeasure")
        val layout = hook("onLayout")
        val translations = hook("calculateIconXTranslations")
        log("Installed: setter=$setter resources=$resources measure=$measure layout=$layout translations=$translations; " +
            "fields=${limiter.fieldNames.joinToString()}")
        if (measure == 0 || layout == 0 || translations == 0) log("WARNING: layout enforcement incomplete on this ROM")
    }

    private fun log(message: String) = XposedBridge.log("NothingTweaks: [NotificationIconHooks] $message")
}
