package com.astrion.remote.ui.cards

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.astrion.remote.config.CardConfig
import com.astrion.remote.ha.EntityState
import com.astrion.remote.ha.HaClient
import com.astrion.remote.ha.double
import com.astrion.remote.ha.str
import com.astrion.remote.ha.stringList
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Chunky climate card tuned for a 480px-wide touchscreen: big entity name, oversized
 * setpoint steppers (hard-to-miss touch targets), mode/preset chips, and percent fan
 * speeds (like the BedJet's twenty 5%-steps) collapsed into a slider instead of a chip
 * wall. Layout inspired by the HA100 community climate dial + HA's Mushroom cards.
 */
@Composable
fun ClimateCard(card: CardConfig, entity: EntityState?, haClient: HaClient) {
    val entityId = card.options.str("entity_id")
    if (entityId == null) {
        UnknownCard(card, entity)
        return
    }
    val name = card.options.str("name") ?: entityId

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (entity == null) {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text("unavailable", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            val attrs = entity.attributes
            val currentTemp = attrs.double("current_temperature")
            val targetTemp = attrs.double("temperature")
            val step = attrs.double("target_temp_step") ?: 1.0
            val hvacModes = attrs.stringList("hvac_modes")
            val presetModes = attrs.stringList("preset_modes")
            val presetMode = attrs.str("preset_mode")
            val fanModes = attrs.stringList("fan_modes")
            val fanMode = attrs.str("fan_mode")
            val hvacMode = entity.state

            // Header: big name left, current temp right
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    currentTemp?.let { "now %.0f°".format(it) } ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))

            // Setpoint: oversized − [temp] + row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = {
                        haClient.callService(
                            "climate", "set_temperature", entityId,
                            buildJsonObject { put("temperature", roundToStep((targetTemp ?: 70.0) - step, step)) }
                        )
                    },
                    modifier = Modifier.size(60.dp)
                ) { Icon(Icons.Filled.Remove, "cooler", Modifier.size(30.dp)) }

                Text(
                    targetTemp?.let { if (step >= 1.0) "${it.roundToInt()}°" else "%.1f°".format(it) } ?: "--",
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    color = if (hvacMode == "off") MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )

                FilledTonalIconButton(
                    onClick = {
                        haClient.callService(
                            "climate", "set_temperature", entityId,
                            buildJsonObject { put("temperature", roundToStep((targetTemp ?: 70.0) + step, step)) }
                        )
                    },
                    modifier = Modifier.size(60.dp)
                ) { Icon(Icons.Filled.Add, "warmer", Modifier.size(30.dp)) }
            }

            // HVAC modes
            if (hvacModes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(hvacModes) { mode ->
                        FilterChip(
                            selected = mode == hvacMode,
                            onClick = {
                                haClient.callService(
                                    "climate", "set_hvac_mode", entityId,
                                    buildJsonObject { put("hvac_mode", mode) }
                                )
                            },
                            label = { Text(mode.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }

            // Presets (e.g. BedJet Turbo / M2: SLEEP)
            if (presetModes.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(presetModes) { preset ->
                        FilterChip(
                            selected = preset == presetMode,
                            onClick = {
                                haClient.callService(
                                    "climate", "set_preset_mode", entityId,
                                    buildJsonObject { put("preset_mode", preset) }
                                )
                            },
                            label = { Text(preset) }
                        )
                    }
                }
            }

            // Fan: percent-mode lists (BedJet's 5%..100%) become a slider; short lists stay chips
            if (fanModes.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val percents = fanModes.mapNotNull { it.removeSuffix("%").toIntOrNull() }
                if (percents.size == fanModes.size && percents.size > 6) {
                    // Discrete −/+ stepping, not a slider: a slider inside the pager
                    // hijacks horizontal swipes and changes fan speed by accident.
                    val sorted = percents.sorted()
                    val currentPct = fanMode?.removeSuffix("%")?.toIntOrNull() ?: sorted.first()
                    fun stepFan(dir: Int) {
                        val idx = sorted.indexOfFirst { it == currentPct }.takeIf { it >= 0 }
                            ?: sorted.indexOfFirst { it >= currentPct }.takeIf { it >= 0 } ?: 0
                        val next = sorted.getOrNull((idx + dir).coerceIn(0, sorted.lastIndex)) ?: return
                        haClient.callService(
                            "climate", "set_fan_mode", entityId,
                            buildJsonObject { put("fan_mode", "$next%") }
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Fan", style = MaterialTheme.typography.labelMedium)
                        FilledTonalIconButton(onClick = { stepFan(-1) }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Filled.Remove, "slower", Modifier.size(22.dp))
                        }
                        Text(
                            "$currentPct%",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(58.dp)
                        )
                        FilledTonalIconButton(onClick = { stepFan(1) }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Filled.Add, "faster", Modifier.size(22.dp))
                        }
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(fanModes) { mode ->
                            FilterChip(
                                selected = mode == fanMode,
                                onClick = {
                                    haClient.callService(
                                        "climate", "set_fan_mode", entityId,
                                        buildJsonObject { put("fan_mode", mode) }
                                    )
                                },
                                label = { Text("Fan $mode") }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun roundToStep(value: Double, step: Double): Double {
    if (step <= 0.0) return value
    return (value / step).roundToInt() * step
}
