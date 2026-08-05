package com.astrion.remote.ui.cards

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.astrion.remote.config.CardConfig
import com.astrion.remote.ha.HaClient
import com.astrion.remote.ha.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private data class GridButton(
    val label: String,
    val service: String?,
    val entityId: String?,
    val data: JsonObject?,
    val iconPath: String?,
    val iconName: String?,
    val appPackage: String?
)

/** Built-in Material icons addressable from dashboard.json via "icon_name". */
private fun materialIcon(name: String?) = when (name) {
    "spa" -> Icons.Filled.Spa
    "tv" -> Icons.Filled.Tv
    "movie" -> Icons.Filled.Movie
    "apps" -> Icons.Filled.Apps
    "home" -> Icons.Filled.Home
    "play" -> Icons.Filled.PlayArrow
    "lightbulb" -> Icons.Filled.Lightbulb
    else -> null
}

/**
 * Row of launcher-style buttons, each firing any HA service. Icons are optional PNGs from
 * /sdcard/astrion/icons/ (config "icon"); falls back to the first letter of the label.
 */
@Composable
fun ButtonGridCard(card: CardConfig, haClient: HaClient) {
    val buttons = remember(card) {
        (card.options["buttons"] as? JsonArray)?.mapNotNull { el ->
            val o = el.jsonObject
            GridButton(
                label = o.str("name") ?: o.str("service") ?: o.str("app") ?: return@mapNotNull null,
                service = o.str("service"),
                entityId = o.str("entity_id"),
                data = o["data"] as? JsonObject,
                iconPath = o.str("icon"),
                iconName = o.str("icon_name"),
                appPackage = o.str("app")
            )
        } ?: emptyList()
    }
    if (buttons.isEmpty()) {
        UnknownCard(card, null)
        return
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        buttons.forEach { button ->
            // weight(1f) => the row divides its width evenly, so buttons grow to fill
            // whatever space is left as buttons are added/removed in config.
            Box(Modifier.weight(1f)) { LauncherButton(button, haClient) }
        }
    }
}

@Composable
private fun LauncherButton(button: GridButton, haClient: HaClient) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val icon = remember(button.iconPath) {
        button.iconPath?.let { path ->
            try {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable {
                if (button.appPackage != null) {
                    // "app" buttons launch another Android app (e.g. the stock HaRemote).
                    // User-initiated: grant the stock app a foreground window so the
                    // rescue service doesn't bounce it back.
                    com.astrion.remote.input.StockGate.allow(context, 10 * 60_000)
                    val intent = context.packageManager.getLaunchIntentForPackage(button.appPackage)
                        ?: android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                            setPackage(button.appPackage)
                            addCategory(android.content.Intent.CATEGORY_HOME)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        android.util.Log.w("AstrionButtonGrid", "Cannot launch ${button.appPackage}: ${e.message}")
                    }
                    return@clickable
                }
                val service = button.service ?: return@clickable
                val parts = service.split(".", limit = 2)
                if (parts.size == 2) {
                    haClient.callService(parts[0], parts[1], button.entityId, button.data)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val vector = materialIcon(button.iconName)
        when {
            icon != null -> Image(
                icon,
                contentDescription = button.label,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit
            )
            vector != null -> Icon(
                vector,
                contentDescription = button.label,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            else -> Text(
                button.label.take(1).uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        button.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    }
}
