package com.souleven.nothingos.hooks

import android.content.Context
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import com.souleven.nothingos.MainHook.Companion.TAG

class PowerMenuHooks : HookModule {

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "com.android.systemui") return

        try {
            val dialogLiteClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.globalactions.GlobalActionsDialogLite",
                lpparam.classLoader
            )
            val restartActionClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.globalactions.GlobalActionsDialogLite\$RestartAction",
                lpparam.classLoader
            )

            if (dialogLiteClass != null && restartActionClass != null) {
                // Hook createActionItems to inject our custom buttons into the grid
                XposedHelpers.findAndHookMethod(
                    dialogLiteClass,
                    "createActionItems",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!prefs.getBoolean("pref_advanced_power_menu", false)) return

                            val dialogLite = param.thisObject
                            val mItems = XposedHelpers.getObjectField(dialogLite, "mItems") as java.util.ArrayList<Any>

                            // Recovery Button
                            val recoveryAction = XposedHelpers.newInstance(restartActionClass, dialogLite)
                            XposedHelpers.setIntField(recoveryAction, "mMessageResId", 0)
                            XposedHelpers.setObjectField(recoveryAction, "mMessage", "Recovery")
                            mItems.add(recoveryAction)

                            // Bootloader Button
                            val bootloaderAction = XposedHelpers.newInstance(restartActionClass, dialogLite)
                            XposedHelpers.setIntField(bootloaderAction, "mMessageResId", 0)
                            XposedHelpers.setObjectField(bootloaderAction, "mMessage", "Bootloader")
                            mItems.add(bootloaderAction)
                        }
                    }
                )

                // Hook onPress to intercept custom button clicks
                XposedHelpers.findAndHookMethod(
                    restartActionClass,
                    "onPress",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!prefs.getBoolean("pref_advanced_power_menu", false)) return

                            val mMessageStr = (XposedHelpers.getObjectField(param.thisObject, "mMessage") as? CharSequence)?.toString()
                            
                            if (mMessageStr == "Recovery" || mMessageStr == "Bootloader") {
                                // cancel default restart behavior
                                param.result = null

                                val dialogLite = XposedHelpers.getObjectField(param.thisObject, "this\$0")

                                // dismiss the menu
                                try {
                                    XposedHelpers.callMethod(dialogLite, "dismissDialog")
                                } catch (e: Exception) {}

                                // send broadcast to system_server to perform the reboot
                                try {
                                    val intent = Intent()
                                    if (mMessageStr == "Recovery") {
                                        intent.action = "com.souleven.nothingos.REBOOT_RECOVERY"
                                    } else {
                                        intent.action = "com.souleven.nothingos.REBOOT_BOOTLOADER"
                                    }
                                    intent.setPackage("android") // Make it an explicit broadcast to system_server
                                    val context = XposedHelpers.getObjectField(dialogLite, "mContext") as Context
                                    context.sendBroadcast(intent)
                                } catch (e: Exception) {
                                    XposedBridge.log("$TAG Failed to send reboot broadcast: ${e.message}")
                                }
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook Power Menu: ${t.message}")
            XposedBridge.log(t)
        }
    }
}
