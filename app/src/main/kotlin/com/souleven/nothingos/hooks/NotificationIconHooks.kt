// Hook 5 — ЗАМЕР иерархии: кто режет/перекрывает. Фильтры: NTX_PAR, NTX_SIB
try {
    XposedBridge.hookAllMethods(containerClass, "calculateIconXTranslations", object : XC_MethodHook() {
        private var last = 0L

        private fun idName(v: Any): String = try {
            val id = XposedHelpers.callMethod(v, "getId") as Int
            if (id <= 0) "no-id"
            else {
                val ctx = XposedHelpers.callMethod(v, "getContext") as android.content.Context
                ctx.resources.getResourceEntryName(id)
            }
        } catch (e: Throwable) { "?" }

        private fun dump(tag: String, v: Any): String {
            val cn = v.javaClass.simpleName
            val w = XposedHelpers.callMethod(v, "getWidth")
            val l = XposedHelpers.callMethod(v, "getLeft")
            val r = XposedHelpers.callMethod(v, "getRight")
            val tx = XposedHelpers.callMethod(v, "getTranslationX")
            val vis = XposedHelpers.callMethod(v, "getVisibility")
            val clip = try { XposedHelpers.callMethod(v, "getClipBounds") } catch (e: Throwable) { null }
            val cc = try { (XposedHelpers.callMethod(v, "getClipChildren") as Boolean).toString() } catch (e: Throwable) { "-" }
            return "$tag[${idName(v)}/$cn w=$w l=$l r=$r tx=$tx vis=$vis clipCh=$cc clip=$clip]"
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val v = param.thisObject
            val id = XposedHelpers.callMethod(v, "getId") as Int
            if (id != statusBarId(v)) return
            val now = System.currentTimeMillis()
            if (now - last < 1000) return
            last = now
            try {
                // 1) цепочка родителей вверх
                val sb = StringBuilder()
                var cur: Any? = v
                var lvl = 0
                while (cur != null && lvl < 7) {
                    sb.append(dump("L$lvl", cur)).append("  ")
                    val p = try { XposedHelpers.callMethod(cur, "getParent") } catch (e: Throwable) { null }
                    if (p == null || p !is android.view.View) break
                    cur = p
                    lvl++
                }
                XposedBridge.log("NTX_PAR $sb")

                // 2) соседи внутри непосредственного родителя (ищем перекрытие)
                val parent = XposedHelpers.callMethod(v, "getParent")
                if (parent is android.view.ViewGroup) {
                    val n = XposedHelpers.callMethod(parent, "getChildCount") as Int
                    val sb2 = StringBuilder()
                    for (i in 0 until n) {
                        val ch = XposedHelpers.callMethod(parent, "getChildAt", i)
                        sb2.append("${idName(ch)}/${ch.javaClass.simpleName}(l=${XposedHelpers.callMethod(ch, "getLeft")},r=${XposedHelpers.callMethod(ch, "getRight")},vis=${XposedHelpers.callMethod(ch, "getVisibility")}) ")
                    }
                    XposedBridge.log("NTX_SIB $sb2")
                }
            } catch (e: Throwable) {
                XposedBridge.log("NTX_PAR diag error: ${e.message}")
            }
        }
    })
} catch (t: Throwable) {
    XposedBridge.log("NothingTweaks: parent-diag hook failed: ${t.message}")
}
