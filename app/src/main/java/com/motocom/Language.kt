package com.motocom

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's own language, Spanish unless English is chosen, whatever the phone is set to.
 * Activities and services see it through [wrap]; code holding only the application context
 * asks [text] for each string, so it follows a change without restarting.
 */
object Language {
    const val SPANISH = "es"
    const val ENGLISH = "en"
    val ALL = listOf(SPANISH, ENGLISH)

    /** The last context made for a language, so [text] does not build one per string. */
    @Volatile private var cached: Pair<String, Context>? = null
    @Volatile private var spanishContext: Context? = null

    fun current(context: Context): String =
        prefs(context).getString(KEY, SPANISH)?.takeIf { it in ALL } ?: SPANISH

    fun set(context: Context, tag: String) {
        prefs(context).edit().putString(KEY, tag).commit()
        cached = null
    }

    /** [base] with the chosen language, for attachBaseContext. */
    fun wrap(base: Context): Context = inLanguage(base, current(base))

    fun text(context: Context, id: Int, vararg args: Any): String {
        val tag = current(context)
        val localized = cached?.takeIf { it.first == tag }?.second
            ?: inLanguage(context.applicationContext, tag).also { cached = tag to it }
        return format(localized, id, args)
    }

    /** In Spanish whatever the choice, for the log, which is a record for whoever fixes the app. */
    fun spanish(context: Context, id: Int, vararg args: Any): String = format(inSpanish(context), id, args)

    fun inSpanish(context: Context): Context =
        spanishContext ?: inLanguage(context.applicationContext, SPANISH).also { spanishContext = it }

    // Without arguments the text is not formatted, as with stringResource, so a lone % is safe.
    fun format(context: Context, id: Int, args: Array<out Any>): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    /** Only the language is overridden; size, theme and the rest still follow the phone. */
    private fun inLanguage(base: Context, tag: String): Context =
        base.createConfigurationContext(Configuration().apply { setLocale(Locale.forLanguageTag(tag)) })

    private fun prefs(context: Context) = context.getSharedPreferences("motocom_ui", Context.MODE_PRIVATE)

    private const val KEY = "idioma"
}

/**
 * A text kept as its resource, to be read in whatever language is on when it is shown: one
 * said in Spanish and shown after switching to English comes out in English. An argument may
 * be another [Said].
 */
class Said(val id: Int, vararg val args: Any) {
    fun read(context: Context): String =
        Language.format(context, id, Array(args.size) { args[it].let { arg -> if (arg is Said) arg.read(context) else arg } })

    fun forLog(context: Context) = read(Language.inSpanish(context))
}

/** Something the app itself refused, with its reason as a [Said]. */
class Refused(val said: Said) : IllegalStateException()
