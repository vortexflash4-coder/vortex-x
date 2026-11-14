package com.vortexx.player.ui.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vortexx.player.domain.model.SortField
import com.vortexx.player.domain.model.SortSpec
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexRadius
import com.vortexx.player.ui.designsystem.theme.VortexSpacing

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(VortexRadius.pill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(VortexColors.Card)
            .border(1.dp, VortexColors.Divider, shape)
            .padding(horizontal = VortexSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = VortexColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(VortexSpacing.sm))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = VortexColors.TextSecondary)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = VortexColors.TextPrimary),
                cursorBrush = SolidColor(VortexColors.AccentActive),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }) {
                Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = VortexColors.TextSecondary)
            }
        }
    }
}

/** Sort button with name/date/duration/size and direction. */
@Composable
fun SortMenuButton(
    spec: SortSpec,
    onChange: (SortSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(sortLabel(field)) },
                    onClick = {
                        onChange(spec.copy(field = field))
                        expanded = false
                    },
                    trailingIcon = {
                        if (spec.field == field) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = VortexColors.AccentActive)
                    },
                )
            }
            HorizontalDivider(color = VortexColors.Divider)
            DropdownMenuItem(
                text = { Text("Ascending") },
                onClick = {
                    onChange(spec.copy(ascending = true))
                    expanded = false
                },
                trailingIcon = { if (spec.ascending) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = VortexColors.AccentActive) },
            )
            DropdownMenuItem(
                text = { Text("Descending") },
                onClick = {
                    onChange(spec.copy(ascending = false))
                    expanded = false
                },
                trailingIcon = { if (!spec.ascending) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = VortexColors.AccentActive) },
            )
        }
    }
}

fun sortLabel(field: SortField): String = when (field) {
    SortField.Name -> "Name"
    SortField.DateAdded -> "Date added"
    SortField.Duration -> "Duration"
    SortField.Size -> "Size"
}
