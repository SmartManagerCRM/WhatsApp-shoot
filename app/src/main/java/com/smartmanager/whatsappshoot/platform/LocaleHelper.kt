package com.smartmanager.whatsappshoot.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

/**
 * In-app language: System default, English, Arabic or French. The choice is stored in its own small preferences file
 * (read before anything else is built) and applied by wrapping the Context the activities, notifications and the floating button use.
 * Arabic also flips the layout to right-to-left.
 */
object LocaleHelper {
    const val SYSTEM = "system"
    val SUPPORTED = listOf("en", "ar", "fr")

    private const val PREFS = "language"
    private const val KEY = "code"

    fun saved(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM)?.takeIf { it == SYSTEM || it in SUPPORTED } ?: SYSTEM

    fun save(context: Context, code: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, if (code in SUPPORTED) code else SYSTEM).commit()
    }

    /** The language actually shown: the saved one, or the phone's own if it is supported, else English. */
    fun effective(context: Context): String {
        val s = saved(context)
        if (s != SYSTEM) return s
        val sys = context.applicationContext.resources.configuration.locales[0].language
        return if (sys in SUPPORTED) sys else "en"
    }

    /** [base] with the chosen language applied (returned unchanged for "system default"). */
    fun wrap(base: Context): Context {
        val code = saved(base)
        if (code == SYSTEM) return base
        val locale = Locale(code)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }

    fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}
