package com.example.termiusclone.ui.terminal

import android.content.Context
import android.graphics.Color
import androidx.preference.PreferenceManager

/** Color scheme for the mini terminal. */
data class TerminalTheme(val key: String, val label: String, val bg: Int, val fg: Int)

object TerminalThemes {
    val all = listOf(
        TerminalTheme("default",   "Termius Dark",  Color.parseColor("#0E1020"), Color.parseColor("#E6E6E6")),
        TerminalTheme("solarized", "Solarized Dark",Color.parseColor("#002B36"), Color.parseColor("#93A1A1")),
        TerminalTheme("dracula",   "Dracula",       Color.parseColor("#282A36"), Color.parseColor("#F8F8F2")),
        TerminalTheme("monokai",   "Monokai",       Color.parseColor("#272822"), Color.parseColor("#F8F8F2")),
        TerminalTheme("gruvbox",   "Gruvbox Dark",  Color.parseColor("#282828"), Color.parseColor("#EBDBB2")),
        TerminalTheme("light",     "Light",         Color.parseColor("#FAFAFA"), Color.parseColor("#1F2329"))
    )

    fun current(ctx: Context): TerminalTheme {
        val key = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString("terminal_theme", "default") ?: "default"
        return all.firstOrNull { it.key == key } ?: all[0]
    }
}
