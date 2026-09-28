package com.wmserp.app.presentation.common

import android.content.Context
import android.content.res.Configuration
import android.text.TextUtils
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.wmserp.app.domain.model.AppLanguage
import java.util.Locale

/**
 * Applies the in-app language choice to everything below it: string resources, layout direction
 * and configuration. [AppLanguage.SYSTEM] leaves the system locale untouched so Android 13+
 * per-app language settings keep working.
 */
@Composable
fun LocalizedContent(language: AppLanguage, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val tag = language.tag
    if (tag == null) {
        content()
        return
    }
    val localized = remember(baseContext, baseConfiguration, tag) {
        val locale = Locale.forLanguageTag(tag)
        val configuration = Configuration(baseConfiguration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        val context = baseContext.createConfigurationContext(configuration)
        val direction = if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
        Localized(context, configuration, direction)
    }
    CompositionLocalProvider(
        LocalContext provides localized.context,
        LocalConfiguration provides localized.configuration,
        LocalLayoutDirection provides localized.direction,
        content = content,
    )
}

private data class Localized(val context: Context, val configuration: Configuration, val direction: LayoutDirection)
