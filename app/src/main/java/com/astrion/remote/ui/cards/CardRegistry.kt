package com.astrion.remote.ui.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astrion.remote.config.CardConfig
import com.astrion.remote.ha.EntityState
import com.astrion.remote.ha.HaClient
import com.astrion.remote.ha.str

@Composable
fun CardRenderer(card: CardConfig, entities: Map<String, EntityState>, haClient: HaClient) {
    val entityId = card.options.str("entity_id")
    val entity = entityId?.let { entities[it] }
    when (card.type) {
        "bubble_light" -> BubbleLightCard(card, entities, haClient)
        "scene_grid" -> SceneGridCard(card, haClient)
        "climate" -> ClimateCard(card, entity, haClient)
        "cover" -> CoverCard(card, entity, haClient)
        "media_player" -> if (card.variant == "full") {
            MediaPlayerFullCard(card, entity, haClient)
        } else {
            MediaPlayerCompactCard(card, entity, haClient)
        }
        "clock_weather" -> ClockWeatherCard(card, entity, haClient)
        "picture_elements" -> PictureElementsCard(card, entities, haClient)
        "button_grid" -> ButtonGridCard(card, haClient)
        "plex" -> PlexCard(card, entities, haClient)
        "separator" -> SeparatorCard(card)
        else -> UnknownCard(card, entity)
    }
}

/** Fallback for card types Milestone 3 hasn't built dedicated UI for yet (picture_elements, button_grid, etc). */
@Composable
internal fun UnknownCard(card: CardConfig, entity: EntityState?) {
    val entityId = card.options.str("entity_id")
    val name = card.options.str("name") ?: entityId ?: card.type

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                card.type + (card.variant?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when {
                entity != null -> Text(entity.state, style = MaterialTheme.typography.bodyMedium)
                entityId != null -> Text(
                    "entity not found: $entityId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                else -> Text(
                    "(card UI not implemented yet)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
