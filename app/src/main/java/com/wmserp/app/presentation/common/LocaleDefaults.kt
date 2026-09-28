package com.wmserp.app.presentation.common

import com.wmserp.app.domain.model.AppLanguage
import java.util.Locale

/** Keeps java.util.Locale defaults (month/day names in analytics) in step with the app language. */
object LocaleDefaults {
    private val systemDefault: Locale = Locale.getDefault()

    fun apply(language: AppLanguage) {
        val tag = language.tag
        Locale.setDefault(if (tag == null) systemDefault else Locale.forLanguageTag(tag))
    }
}
