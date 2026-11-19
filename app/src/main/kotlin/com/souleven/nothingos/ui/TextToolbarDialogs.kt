package com.souleven.nothingos.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.souleven.nothingos.R
import com.souleven.nothingos.hooks.TextToolbarKeys as K
import org.json.JSONObject

/** Settings dialogs for the text selection toolbar tweaks. */
object TextToolbarDialogs {

    private data class Entry(val key: String, val title: String, val subtitle: String? = null)

    private data class ProcessTextActivity(
        val pkg: String, val cls: String, val label: String, val appLabel: String, val enabled: Boolean
    )

    private fun builtinTitle(ctx: Context, key: String): String = ctx.getString(
        when (key) {
            K.ITEM_ASSIST -> R.string.tt_item_assist
            K.ITEM_CUT -> R.string.tt_item_cut
            K.ITEM_COPY -> R.string.tt_item_copy
            K.ITEM_PASTE -> R.string.tt_item_paste
            K.ITEM_SHARE -> R.string.tt_item_share
            K.ITEM_SELECT_ALL -> R.string.tt_item_select_all
            K.ITEM_REPLACE -> R.string.tt_item_replace
            K.ITEM_AUTOFILL -> R.string.tt_item_autofill
            K.ITEM_PASTE_PLAIN -> R.string.tt_item_paste_plain
            else -> R.string.tt_item_assist_secondary
        }
    )

    private fun queryProcessText(ctx: Context): List<ProcessTextActivity> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
        return list.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            if (ai.packageName == ctx.packageName) return@mapNotNull null
            val state = runCatching {
                pm.getComponentEnabledSetting(ComponentName(ai.packageName, ai.name))
            }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            val enabled = when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> ai.enabled
                else -> false
            }
            ProcessTextActivity(
                pkg = ai.packageName,
                cls = ai.name,
                label = ri.loadLabel(pm).toString(),
                appLabel = ai.applicationInfo.loadLabel(pm).toString(),
                enabled = enabled
            )
        }.distinctBy { it.pkg + "/" + it.cls }.sortedBy { it.appLabel.lowercase() }
    }

    // ---------------------------------------------------------------------------------------
    // Items: order, visibility and custom short labels
    // ---------------------------------------------------------------------------------------

    fun showItemsEditor(ctx: Context, prefs: SharedPreferences) {
        val known = LinkedHashMap<String, Entry>()
        K.BUILTIN_ITEMS.forEach { known[it] = Entry(it, builtinTitle(ctx, it)) }
        queryProcessText(ctx).filter { it.enabled }.forEach {
            val key = K.processTextKey(it.pkg, it.cls)
            known[key] = Entry(key, it.label, it.appLabel.takeIf { a -> a != it.label })
        }

        val saved = K.parseList(prefs.getString(K.ORDER, ""))
        // Keep keys of currently uninstalled apps so their position is not lost.
        val order = ArrayList<String>(saved + known.keys.filter { it !in saved })
        val hidden = K.parseList(prefs.getString(K.HIDDEN, "")).toHashSet()
        val labels = HashMap<String, String>()
        runCatching {
            val json = JSONObject(prefs.getString(K.LABELS, "").orEmpty().ifEmpty { "{}" })
            json.keys().forEach { labels[it] = json.optString(it) }
        }

        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        lateinit var rebuild: () -> Unit
        rebuild = {
            list.removeAllViews()
            order.filter { it in known }.forEach { key ->
                val entry = known.getValue(key)
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val check = CheckBox(ctx).apply {
                    isChecked = key !in hidden
                    setOnCheckedChangeListener { _, on -> if (on) hidden.remove(key) else hidden.add(key) }
                }
                val custom = labels[key]?.takeIf { it.isNotBlank() }
                val text = TextView(ctx).apply {
                    this.text = buildString {
                        append(entry.title)
                        entry.subtitle?.let { append(" · ").append(it) }
                        custom?.let { append("  [").append(it).append("]") }
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                    setOnClickListener {
                        editLabel(ctx, entry.title, labels[key].orEmpty()) { value ->
                            if (value.isBlank()) labels.remove(key) else labels[key] = value.trim()
                            rebuild()
                        }
                    }
                }
                fun move(delta: Int) {
                    val visible = order.filter { it in known }
                    val i = visible.indexOf(key)
                    val j = i + delta
                    if (j !in visible.indices) return
                    val a = order.indexOf(visible[i])
                    val b = order.indexOf(visible[j])
                    order[a] = visible[j]
                    order[b] = visible[i]
                    rebuild()
                }
                val up = ImageButton(ctx).apply {
                    setImageResource(android.R.drawable.arrow_up_float)
                    background = null
                    contentDescription = ctx.getString(R.string.tt_move_up)
                    setOnClickListener { move(-1) }
                }
                val down = ImageButton(ctx).apply {
                    setImageResource(android.R.drawable.arrow_down_float)
                    background = null
                    contentDescription = ctx.getString(R.string.tt_move_down)
                    setOnClickListener { move(1) }
                }
                row.addView(check)
                row.addView(text)
                row.addView(up, LinearLayout.LayoutParams(dp(40), dp(40)))
                row.addView(down, LinearLayout.LayoutParams(dp(40), dp(40)))
                list.addView(row)
            }
        }
        rebuild()

        val hint = TextView(ctx).apply {
            setText(R.string.tt_items_hint)
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(ScrollView(ctx).apply { addView(list) })
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.tt_items_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val json = JSONObject()
                labels.filterValues { it.isNotBlank() }.forEach { (k, v) -> json.put(k, v) }
                prefs.edit()
                    .putString(K.ORDER, K.joinList(order))
                    .putString(K.HIDDEN, K.joinList(hidden))
                    .putString(K.LABELS, json.toString())
                    .apply()
            }
            .setNeutralButton(R.string.tt_reset) { _, _ ->
                prefs.edit().remove(K.ORDER).remove(K.HIDDEN).remove(K.LABELS).apply()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editLabel(ctx: Context, title: String, current: String, onDone: (String) -> Unit) {
        val input = EditText(ctx).apply {
            setText(current)
            setSingleLine(true)
            hint = ctx.getString(R.string.tt_label_hint)
        }
        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.tt_label_title, title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------------------------
    // Root: enable / disable ACTION_PROCESS_TEXT activities system-wide
    // ---------------------------------------------------------------------------------------

    fun showProcessTextManager(ctx: Context) {
        val items = queryProcessText(ctx)
        if (items.isEmpty()) {
            Toast.makeText(ctx, R.string.tt_pt_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = items.map { if (it.label == it.appLabel) it.label else "${it.label} · ${it.appLabel}" }
            .toTypedArray()
        val checked = items.map { it.enabled }.toBooleanArray()

        AlertDialog.Builder(ctx)
            .setTitle(R.string.tt_pt_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val commands = items.indices
                    .filter { checked[it] != items[it].enabled }
                    .map { i ->
                        val verb = if (checked[i]) "enable" else "disable"
                        "pm $verb --user 0 ${items[i].pkg}/${items[i].cls}"
                    }
                if (commands.isNotEmpty()) runRoot(ctx, commands)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runRoot(ctx: Context, commands: List<String>) {
        val appCtx = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val ok = try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", commands.joinToString(" && ")))
                process.waitFor() == 0
            } catch (t: Throwable) {
                false
            }
            main.post {
                Toast.makeText(
                    appCtx, if (ok) R.string.tt_pt_done else R.string.tt_pt_failed, Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }
}
