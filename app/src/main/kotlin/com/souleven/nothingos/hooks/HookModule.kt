package com.souleven.nothingos.hooks

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

interface HookModule {
    fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs)
}
