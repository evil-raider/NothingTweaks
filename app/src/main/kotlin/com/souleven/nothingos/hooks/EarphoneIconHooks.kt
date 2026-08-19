package com.souleven.nothingos.hooks

import android.bluetooth.BluetoothDevice
import android.content.res.Resources
import android.os.Handler
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class EarphoneIconHooks : HookModule {

    companion object {
        const val KEY_FORCE_EARPHONE_ICON = "force_earphone_icon"
        private const val CLASS_BT_UTILS = "com.nothing.systemui.statusbar.policy.NTBluetoothUtils"
        private const val CLASS_POLICY_EX = "com.nothing.systemui.statusbar.phone.PhoneStatusBarPolicyEx"
        private const val DRAWABLE_EARPHONE = "nt_ic_universal_earphone"
        private const val SYSTEMUI_PKG = "com.android.systemui"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        hookDeviceClassification(lpparam, prefs)
        hookIconFallback(lpparam, prefs)
    }

    private fun hookDeviceClassification(lpparam: LoadPackageParam, prefs: Prefs) {
        val utils = try {
            XposedHelpers.findClass(CLASS_BT_UTILS, lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [EarphoneIcon] class not found: $CLASS_BT_UTILS — ${t.message}")
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                utils,
                "isNothingDeviceFromCache",
                BluetoothDevice::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_FORCE_EARPHONE_ICON, false)) return
                        if (param.result == true) return

                        param.result = true
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [EarphoneIcon] FAILED to hook isNothingDeviceFromCache: ${t.message}")
            XposedBridge.log(t)
        }

    }

    private fun hookIconFallback(lpparam: LoadPackageParam, prefs: Prefs) {
        val policyEx = try {
            XposedHelpers.findClass(CLASS_POLICY_EX, lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [EarphoneIcon] class not found: $CLASS_POLICY_EX — ${t.message}")
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                policyEx,
                "updateNtEarPhoneIcon",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_FORCE_EARPHONE_ICON, false)) return

                        val self = param.thisObject

                        // True means a genuine Nothing bitmap was found and posted. Leave it.
                        val hasReal = try {
                            XposedHelpers.getBooleanField(self, "mHasNtEarPhoneIcon")
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG   [EarphoneIcon] cannot read mHasNtEarPhoneIcon: ${t.message}")
                            return
                        }
                        if (hasReal) return

                        val handler = XposedHelpers.getObjectField(self, "mMainHandler") as? Handler
                        val iconController = XposedHelpers.getObjectField(self, "mIconController")
                        val slot = XposedHelpers.getObjectField(self, "mSlotNTEarPhone") as? String
                        val res = XposedHelpers.getObjectField(self, "mResources") as? Resources

                        if (handler == null || iconController == null || slot == null || res == null) {
                            XposedBridge.log(
                                "$TAG   [EarphoneIcon] missing field — handler=${handler != null} " +
                                    "controller=${iconController != null} slot=${slot != null} res=${res != null}"
                            )
                            return
                        }

                        val resId = res.getIdentifier(DRAWABLE_EARPHONE, "drawable", SYSTEMUI_PKG)
                        if (resId == 0) {
                            XposedBridge.log(
                                "$TAG   [EarphoneIcon] drawable $DRAWABLE_EARPHONE not found in $SYSTEMUI_PKG — " +
                                    "leaving the stock fallback glyph in place"
                            )
                            return
                        }

                        // Posted after the original's runnable, so this setIcon lands last.
                        handler.post {
                            try {
                                XposedHelpers.callMethod(
                                    iconController,
                                    "setIcon",
                                    slot,
                                    resId,
                                    "Bluetooth connected" as CharSequence
                                )
                                XposedHelpers.setBooleanField(self, "mHasNtEarPhoneIcon", true)
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG   [EarphoneIcon] setIcon failed: ${t.message}")
                                XposedBridge.log(t)
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [EarphoneIcon] FAILED to hook updateNtEarPhoneIcon: ${t.message}")
            XposedBridge.log(t)
        }
    }
}
