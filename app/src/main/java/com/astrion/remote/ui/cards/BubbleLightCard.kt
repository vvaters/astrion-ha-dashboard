package com.astrion.remote.ui.cards

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.astrion.remote.config.CardConfig
import com.astrion.remote.ha.EntityState
import com.astrion.remote.ha.HaClient
import com.astrion.remote.ha.int
import com.astrion.remote.ha.rgbColor
import com.astrion.remote.ha.str
import com.astrion.remote.ha.stringList
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BubbleLightCard(
    card: CardConfig,
    allEntities: Map<String, EntityState>,
    haClient: HaClient
) {
    // Single "entity_id" or an "entities" list — one card controlling N lights as a unit.
    val entityIds = card.options.stringList("entities")
        .ifEmpty { listOfNotNull(card.options.str("entity_id")) }
    if (entityIds.isEmpty()) {
        UnknownCard(card, null)
        return
    }
    val entityId = entityIds.first()
    val states = entityIds.mapNotNull { allEntities[it] }
    // Representative entity for brightness/color display: prefer one that's on.
    val entity = states.firstOrNull { it.state == "on" } ?: states.firstOrNull()
    val name = card.options.str("name") ?: entityId
    val attrs = entity?.attributes
    val isOn = states.any { it.state == "on" }

    fun callAll(service: String, data: kotlinx.serialization.json.JsonObject? = null) {
        entityIds.forEach { haClient.callService("light", service, it, data) }
    }
    val brightness255 = attrs?.int("brightness")
    val brightnessPct = if (isOn) (((brightness255 ?: 255) / 255f) * 100f) else 0f
    val rgb = attrs?.rgbColor("rgb_color")
    val supportedModes = attrs?.stringList("supported_color_modes") ?: emptyList()
    val supportsColor = supportedModes.any { it in listOf("rgb", "rgbw", "rgbww", "hs", "xy") }
    val supportsColorTemp = "color_temp" in supportedModes

    var isDragging by remember { mutableStateOf(false) }
    var localValue by remember(entityIds) { mutableStateOf(brightnessPct) }
    if (!isDragging) localValue = brightnessPct

    var showPopup by remember { mutableStateOf(false) }

    val tint = if (isOn && rgb != null && rgb.any { it < 250 }) {
        // Use the light's real color, but not near-white (invisible on dark cards)
        Color(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f)
    } else {
        MaterialTheme.colorScheme.primary
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            callAll(if (isOn) "turn_off" else "turn_on")
                        },
                        onLongClick = {
                            if (supportsColor || supportsColorTemp) showPopup = true
                        }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isOn) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = if (isOn) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (isOn) "${localValue.roundToInt()}%" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Slider(
                value = localValue,
                onValueChange = {
                    isDragging = true
                    localValue = it
                },
                onValueChangeFinished = {
                    isDragging = false
                    callAll("turn_on", buildJsonObject { put("brightness_pct", localValue.roundToInt()) })
                },
                valueRange = 0f..100f,
                enabled = isOn,
                colors = SliderDefaults.colors(
                    thumbColor = tint,
                    activeTrackColor = tint
                )
            )
        }
    }

    if (showPopup) {
        LightColorPopup(
            entityIds = entityIds,
            haClient = haClient,
            supportsColor = supportsColor,
            supportsColorTemp = supportsColorTemp,
            onDismiss = { showPopup = false }
        )
    }
}

private val SWATCHES = listOf(
    "Red" to Color(0xFFF44336),
    "Orange" to Color(0xFFFF9800),
    "Yellow" to Color(0xFFFFEB3B),
    "Green" to Color(0xFF4CAF50),
    "Cyan" to Color(0xFF00BCD4),
    "Blue" to Color(0xFF2196F3),
    "Purple" to Color(0xFF9C27B0),
    "Pink" to Color(0xFFE91E63),
    "White" to Color.White
)

private val COLOR_TEMP_PRESETS = listOf("Warm" to 2700, "Neutral" to 4000, "Cool" to 6500)

@Composable
private fun LightColorPopup(
    entityIds: List<String>,
    haClient: HaClient,
    supportsColor: Boolean,
    supportsColorTemp: Boolean,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp) {
            Column(Modifier.padding(16.dp)) {
                if (supportsColor) {
                    Text("Color", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(SWATCHES) { (_, color) ->
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable {
                                        val data = buildJsonObject {
                                            putJsonArray("rgb_color") {
                                                add((color.red * 255).roundToInt())
                                                add((color.green * 255).roundToInt())
                                                add((color.blue * 255).roundToInt())
                                            }
                                        }
                                        entityIds.forEach {
                                            haClient.callService("light", "turn_on", it, data)
                                        }
                                        onDismiss()
                                    }
                            )
                        }
                    }
                }
                if (supportsColorTemp) {
                    Spacer(Modifier.height(16.dp))
                    Text("Color temperature", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        COLOR_TEMP_PRESETS.forEach { (label, kelvin) ->
                            AssistChip(
                                onClick = {
                                    val data = buildJsonObject { put("color_temp_kelvin", kelvin) }
                                    entityIds.forEach {
                                        haClient.callService("light", "turn_on", it, data)
                                    }
                                    onDismiss()
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}
