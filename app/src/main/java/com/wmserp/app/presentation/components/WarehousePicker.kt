package com.wmserp.app.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.PopupProperties
import com.wmserp.app.R
import com.wmserp.app.domain.model.Warehouse

/** Editable warehouse field with a suggestion list; typing filters the suggestions. */
@Composable
fun WarehousePicker(
    label: String,
    value: String,
    options: List<Warehouse>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(value, options) {
        if (value.isBlank()) options else options.filter { it.name.contains(value, ignoreCase = true) || it.warehouseName.contains(value, ignoreCase = true) }
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
                .scannerAwareFocus()
                .onFocusChanged { if (it.isFocused) expanded = true },
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }, enabled = enabled) {
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = stringResource(if (expanded) R.string.picker_hide_warehouses else R.string.picker_show_warehouses),
                    )
                }
            },
        )
        DropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = false),
        ) {
            filtered.take(MAX_SUGGESTIONS).forEach { warehouse ->
                DropdownMenuItem(
                    text = { Text(warehouse.name) },
                    onClick = {
                        onValueChange(warehouse.name)
                        expanded = false
                    },
                )
            }
        }
    }
}

private const val MAX_SUGGESTIONS = 30
