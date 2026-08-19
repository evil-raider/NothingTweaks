package com.souleven.nothingos.hooks

import android.text.SpannableStringBuilder
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ClockHooks : HookModule {

    companion object {
        const val KEY_SHOW_SECONDS = "clock_show_seconds"
        const val KEY_SHOW_DOW = "clock_show_dow"
        const val KEY_CUSTOM_TEXT = "clock_custom_text"
        private const val CLASS_CLOCK = "com.android.systemui.statusbar.policy.Clock"
        private const val FIELD_SHOW_SECONDS = "mShowSeconds"
        private const val FIELD_CALENDAR = "mCalendar"
        private const val METHOD_UPDATE_SHOW_SECONDS = "updateShowSeconds"
        private const val TUNER_KEY_SECONDS = "clock_seconds"
        private const val MAX_CUSTOM_TEXT = 24
    }

    @Volatile
    private var lastAppendSignature: String? = null

    @Volatile
    private var lastSecondsSignature: Boolean? = null

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val clock = try {
            XposedHelpers.findClass(CLASS_CLOCK, lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Clock] class not found: $CLASS_CLOCK — ${t.message}")
            return
        }

        hookSeconds(clock, prefs)
        hookTextAppend(clock, prefs)
    }

    private fun hookSeconds(clock: Class<*>, prefs: Prefs) {
        val applySeconds = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val want = prefs.getBoolean(KEY_SHOW_SECONDS, false)
                val view = param.thisObject

                val current = try {
                    XposedHelpers.getBooleanField(view, FIELD_SHOW_SECONDS)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG   [Clock] cannot read $FIELD_SHOW_SECONDS: ${t.message}")
                    return
                }

                // Only act when our preference disagrees with the field. Without this guard the
                // re-assert hook on onTuningChanged would restart the ticker on every tuner event.
                if (current == want) return

                try {
                    XposedHelpers.setBooleanField(view, FIELD_SHOW_SECONDS, want)
                    XposedHelpers.callMethod(view, METHOD_UPDATE_SHOW_SECONDS)

                    if (lastSecondsSignature != want) {
                        lastSecondsSignature = want
                        XposedBridge.log(
                            "$TAG   [Clock] seconds -> $want (ticker " +
                                (if (want) "started" else "stopped") + ")"
                        )
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG   [Clock] failed applying seconds=$want: ${t.message}")
                    XposedBridge.log(t)
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(clock, "onAttachedToWindow", applySeconds)
            XposedBridge.log("$TAG   [Clock] hooked onAttachedToWindow (seconds)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Clock] FAILED to hook onAttachedToWindow: ${t.message}")
            XposedBridge.log(t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                clock,
                "onTuningChanged",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args.getOrNull(0) as? String ?: return
                        if (key != TUNER_KEY_SECONDS) return

                        val want = prefs.getBoolean(KEY_SHOW_SECONDS, false)
                        val view = param.thisObject
                        val current = try {
                            XposedHelpers.getBooleanField(view, FIELD_SHOW_SECONDS)
                        } catch (t: Throwable) {
                            return
                        }
                        if (current == want) return

                        try {
                            XposedHelpers.setBooleanField(view, FIELD_SHOW_SECONDS, want)
                            XposedHelpers.callMethod(view, METHOD_UPDATE_SHOW_SECONDS)
                            XposedBridge.log(
                                "$TAG   [Clock] re-asserted seconds=$want after tuner event"
                            )
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG   [Clock] re-assert failed: ${t.message}")
                        }
                    }
                }
            )
            XposedBridge.log("$TAG   [Clock] hooked onTuningChanged (seconds re-assert)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Clock] FAILED to hook onTuningChanged: ${t.message}")
            XposedBridge.log(t)
        }
    }

    private fun hookTextAppend(clock: Class<*>, prefs: Prefs) {
        try {
            XposedHelpers.findAndHookMethod(
                clock,
                "getSmallTime",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val showDow = prefs.getBoolean(KEY_SHOW_DOW, false)
                        val customRaw = prefs.getString(KEY_CUSTOM_TEXT, "").trim()
                        val custom = if (customRaw.length > MAX_CUSTOM_TEXT) {
                            customRaw.substring(0, MAX_CUSTOM_TEXT)
                        } else {
                            customRaw
                        }

                        if (!showDow && custom.isEmpty()) {
                            if (lastAppendSignature != "stock") {
                                lastAppendSignature = "stock"
                            }
                            return
                        }

                        val original = param.result as? CharSequence ?: return
                        val out = SpannableStringBuilder(original)

                        if (showDow) {
                            val dow = dayOfWeek(param.thisObject)
                            if (dow != null) out.insert(0, "$dow ")
                        }

                        if (custom.isNotEmpty()) out.append(" ").append(custom)

                        param.result = out

                        val signature = "dow=$showDow text='$custom'"
                        if (lastAppendSignature != signature) {
                            lastAppendSignature = signature
                            XposedBridge.log("$TAG   [Clock] applied $signature -> \"$out\"")
                        }
                    }
                }
            )
            XposedBridge.log("$TAG   [Clock] hooked getSmallTime (day of week + custom text)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Clock] FAILED to hook getSmallTime: ${t.message}")
            XposedBridge.log(t)
        }
    }

    private fun dayOfWeek(view: Any): String? {
        return try {
            val cal = XposedHelpers.getObjectField(view, FIELD_CALENDAR) as? Calendar
            val time = cal?.time ?: Calendar.getInstance().time
            SimpleDateFormat("EEE", Locale.getDefault()).format(time)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Clock] day-of-week failed: ${t.message}")
            null
        }
    }
}
