package com.r1.launcher

import android.content.pm.ResolveInfo

sealed class AppEntry {
    data class Real(val info: ResolveInfo) : AppEntry()
    object Settings : AppEntry()
    object OpenClaw : AppEntry()
    object Messages : AppEntry()
    object Terminal : AppEntry()
    object Hermes : AppEntry()
    object Meetings : AppEntry()
    object Translator : AppEntry()
    object Camera : AppEntry()
    object Chat : AppEntry()
    /** Scratch panel for exercising the build/install loop. Safe to delete. */
    object Testing : AppEntry()
}
