package com.souleven.nothingos.hooks

import android.content.Intent
import android.net.Uri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class AIClipboardHooks : HookModule {

    companion object {
        const val KEY_AI_CLIPBOARD = "ai_clipboard_gemini"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val classLoader = lpparam.classLoader

        val hasGeminiCache = mutableMapOf<String, Boolean>()
        
        fun hasGemini(context: android.content.Context): Boolean {
            return hasGeminiCache.getOrPut("gemini") {
                try { context.packageManager.getPackageInfo("com.google.android.apps.bard", 0); true } catch (e: Exception) { false }
            }
        }
        
        fun hasChatGPT(context: android.content.Context): Boolean {
            return try { context.packageManager.getPackageInfo("com.openai.chatgpt", 0); true } catch (e: Exception) { false }
        }

        // 1. Force the ChatGPT (Eye) icon to appear for Clipboard
        val clipboardViewClass = XposedHelpers.findClassIfExists("com.nothing.systemui.clipboardoverlay.NTClipboardOverlayView", classLoader)
        if (clipboardViewClass != null) {
            try {
                XposedBridge.hookAllMethods(clipboardViewClass, "isChatGPTInstalled", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? android.content.Context ?: return
                        param.result = hasGemini(context) || hasChatGPT(context)
                    }
                })
            } catch (t: Throwable) {}
        }
        
        // 2. Force the ChatGPT (Eye) icon to appear for Screenshots
        val screenshotControllerClass = XposedHelpers.findClassIfExists("com.android.systemui.screenshot.LegacyScreenshotController", classLoader)
        if (screenshotControllerClass != null) {
            try {
                XposedBridge.hookAllMethods(screenshotControllerClass, "isChatGPTInstalled", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? android.content.Context ?: return
                        param.result = hasGemini(context) || hasChatGPT(context)
                    }
                })
            } catch (t: Throwable) {}
        }

        // 3. Hijack Clipboard Intent Creation (AOSP DefaultIntentCreator & ActionIntentCreator)
        val hookClipboardIntent = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                val clipData = param.args[0] as? android.content.ClipData ?: return
                val context = param.args[1] as? android.content.Context ?: return
                
                if (hasGemini(context)) {
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    
                    if (clipData.itemCount > 0) {
                        val item = clipData.getItemAt(0)
                        if (item.uri != null) {
                            intent.type = "image/*"
                            intent.putExtra(Intent.EXTRA_STREAM, item.uri)
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } else {
                            intent.type = "text/plain"
                            intent.putExtra(Intent.EXTRA_TEXT, item.text ?: "")
                        }
                    }
                    intent.setPackage("com.google.android.apps.bard")
                    param.result = intent
                }
            }
        }
        
        arrayOf(
            "com.android.systemui.clipboardoverlay.DefaultIntentCreator",
            "com.android.systemui.clipboardoverlay.ActionIntentCreator"
        ).forEach { className ->
            val creatorClass = XposedHelpers.findClassIfExists(className, classLoader)
            if (creatorClass != null) {
                try {
                    XposedBridge.hookAllMethods(creatorClass, "getGPTIntent", hookClipboardIntent)
                } catch (t: Throwable) {}
            }
        }
        
        // 4. Hijack Screenshot Intent Creation and Bypass Shared Transition
        val screenshotIntentCreatorClass = XposedHelpers.findClassIfExists("com.android.systemui.screenshot.ActionIntentCreator", classLoader)
        if (screenshotIntentCreatorClass != null) {
            try {
                XposedBridge.hookAllMethods(screenshotIntentCreatorClass, "createShareAI", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val originalIntent = param.result as? Intent ?: return
                        
                        val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                        val context = XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? android.content.Context
                        if (context != null && hasGemini(context)) {
                            originalIntent.component = null
                            originalIntent.setPackage("com.google.android.apps.bard")
                            param.result = originalIntent
                        }
                    }
                })
            } catch (t: Throwable) {}
        }
        
        // 5. Prevent freezing by bypassing startSharedTransition for Gemini
        val actionExecutorClass = XposedHelpers.findClassIfExists("com.android.systemui.screenshot.ActionExecutor", classLoader)
        if (actionExecutorClass != null) {
            try {
                XposedBridge.hookAllMethods(actionExecutorClass, "startSharedTransition", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val intent = param.args[0] as? Intent ?: return
                        if (intent.`package` == "com.google.android.apps.bard") {
                            // Bypass shared transition which freezes if the target app doesn't support it
                            val context = XposedHelpers.callMethod(param.thisObject, "getContext") as? android.content.Context
                            if (context != null) {
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {}
                            }
                            
                            // Dismiss the screenshot overlay
                            try {
                                XposedHelpers.callMethod(param.thisObject, "requestDismissal")
                            } catch (t: Throwable) {}
                            
                            param.result = null // skip the original startSharedTransition
                        }
                    }
                })
            } catch (t: Throwable) {}
        }
    }
}
