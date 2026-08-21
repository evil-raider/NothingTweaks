package com.souleven.nothingos.hooks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import com.souleven.nothingos.MainHook.Companion.TAG

class SystemFrameworkHooks : HookModule {

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "android") return

        try {
            val activityManagerServiceClass = XposedHelpers.findClassIfExists(
                "com.android.server.am.ActivityManagerService",
                lpparam.classLoader
            )
            
            if (activityManagerServiceClass != null) {
                XposedBridge.hookAllMethods(
                    activityManagerServiceClass,
                    "systemReady",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val ams = param.thisObject
                            val context = XposedHelpers.getObjectField(ams, "mContext") as? Context
                            
                            if (context != null) {
                                registerSystemReceiver(context)
                            } else {
                                XposedBridge.log("$TAG SystemFrameworkHooks: mContext is null in AMS")
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook System Framework: ${t.message}")
            XposedBridge.log(t)
        }
    }

    private fun registerSystemReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val action = intent.action
                if (action == "com.souleven.nothingos.REBOOT_RECOVERY" || action == "com.souleven.nothingos.REBOOT_BOOTLOADER") {
                    try {
                        val sysPropClass = Class.forName("android.os.SystemProperties")
                        val setMethod = sysPropClass.getMethod("set", String::class.java, String::class.java)
                        
                        if (action == "com.souleven.nothingos.REBOOT_RECOVERY") {
                            setMethod.invoke(null, "sys.powerctl", "reboot,recovery")
                        } else {
                            setMethod.invoke(null, "sys.powerctl", "reboot,bootloader")
                        }
                    } catch (e: Exception) {
                        XposedBridge.log("$TAG Failed to set sys.powerctl from framework: ${e.message}")
                    }
                } else if (action == "com.souleven.nothingos.FORCE_STOP_APP") {
                    val packageName = intent.getStringExtra("package_name")
                    if (packageName != null) {
                        try {
                            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                            val method = am.javaClass.getMethod("forceStopPackage", String::class.java)
                            method.invoke(am, packageName)
                        } catch (e: Exception) {
                            // Silently fail if something goes wrong
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.souleven.nothingos.REBOOT_RECOVERY")
            addAction("com.souleven.nothingos.REBOOT_BOOTLOADER")
            addAction("com.souleven.nothingos.FORCE_STOP_APP")
        }

        try {
            // Android 13+ requires specifying RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
            val Context_RECEIVER_EXPORTED = 0x2
            val registerReceiverMethod = context.javaClass.getMethod("registerReceiver", BroadcastReceiver::class.java, IntentFilter::class.java, Int::class.javaPrimitiveType)
            registerReceiverMethod.invoke(context, receiver, filter, Context_RECEIVER_EXPORTED)
        } catch (e: Exception) {
            // Fallback for older Android versions
            context.registerReceiver(receiver, filter)
        }
        XposedBridge.log("$TAG Registered SystemReceiver in system_server")
    }
}
