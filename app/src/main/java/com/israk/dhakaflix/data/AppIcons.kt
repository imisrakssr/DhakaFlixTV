package com.israk.dhakaflix.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Launcher icon variants (activity-aliases in the manifest). */
object LauncherIcons {
    val variants = listOf("classic", "sunset", "emerald", "royal", "crimson", "gold", "mono")

    fun label(v: String) = v.replaceFirstChar { it.uppercase() }

    fun apply(ctx: Context, chosen: String) {
        val pm = ctx.packageManager
        // enable the new one first so there is never a moment without a launcher entry
        val order = listOf(chosen) + variants.filter { it != chosen }
        order.forEach { v ->
            runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName(ctx.packageName, "com.israk.dhakaflix.Launcher_$v"),
                    if (v == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
