package com.souleven.nothingos.hooks

import android.app.AndroidAppHelper
import android.content.ClipboardManager
import android.content.Context
import com.souleven.nothingos.hooks.TextToolbarKeys as K
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * SystemUI side of auto-copy: hides the clipboard overlay (Android 13+) for text that was
 * copied automatically by [TextToolbarHooks], unless the user chose the system feedback.
 */
class AutoCopyOverlayHooks : HookModule {

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val listener = XposedHelpers.findClassIfExists(
            "com.android.systemui.clipboardoverlay.ClipboardListener", lpparam.classLoader
        ) ?: run {
            XposedBridge.log("NothingTweaks: ClipboardListener not found, overlay suppression disabled")
            return
        }

        XposedBridge.hookAllMethods(listener, "onPrimaryClipChanged", object : XC_MethodHook() {
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
        })
    }
}
