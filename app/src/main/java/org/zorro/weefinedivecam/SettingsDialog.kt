package org.zorro.weefinedivecam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val WaterType.description: String
    get() = when (this) {
        WaterType.CLEAR_OCEAN -> "Clear blue sea, tropics, Med."
        WaterType.COASTAL -> "Blue-green sea, Atlantic, Channel"
        WaterType.GREEN_LAKE -> "Green fresh water, lakes, quarries"
    }

/**
 * Dive site settings, set on land before the dive: the touchscreen doesn't work in the housing.
 * Choices sit side by side because the app runs in landscape, where height is scarce.
 */
@Composable
fun SettingsDialog(
    waterType: WaterType,
    onWaterTypeSelected: (WaterType) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Water type", style = MaterialTheme.typography.titleLarge)

                Row(
                    // Cards grow to the tallest description and share its height
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(top = 12.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WaterType.entries.forEach { type ->
                        val selected = type == waterType
                        OutlinedCard(
                            border = BorderStroke(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .selectable(selected = selected, onClick = { onWaterTypeSelected(type) }, role = Role.RadioButton)
                        ) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
                                RadioButton(selected = selected, onClick = null)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(type.label, style = MaterialTheme.typography.titleMedium)
                                    Text(type.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("OK")
                }
            }
        }
    }
}
