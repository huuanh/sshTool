package com.sshtool.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val darkTheme: Boolean = true,
    val fontSize: Float = 14f,
    val keepScreenOn: Boolean = false,
    val biometricLock: Boolean = false,
    val terminalBellEnabled: Boolean = true,
    val defaultRows: Int = 40,
    val defaultCols: Int = 120
)

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store = context.dataStore

    companion object {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
        val TERMINAL_BELL = booleanPreferencesKey("terminal_bell")
        val DEFAULT_ROWS = intPreferencesKey("default_rows")
        val DEFAULT_COLS = intPreferencesKey("default_cols")
    }

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            darkTheme = prefs[DARK_THEME] ?: true,
            fontSize = prefs[FONT_SIZE] ?: 14f,
            keepScreenOn = prefs[KEEP_SCREEN_ON] ?: false,
            biometricLock = prefs[BIOMETRIC_LOCK] ?: false,
            terminalBellEnabled = prefs[TERMINAL_BELL] ?: true,
            defaultRows = prefs[DEFAULT_ROWS] ?: 40,
            defaultCols = prefs[DEFAULT_COLS] ?: 120
        )
    }

    suspend fun setDarkTheme(value: Boolean) {
        store.edit { it[DARK_THEME] = value }
    }

    suspend fun setFontSize(value: Float) {
        store.edit { it[FONT_SIZE] = value }
    }

    suspend fun setKeepScreenOn(value: Boolean) {
        store.edit { it[KEEP_SCREEN_ON] = value }
    }

    suspend fun setBiometricLock(value: Boolean) {
        store.edit { it[BIOMETRIC_LOCK] = value }
    }

    suspend fun setDefaultTerminalSize(rows: Int, cols: Int) {
        store.edit {
            it[DEFAULT_ROWS] = rows
            it[DEFAULT_COLS] = cols
        }
    }
}
