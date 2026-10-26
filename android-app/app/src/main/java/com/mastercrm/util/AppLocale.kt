package com.mastercrm.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AppLocale {
    private const val PREFS_NAME = "app_locale_prefs"
    private const val KEY_LANG = "key_language"
    private const val KEY_CURR = "key_currency"

    var isEnglish by mutableStateOf(false)
        private set

    var currencySymbol by mutableStateOf("$")
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isEnglish = prefs.getString(KEY_LANG, "uk") == "en"
        currencySymbol = prefs.getString(KEY_CURR, "$") ?: "$"
    }

    fun setLanguage(context: Context, lang: String) {
        isEnglish = (lang == "en")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).apply()
    }

    fun setCurrency(context: Context, curr: String) {
        currencySymbol = curr
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CURR, curr).apply()
    }

    fun formatMoney(amount: Double): String {
        return when (currencySymbol) {
            "$" -> "$${amount.toInt()}"
            "€" -> "€${amount.toInt()}"
            else -> "${amount.toInt()} грн"
        }
    }

    fun t(uk: String, en: String): String = if (isEnglish) en else uk
}
