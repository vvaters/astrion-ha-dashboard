package com.astrion.remote.ui.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.astrion.remote.config.CardConfig
import com.astrion.remote.ha.EntityState
import com.astrion.remote.ha.HaClient
import com.astrion.remote.ha.str
import com.astrion.remote.ui.NetworkImage
import com.astrion.remote.ui.resolveImageUrl

@Composable
fun MediaPlayerCompactCard(card: CardConfig, entity: EntityState?, haClient: HaClient) {
    val entityId = card.options.str("entity_id")
    if (entityId == null) {
        UnknownCard(card, entity)
        return
    }
    val attrs = entity?.attributes
    val title = attrs?.str("media_title") ?: entity?.state ?: "—"
    val artist = attrs?.str("media_artist")
    val artUrl = attrs?.str("entity_picture")?.let { resolveImageUrl(haClient.host, it) }

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { haClient.callService("media_player", "media_play_pause", entityId) }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NetworkImage(artUrl, haClient.token, modifier = Modifier.size(48.dp).clip(CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (artist != null) {
                    Text(
                        artist,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = { haClient.callService("media_player", "volume_down", entityId) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, "volume down")
            }
            IconButton(onClick = { haClient.callService("media_player", "volume_up", entityId) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, "volume up")
            }
        }
    }
}

@Composable
fun MediaPlayerFullCard(card: CardConfig, entity: EntityState?, haClient: HaClient) {
    val entityId = card.options.str("entity_id")
    if (entityId == null) {
        UnknownCard(card, entity)
        return
    }
    val attrs = entity?.attributes
    val title = attrs?.str("media_title") ?: entity?.state ?: "—"
    val artist = attrs?.str("media_artist")
    val artUrl = attrs?.str("entity_picture")?.let { resolveImageUrl(haClient.host, it) }
    val isPlaying = entity?.state == "playing"

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NetworkImage(artUrl, haClient.token, modifier = Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (artist != null) {
                Text(artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { haClient.callService("media_player", "volume_down", entityId) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, "volume down")
                }
                IconButton(onClick = { haClient.callService("media_player", "media_previous_track", entityId) }) {
                    Icon(Icons.Filled.SkipPrevious, "previous")
                }
                IconButton(
                    onClick = { haClient.callService("media_player", "media_play_pause", entityId) },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "play/pause",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { haClient.callService("media_player", "media_next_track", entityId) }) {
                    Icon(Icons.Filled.SkipNext, "next")
                }
                IconButton(onClick = { haClient.callService("media_player", "volume_up", entityId) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, "volume up")
                }
            }
        }
    }
}
