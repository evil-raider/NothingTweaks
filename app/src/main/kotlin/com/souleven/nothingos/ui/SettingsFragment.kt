package com.souleven.nothingos.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import com.souleven.nothingos.hooks.NotificationIconLimitOverride
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.souleven.nothingos.MainHook
import com.souleven.nothingos.R

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = MainHook.PREFS_NAME

        @Suppress("DEPRECATION")
        if (com.souleven.nothingos.ModuleStatus.isModuleActive()) {
            try {
                preferenceManager.sharedPreferencesMode = Context.MODE_WORLD_READABLE
            } catch (t: Throwable) {
                // Ignored
            }
        }

        try {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        } catch (t: SecurityException) {
            // Fallback just in case setting preferences still throws
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }

        wireFingerprintDependencies()
        wireTextToolbar()
        wireNotificationIconLimit()

        // Use the reusable reboot function for preferences that require a reboot
        requireRebootOnEnable("allow_180_rotation")
        requireRebootOnEnable("pref_advanced_power_menu")
        requireRebootOnEnable("pref_back_gesture_kill")
    }

    private fun requireRebootOnEnable(preferenceKey: String) {
        findPreference<SwitchPreferenceCompat>(preferenceKey)?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Please reboot your phone to enable this feature.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.isNestedScrollingEnabled = false
        listView.clipToPadding = false
    }

    private fun wireNotificationIconLimit() {
        val pref = findPreference<EditTextPreference>("pref_max_notif_icons") ?: return
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { item ->
            val raw = item.text.orEmpty()
            val limit = NotificationIconLimitOverride.parseLimit(raw)
            val current = when {
                raw.isBlank() -> getString(R.string.pref_max_notif_icons_stock)
                limit == null -> getString(R.string.pref_max_notif_icons_invalid)
                else -> getString(R.string.pref_max_notif_icons_current, limit)
            }
            getString(R.string.pref_max_notif_icons_summary) + "\n" + current
        }
        pref.setOnBindEditTextListener { edit ->
            edit.inputType = InputType.TYPE_CLASS_NUMBER
            edit.setSelectAllOnFocus(true)
        }
        pref.setOnPreferenceChangeListener { _, value ->
            val raw = value as? String ?: return@setOnPreferenceChangeListener false
            val valid = raw.isBlank() || NotificationIconLimitOverride.parseLimit(raw) != null
            if (!valid) Toast.makeText(requireContext(),
                R.string.pref_max_notif_icons_invalid, Toast.LENGTH_LONG).show()
            valid
        }
    }

    private fun wireTextToolbar() {
        findPreference<Preference>("tt_items_editor")?.setOnPreferenceClickListener {
            preferenceManager.sharedPreferences?.let { prefs ->
                TextToolbarDialogs.showItemsEditor(requireContext(), prefs)
            }
            true
        }
        findPreference<Preference>("tt_process_text_root")?.setOnPreferenceClickListener {
            TextToolbarDialogs.showProcessTextManager(requireContext())
            true
        }
    }

    private fun wireFingerprintDependencies() {
        val master = findPreference<SwitchPreferenceCompat>("fp_color_enabled") ?: return
        val random = findPreference<SwitchPreferenceCompat>("fp_random") ?: return
        val color = findPreference<ColorPreference>("fp_color") ?: return

        fun apply(masterOn: Boolean, randomOn: Boolean) {
            random.isEnabled = masterOn
            color.isEnabled = masterOn && !randomOn
        }

        master.setOnPreferenceChangeListener { _: Preference, newValue: Any ->
            val on = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
            if (!on) {
                random.isChecked = true
            }
            apply(on, random.isChecked)
            true
        }

        random.setOnPreferenceChangeListener { _: Preference, newValue: Any ->
            val randomOn = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
            apply(master.isChecked, randomOn)
            true
        }

        apply(master.isChecked, random.isChecked)
    }
}
