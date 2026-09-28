package com.wmserp.app.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Resolves a [UiText] against the current (possibly app-overridden) locale. */
@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
    is UiText.Plain -> value
}
