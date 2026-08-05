package com.astrion.remote.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Curtains
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astrion.remote.config.CardConfig
import com.astrion.remote.ha.EntityState
import com.astrion.remote.ha.HaClient
import com.astrion.remote.ha.str

@Composable
fun CoverCard(card: CardConfig, entity: EntityState?, haClient: HaClient) {
    val entityId = card.options.str("entity_id")
    if (entityId == null) {
        UnknownCard(card, entity)
        return
    }
    val name = card.options.str("name") ?: entityId
    val state = entity?.state ?: "unavailable"

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Curtains,
                    contentDescription = null,
                    tint = if (state == "open" || state == "opening") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.padding(start = 10.dp))
                Column {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        state,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row {
                IconButton(onClick = { haClient.callService("cover", "open_cover", entityId) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, "open")
                }
                IconButton(onClick = { haClient.callService("cover", "stop_cover", entityId) }) {
                    Icon(Icons.Filled.Stop, "stop")
                }
                IconButton(onClick = { haClient.callService("cover", "close_cover", entityId) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "close")
                }
            }
        }
    }
}
