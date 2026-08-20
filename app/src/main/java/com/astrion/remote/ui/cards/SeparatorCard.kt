package com.astrion.remote.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrion.remote.config.CardConfig
import com.astrion.remote.ha.str

/**
 * A section heading: small icon, label, then a rule filling the rest of the row.
 *
 * Purely visual, and that is the point — on a 480x800 screen a long page of
 * cards reads as one undifferentiated column, and a heading costs a single row
 * to break it into scannable groups. It pairs with hotkey `scroll_to`, since a
 * separator's name is a natural anchor to jump to.
 *
 * ```json
 * { "type": "separator", "options": { "name": "Climate", "icon": "thermostat" } }
 * ```
 *
 * Both options are optional: with no name it is just a rule, which is useful for
 * splitting a group without labelling it.
 */
@Composable
fun SeparatorCard(card: CardConfig) {
    val name = card.options.str("name").orEmpty()
    val icon = when (card.options.str("icon")) {
        "light" -> Icons.Filled.Lightbulb
        "tv", "screen" -> Icons.Filled.Tv
        "thermostat", "climate" -> Icons.Filled.Thermostat
        "music", "media" -> Icons.Filled.MusicNote
        "curtain", "cover", "shade" -> Icons.Filled.VerticalAlignCenter
        "ac" -> Icons.Filled.AcUnit
        else -> Icons.Filled.Menu
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color(0xFF7FB3C4),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        if (name.isNotBlank()) {
            Text(name, color = Color(0xFF9FC0CB), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
        }
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Color(0x33FFFFFF)),
        )
    }
}
