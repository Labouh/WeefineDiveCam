package org.zorro.weefinedivecam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** Phone battery in percent, or null until known. Replaces the hidden status bar. */
@Composable
fun rememberPhoneBatteryLevel(): Int? {
    val context = LocalContext.current
    var level by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                level = intent.batteryPercent()
            }
        }
        // ACTION_BATTERY_CHANGED is sticky: registering returns the current level right away
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )?.let { level = it.batteryPercent() }

        onDispose { context.unregisterReceiver(receiver) }
    }
    return level
}

private fun Intent.batteryPercent(): Int? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else null
}

fun batteryColor(percent: Int): Color = when {
    percent <= 20 -> Color.Red
    percent <= 50 -> Color.Yellow
    else -> Color.Green
}

private fun batteryIcon(percent: Int): ImageVector = when {
    percent >= 95 -> Icons.Default.BatteryFull
    percent >= 80 -> Icons.Default.Battery6Bar
    percent >= 65 -> Icons.Default.Battery5Bar
    percent >= 50 -> Icons.Default.Battery4Bar
    percent >= 35 -> Icons.Default.Battery3Bar
    percent >= 20 -> Icons.Default.Battery2Bar
    percent >= 10 -> Icons.Default.Battery1Bar
    else -> Icons.Default.BatteryAlert
}

/**
 * [source] icon (phone, housing...) followed by a battery gauge and the percentage.
 * @param tint fixed colour, or null to colour by charge level.
 */
@Composable
fun BatteryIndicator(
    source: ImageVector,
    sourceDescription: String,
    percent: Int,
    tint: Color? = null,
    fontSize: TextUnit = 12.sp
) {
    val color = tint ?: batteryColor(percent)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(source, contentDescription = sourceDescription, tint = color, modifier = Modifier.size(16.dp))
        Icon(batteryIcon(percent), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text = "$percent%", color = color, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}
