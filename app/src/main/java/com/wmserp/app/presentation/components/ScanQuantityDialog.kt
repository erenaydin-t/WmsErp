package com.wmserp.app.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wmserp.app.R
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.presentation.common.ScanQuantityPrompt

/**
 * Asked after a matching scan: how many units does this scan stand for? The field is prefilled
 * with everything still open on the row, so confirming takes the whole quantity; the picker can
 * lower it with the steppers or by typing (tapping the field selects it). Values above the open
 * quantity are refused before anything reaches ERPNext.
 */
@Composable
fun ScanQuantityDialog(
    prompt: ScanQuantityPrompt,
    title: String,
    onQtyChange: (String) -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onAll: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val remaining = Formatters.qty(prompt.remaining)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("scan_qty_dialog"),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(prompt.itemName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(prompt.itemCode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    prompt.batchNo?.let { StatusChip(stringResource(R.string.scan_qty_batch, it), MaterialTheme.colorScheme.primary) }
                }
                Text(
                    stringResource(R.string.scan_qty_remaining, remaining, prompt.uom ?: ""),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("scan_qty_remaining"),
                )
                QuantityEditor(prompt = prompt, onQtyChange = onQtyChange, onIncrement = onIncrement, onDecrement = onDecrement, onConfirm = onConfirm)
                AssistChip(
                    onClick = onAll,
                    label = { Text(stringResource(R.string.scan_qty_all, remaining)) },
                    leadingIcon = { Icon(Icons.Outlined.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("scan_qty_all"),
                )
                if (prompt.isValid) {
                    Text(stringResource(R.string.scan_qty_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.scan_qty_invalid, remaining), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = prompt.isValid, modifier = Modifier.testTag("scan_qty_confirm")) {
                Text(stringResource(R.string.scan_qty_confirm, Formatters.qty(prompt.qty)))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("scan_qty_cancel")) { Text(stringResource(R.string.scan_qty_cancel)) }
        },
    )
}

/** Large +/- buttons around the quantity; the text is selected when the field gains focus so typing replaces it. */
@Composable
private fun QuantityEditor(
    prompt: ScanQuantityPrompt,
    onQtyChange: (String) -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onConfirm: () -> Unit,
) {
    var field by remember(prompt.rowName) { mutableStateOf(TextFieldValue(prompt.qtyText, TextRange(prompt.qtyText.length))) }
    LaunchedEffect(prompt.qtyText) {
        if (field.text != prompt.qtyText) field = TextFieldValue(prompt.qtyText, TextRange(prompt.qtyText.length))
    }
    val uomSuffix: (@Composable () -> Unit)? = prompt.uom?.let { uom -> { Text(uom, style = MaterialTheme.typography.labelMedium) } }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = onDecrement, enabled = prompt.qty > 0.0, modifier = Modifier.size(48.dp).testTag("scan_qty_minus")) {
            Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.qty_decrease))
        }
        OutlinedTextField(
            value = field,
            onValueChange = { new ->
                field = new
                onQtyChange(new.text)
            },
            modifier = Modifier
                .weight(1f)
                .scannerAwareFocus()
                .onFocusChanged { if (it.isFocused) field = field.copy(selection = TextRange(0, field.text.length)) }
                .testTag("scan_qty_input"),
            singleLine = true,
            isError = !prompt.isValid,
            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.Bold),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (prompt.isValid) onConfirm() }),
            suffix = uomSuffix,
        )
        FilledTonalIconButton(onClick = onIncrement, enabled = prompt.qty < prompt.remaining, modifier = Modifier.size(48.dp).testTag("scan_qty_plus")) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.qty_increase))
        }
    }
}
