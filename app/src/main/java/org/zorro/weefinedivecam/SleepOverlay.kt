package org.zorro.weefinedivecam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes

private val DIM_GREY = Color(0xFF3A3A3A)

// Small offsets cycled every minute so the static text doesn't burn into the OLED panel
private val BURN_IN_OFFSETS = listOf(0 to 0, 24 to 12, -24 to 24, 12 to -16, -12 to -8)

/**
 * Black screen shown while asleep or when the housing is lost. Black pixels are switched off
 * on an OLED panel, so this costs almost nothing, unlike a camera preview at full brightness.
 * [message] (depth and temperature, or the lost connection) stays visible in dim grey.
 */
@Composable
fun SleepOverlay(
    message: String,
    phoneBattery: Int?,
    onTap: () -> Unit, // Touch doesn't work through the housing, handy on land
    modifier: Modifier = Modifier
) {
    var offsetIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1.minutes)
            offsetIndex = (offsetIndex + 1) % BURN_IN_OFFSETS.size
        }
    }
    val (dx, dy) = BURN_IN_OFFSETS[offsetIndex]

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(20f)
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.offset(dx.dp, dy.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = DIM_GREY,
                fontSize = 20.sp
            )
            if (phoneBattery != null) {
                BatteryIndicator(Icons.Default.Smartphone, "Phone battery", phoneBattery, tint = DIM_GREY, fontSize = 20.sp)
            }
        }
    }
}
