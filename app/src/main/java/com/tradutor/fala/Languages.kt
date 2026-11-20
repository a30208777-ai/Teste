package com.tradutor.fala

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage

/** Idiomas oferecidos no app (código ML Kit -> nome). */
object Languages {
    val list: List<Pair<String, String>> = listOf(
        TranslateLanguage.PORTUGUESE to "Português",
        TranslateLanguage.ENGLISH to "Inglês",
        TranslateLanguage.SPANISH to "Espanhol",
        TranslateLanguage.FRENCH to "Francês",
        TranslateLanguage.GERMAN to "Alemão",
        TranslateLanguage.ITALIAN to "Italiano",
        TranslateLanguage.JAPANESE to "Japonês",
        TranslateLanguage.KOREAN to "Coreano",
        TranslateLanguage.CHINESE to "Chinês",
        TranslateLanguage.RUSSIAN to "Russo",
        TranslateLanguage.ARABIC to "Árabe",
        TranslateLanguage.HINDI to "Hindi",
        TranslateLanguage.TURKISH to "Turco",
        TranslateLanguage.DUTCH to "Holandês",
        TranslateLanguage.POLISH to "Polonês",
    )

    fun indexOf(code: String): Int = list.indexOfFirst { it.first == code }.coerceAtLeast(0)
}

/** Idiomas escolhidos, salvos para o serviço e o menu "Traduzir e falar" usarem. */
object Prefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tradutor", Context.MODE_PRIVATE)

    fun source(ctx: Context): String =
        prefs(ctx).getString("source", TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH

    fun target(ctx: Context): String =
        prefs(ctx).getString("target", TranslateLanguage.PORTUGUESE) ?: TranslateLanguage.PORTUGUESE

    fun save(ctx: Context, source: String, target: String) {
        prefs(ctx).edit().putString("source", source).putString("target", target).apply()
    }
}
