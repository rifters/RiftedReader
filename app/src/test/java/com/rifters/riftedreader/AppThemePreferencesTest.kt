package com.rifters.riftedreader

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.rifters.riftedreader.data.preferences.AppTheme
import com.rifters.riftedreader.data.preferences.AppThemePreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AppThemePreferencesTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { temp[key ?: ""] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { temp[key ?: ""] = null; return this }
            override fun clear(): SharedPreferences.Editor { clearFlag = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearFlag) map.clear()
                for ((k, v) in temp) {
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
    }

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var preferences: AppThemePreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        preferences = AppThemePreferences(fakePrefs)
    }

    @Test
    fun testAppThemeEnumNightModes() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppTheme.DARK.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppTheme.LIGHT.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppTheme.SYSTEM.nightMode)
        assertEquals(AppTheme.DARK, AppTheme.DEFAULT)
    }

    @Test
    fun testAppThemeFromString() {
        assertEquals(AppTheme.DARK, AppTheme.fromString("DARK"))
        assertEquals(AppTheme.DARK, AppTheme.fromString("dark"))
        assertEquals(AppTheme.LIGHT, AppTheme.fromString("LIGHT"))
        assertEquals(AppTheme.LIGHT, AppTheme.fromString("light"))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromString("SYSTEM"))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromString("system"))
        assertEquals(AppTheme.DARK, AppTheme.fromString(null))
        assertEquals(AppTheme.DARK, AppTheme.fromString("invalid"))
    }

    @Test
    fun testDefaultAppThemeIsDark() {
        assertEquals(AppTheme.DARK, preferences.appTheme)
    }

    @Test
    fun testUpdateAppTheme() {
        preferences.appTheme = AppTheme.LIGHT
        assertEquals(AppTheme.LIGHT, preferences.appTheme)
        assertEquals("LIGHT", fakePrefs.getString(AppThemePreferences.KEY_APP_THEME, null))

        preferences.appTheme = AppTheme.SYSTEM
        assertEquals(AppTheme.SYSTEM, preferences.appTheme)
        assertEquals("SYSTEM", fakePrefs.getString(AppThemePreferences.KEY_APP_THEME, null))

        preferences.appTheme = AppTheme.DARK
        assertEquals(AppTheme.DARK, preferences.appTheme)
        assertEquals("DARK", fakePrefs.getString(AppThemePreferences.KEY_APP_THEME, null))
    }

    @Test
    fun testApplyThemeSetsNightMode() {
        preferences.applyTheme(AppTheme.DARK)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())

        preferences.applyTheme(AppTheme.LIGHT)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())

        preferences.applyTheme(AppTheme.SYSTEM)
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.getDefaultNightMode())
    }
}
