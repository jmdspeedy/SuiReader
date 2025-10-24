package com.jmdspeedy.suireader

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val themeSwitch = findViewById<SwitchMaterial>(R.id.theme_switch)
        val sharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        themeSwitch.isChecked = currentNightMode == Configuration.UI_MODE_NIGHT_YES

        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            val mode = if (isChecked) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
            AppCompatDelegate.setDefaultNightMode(mode)
            sharedPreferences.edit().putBoolean("night_mode", isChecked).apply()
        }

        val languageSetting = findViewById<RelativeLayout>(R.id.language_setting)
        languageSetting.setOnClickListener {
            showLanguageDialog()
        }
    }

    private fun showLanguageDialog() {
        val languages = arrayOf("日本語", "English", "简体中文")
        val languageCodes = arrayOf("ja", "en", "zh")

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.language))
            .setItems(languages) { _, which ->
                val selectedLanguageCode = languageCodes[which]
                val appLocale = LocaleListCompat.forLanguageTags(selectedLanguageCode)
                AppCompatDelegate.setApplicationLocales(appLocale)
            }
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
