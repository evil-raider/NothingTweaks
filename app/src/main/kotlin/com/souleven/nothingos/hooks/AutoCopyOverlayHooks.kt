package com.souleven.nothingos.hooks

import android.app.AndroidAppHelper
import android.content.ClipboardManager
import android.content.Context
import com.souleven.nothingos.hooks.TextToolbarKeys as K
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.Collections

/**
 * SystemUI side of auto-copy: hides the clipboard overlay (Android 13+) for text that was
 * copied automatically by [TextToolbarHooks], unless the user chose the system feedback.
 *
 * Nothing OS does not use the AOSP ClipboardListener class name, so instead of hooking a
 * fixed class we hook whatever listener SystemUI registers with ClipboardManager.
 */
class AutoCopyOverlayHooks : HookModule {

    private val hookedClasses = Collections.synchronizedSet(HashSet<Class<*>>())

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val suppress = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!prefs.getBoolean(K.ENABLED, false) || !prefs.getBoolean(K.AUTOCOPY, false)) return
                if (prefs.getString(K.AUTOCOPY_FEEDBACK, K.FEEDBACK_TOP) == K.FEEDBACK_SYSTEM) return
                try {
                    val context = (runCatching { XposedHelpers.getObjectField(param.thisObject, "mContext") }
                        .getOrNull() as? Context) ?: AndroidAppHelper.currentApplication() ?: return
                    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
                    if (cm.primaryClipDescription?.label?.toString() == K.AUTOCOPY_CLIP_LABEL) {
                        param.result = null
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("NothingTweaks: overlay suppression failed: ${t.message}")
                }
            }
        }

        fun hookListenerClass(cls: Class<*>) {
            if (!hookedClasses.add(cls)) return
            try {
                val m = cls.getMethod("onPrimaryClipChanged")
                XposedBridge.hookMethod(m, suppress)
                XposedBridge.log("NothingTweaks: clipboard overlay listener hooked: ${cls.name}")
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: cannot hook clipboard listener ${cls.name}: ${t.message}")
            }
        }

        // AOSP class, if present.
        XposedHelpers.findClassIfExists(
            "com.android.systemui.clipboardoverlay.ClipboardListener", lpparam.classLoader
        )?.let { hookListenerClass(it) }

        // Any clipboard listener SystemUI registers (covers Nothing's own classes).
        XposedBridge.hookAllMethods(ClipboardManager::class.java, "addPrimaryClipChangedListener", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val listener = param.args.getOrNull(0) ?: return
                hookListenerClass(listener.javaClass)
            }
        })
    }
}
