package com.wmserp.app.presentation.picking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wmserp.app.R
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.theme.WmsTheme

/**
 * Correction of one pick before the final confirmation: the picked quantity can be changed (0 up
 * to the required quantity) or the pick removed, which also clears a batch the picker scanned so
 * the row can be scanned again with the right one. A row the server already confirmed only
 * offers the removal.
 */
@Composable
fun PickLineEditDialog(
    edit: PickLineEdit,
    onQtyChange: (String) -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("pick_edit_dialog"),
        title = { Text(stringResource(R.string.pick_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(edit.itemName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(edit.itemCode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    edit.batchNo?.let { StatusChip(stringResource(R.string.scan_qty_batch, it), MaterialTheme.colorScheme.primary) }
                }
                if (edit.batchFromWms) {
                    Text(stringResource(R.string.pick_edit_batch_scanned), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(R.string.pick_edit_required, Formatters.qty(edit.required), edit.uom ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (edit.confirmed) {
                    Text(stringResource(R.string.pick_edit_confirmed_hint), style = MaterialTheme.typography.bodyMedium, color = WmsTheme.colors.warning)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDecrement, modifier = Modifier.testTag("pick_edit_minus")) {
                            Icon(Icons.Outlined.Remove, contentDescription = null)
                        }
                        OutlinedTextField(
                            value = edit.qtyText,
                            onValueChange = onQtyChange,
                            label = { Text(stringResource(R.string.pick_edit_qty)) },
                            singleLine = true,
                            isError = !edit.isValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag("pick_edit_qty"),
                        )
                        OutlinedButton(onClick = onIncrement, modifier = Modifier.testTag("pick_edit_plus")) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                        }
                    }
                }
                Text(stringResource(R.string.pick_edit_remove_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().testTag("pick_edit_remove")) {
                    Text(stringResource(R.string.pick_edit_remove), color = WmsTheme.colors.danger)
                }
            }
        },
        confirmButton = {
            if (!edit.confirmed) {
                Button(onClick = onSave, enabled = edit.isValid, modifier = Modifier.testTag("pick_edit_save")) {
                    Text(stringResource(R.string.pick_edit_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("pick_edit_cancel")) {
                Text(stringResource(R.string.common_cancel))
            }
            Spacer()
        },
    )
}

@Composable
private fun Spacer() {
    androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
}
