package com.souleven.nothingos.hooks

import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlin.random.Random

class FingerprintHooks : HookModule {

    companion object {
        const val KEY_ENABLED = "fp_color_enabled"
        const val KEY_RANDOM = "fp_random"
        const val KEY_COLOR = "fp_color"
        private const val CLASS_UDFPS_VIEW = "com.nothing.systemui.biometrics.NTUdfpsView"
        private const val DEFAULT_COLOR_HEX = "#FFD71921"
        private const val DEFAULT_COLOR = 0xFFD71921.toInt()
    }

    @Volatile
    private var randomColor: Int = DEFAULT_COLOR

    @Volatile private var loggedDisabled = false
    @Volatile private var loggedBadColor: String? = null

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        hookDots(lpparam, prefs)
        hookFingerDown(lpparam, prefs)
    }

    private val hookedCallbackClasses = mutableSetOf<String>()

    private fun hookDots(lpparam: LoadPackageParam, prefs: Prefs) {
        val lottieViewClass = try {
            XposedHelpers.findClass("com.airbnb.lottie.LottieAnimationView", lpparam.classLoader)
        } catch (t: Throwable) {
            return
        }

        XposedBridge.hookAllMethods(lottieViewClass, "addValueCallback", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val callback = param.args.lastOrNull() ?: return
                val clazz = callback.javaClass
                if (clazz.name.startsWith("com.nothing.systemui.biometrics.NTUdfpsView")) {
                    hookGetValue(clazz, prefs)
                }
            }
        })
    }

    private fun hookGetValue(callbackClass: Class<*>, prefs: Prefs) {
        if (!hookedCallbackClasses.add(callbackClass.name)) return
        try {
            XposedBridge.hookAllMethods(
                callbackClass,
                "getValue",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val color = resolveColor(prefs) ?: return
                        if (param.result != null && param.result !is ColorFilter) return
                        param.result = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Fingerprint] FAILED to hook getValue in ${callbackClass.name}: ${t.message}")
        }
    }

    private fun hookFingerDown(lpparam: LoadPackageParam, prefs: Prefs) {
        val udfpsView = try {
            XposedHelpers.findClass(CLASS_UDFPS_VIEW, lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log(
                "$TAG   [Fingerprint] NTUdfpsView not found — random mode unavailable: ${t.message}"
            )
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                udfpsView,
                "onFingerDown",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_ENABLED, false)) return
                        if (!prefs.getBoolean(KEY_RANDOM, true)) return

                        randomColor = rollVividColor()
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Fingerprint] FAILED to hook onFingerDown: ${t.message}")
            XposedBridge.log(t)
        }
    }

    private fun resolveColor(prefs: Prefs): Int? {
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            if (!loggedDisabled) {
                loggedDisabled = true
                XposedBridge.log("$TAG   [Fingerprint] disabled — stock adaptive colour kept")
            }
            return null
        }

        loggedDisabled = false
        return if (prefs.getBoolean(KEY_RANDOM, true)) {
            randomColor
        } else {
            parseColor(prefs.getString(KEY_COLOR, DEFAULT_COLOR_HEX)) ?: DEFAULT_COLOR
        }
    }

    private fun rollVividColor(): Int {
        val hue = Random.nextFloat() * 360f
        val sat = 0.75f + Random.nextFloat() * 0.25f
        val value = 0.85f + Random.nextFloat() * 0.15f
        return Color.HSVToColor(floatArrayOf(hue, sat, value))
    }

    private fun parseColor(raw: String): Int? {
        val hex = raw.trim().removePrefix("#")
        if (hex.isEmpty() || hex.equals("off", ignoreCase = true)) return null
        return try {
            when (hex.length) {
                8 -> hex.toLong(16).toInt()
                6 -> (0xFF000000L or hex.toLong(16)).toInt()
                else -> {
                    logBadColor(raw)
                    null
                }
            }
        } catch (t: Throwable) {
            logBadColor(raw)
            null
        }
    }

    private fun logBadColor(raw: String) {
        if (loggedBadColor == raw) return
        loggedBadColor = raw
        XposedBridge.log(
            "$TAG   [Fingerprint] unparseable colour '$raw' — expected #AARRGGBB or #RRGGBB"
        )
    }

    private fun hex(color: Int): String = String.format("#%08X", color)
}
