package com.wmserp.app.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.wmserp.app.R
import com.wmserp.app.domain.model.RequiredField

/**
 * Asks for the required fields an ERPNext site added to a document (e.g. a mandatory Department)
 * that the app cannot fill from the order. Link and Select fields offer their values; anything
 * else is typed. The answers are remembered for the next documents.
 */
@Composable
fun RequiredFieldsDialog(
    doctype: String,
    fields: List<RequiredField>,
    answers: Map<String, String>,
    linkOptions: Map<String, List<String>>,
    onAnswerChange: (String, String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val complete = fields.all { !answers[it.key].isNullOrBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("required_fields_dialog"),
        title = { Text(stringResource(R.string.required_fields_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.required_fields_message, doctype), style = MaterialTheme.typography.bodyMedium)
                fields.forEach { field ->
                    val value = answers[field.key].orEmpty()
                    val suggestions = if (field.isLink) linkOptions[field.key].orEmpty() else field.selectOptions
                    SuggestionField(
                        label = field.label,
                        value = value,
                        options = suggestions,
                        onValueChange = { onAnswerChange(field.key, it) },
                        modifier = Modifier.testTag("required_field_${field.fieldname}"),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = complete, modifier = Modifier.testTag("required_fields_confirm")) {
                Text(stringResource(R.string.required_fields_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.required_fields_cancel)) }
        },
    )
}

/** Text field with a filtered suggestion list; free text stays allowed for values the list does not show. */
@Composable
private fun SuggestionField(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(value, options) {
        if (value.isBlank()) options else options.filter { it.contains(value, ignoreCase = true) }
    }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) expanded = true },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                if (options.isNotEmpty()) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                    }
                }
            },
        )
        DropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = false),
        ) {
            filtered.take(MAX_SUGGESTIONS).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private const val MAX_SUGGESTIONS = 30
