package com.myra.assistant.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.myra.assistant.R
import com.myra.assistant.data.SecurePrefs

/** Launcher Core: switches the home-screen icon by enabling exactly one <activity-alias>. */
object LauncherIcons {
    data class Icon(val id: String, val label: String, val cls: String, val res: Int)

    val all = listOf(
        Icon("default", "Default", "com.myra.assistant.IconDefault", R.mipmap.ic_launcher_default),
        Icon("aurared", "Aura Red", "com.myra.assistant.IconAurared", R.mipmap.ic_launcher_aurared),
        Icon("midnight", "Midnight", "com.myra.assistant.IconMidnight", R.mipmap.ic_launcher_midnight),
        Icon("classic", "Classic", "com.myra.assistant.IconClassic", R.mipmap.ic_launcher_classic),
        Icon("gold", "Gold", "com.myra.assistant.IconGold", R.mipmap.ic_launcher_gold)
    )

    fun apply(ctx: Context, id: String) {
        val pm = ctx.packageManager
        val chosen = all.firstOrNull { it.id == id } ?: all[0]
        // enable the new one first so the app never has zero launcher entries
        pm.setComponentEnabledSetting(
            ComponentName(ctx.packageName, chosen.cls),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )
        for (i in all) {
            if (i.id == chosen.id) continue
            pm.setComponentEnabledSetting(
                ComponentName(ctx.packageName, i.cls),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
        }
        SecurePrefs.launcherIcon = chosen.id
    }
}
