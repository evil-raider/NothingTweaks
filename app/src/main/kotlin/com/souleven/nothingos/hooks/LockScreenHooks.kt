package com.souleven.nothingos.hooks

import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class LockScreenHooks : HookModule {

    private val className = "com.nothing.keyguard.KeyguardSecurityContainerControllerEx"

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val clazz = try {
            XposedHelpers.findClass(className, lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [LockScreen] class not found: $className — ${t.message}")
            return
        }

        // disable_power_off_verify -> getShouldPowerOffVerify() = false
        hookBool(clazz, "getShouldPowerOffVerify", "disable_power_off_verify", prefs, forceValue = false)
        
        // Scramble PIN
        val pinViewControllerClass = XposedHelpers.findClassIfExists("com.android.keyguard.KeyguardPinViewController", lpparam.classLoader)
        if (pinViewControllerClass != null) {
            try {
                XposedBridge.hookAllMethods(pinViewControllerClass, "onViewAttached", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean("scramble_pin", false)) return
                        
                        val view = XposedHelpers.getObjectField(param.thisObject, "mView") as? android.view.View ?: return
                        val context = view.context
                        
                        val buttons = mutableListOf<android.view.View>()
                        for (i in 0..9) {
                            val resId = context.resources.getIdentifier("key$i", "id", "com.android.systemui")
                            if (resId != 0) {
                                view.findViewById<android.view.View>(resId)?.let { buttons.add(it) }
                            }
                        }
                        
                        if (buttons.size == 10) {
                            val shuffledDigits = (0..9).shuffled()
                            val sKlondike = XposedHelpers.getStaticObjectField(buttons[0].javaClass, "sKlondike") as? Array<String>
                            
                            for (i in 0..9) {
                                val btn = buttons[i]
                                val newDigit = shuffledDigits[i]
                                
                                XposedHelpers.setIntField(btn, "mDigit", newDigit)
                                
                                val digitText = XposedHelpers.getObjectField(btn, "mDigitText") as? android.widget.TextView
                                digitText?.text = newDigit.toString()
                                
                                val klondikeText = XposedHelpers.getObjectField(btn, "mKlondikeText") as? android.widget.TextView
                                if (sKlondike != null && sKlondike.size > newDigit && newDigit >= 0) {
                                    klondikeText?.text = sKlondike[newDigit]
                                } else {
                                    klondikeText?.text = ""
                                }
                            }
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook scramble pin: ${t.message}")
            }
        }
    }

    private fun hookBool(
        clazz: Class<*>,
        method: String,
        prefKey: String,
        prefs: Prefs,
        forceValue: Boolean
    ) {
        try {
            XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean(prefKey, false)) return
                    val orig = param.result as? Boolean ?: return
                    if (orig != forceValue) {
                        param.result = forceValue
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [LockScreen] FAILED to hook $method: ${t.message}")
            XposedBridge.log(t)
        }
    }
}
