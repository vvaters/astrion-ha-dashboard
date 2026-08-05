package com.astrion.remote.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.astrion.remote.config.CardConfig
import com.astrion.remote.ha.HaClient
import com.astrion.remote.ha.str
import kotlinx.serialization.json.jsonObject

private data class SceneItem(val entityId: String, val name: String, val color: Color?)

@Composable
fun SceneGridCard(card: CardConfig, haClient: HaClient) {
    val scenesJson = card.options["scenes"]
    if (scenesJson == null) {
        UnknownCard(card, null)
        return
    }
    val layout = card.options.str("layout") ?: "row"
    val scenes = scenesJson.jsonArrayOrEmpty().mapNotNull { el ->
        val o = el.jsonObject
        val entityId = o.str("entity_id") ?: return@mapNotNull null
        SceneItem(entityId, o.str("name") ?: entityId, o.str("color")?.let { parseArgb(it) })
    }
    if (scenes.isEmpty()) {
        UnknownCard(card, null)
        return
    }

    if (layout == "row") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scenes) { s -> SceneTile(s, haClient) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            scenes.chunked(2).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { s ->
                        Box(Modifier.weight(1f)) { SceneTile(s, haClient) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneTile(item: SceneItem, haClient: HaClient) {
    val domain = item.entityId.substringBefore(".")
    Box(
        Modifier
            .size(width = 104.dp, height = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(item.color ?: MaterialTheme.colorScheme.secondaryContainer)
            .clickable { haClient.callService(domain, "turn_on", item.entityId) }
            .padding(10.dp)
    ) {
        Text(
            item.name,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

private fun parseArgb(hex: String): Color? {
    val clean = hex.removePrefix("#")
    if (clean.length != 8) return null
    return try {
        Color(clean.toLong(16).toInt())
    } catch (e: NumberFormatException) {
        null
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrEmpty(): List<kotlinx.serialization.json.JsonElement> =
    (this as? kotlinx.serialization.json.JsonArray) ?: emptyList()
