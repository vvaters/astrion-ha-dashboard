package com.astrion.remote.ui.cards

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.astrion.remote.config.CardConfig
import com.astrion.remote.ha.EntityState
import com.astrion.remote.ha.HaClient
import com.astrion.remote.ha.double
import com.astrion.remote.ha.str
import com.astrion.remote.ha.stringList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

private data class FloorplanElement(
    val entityIds: List<String>,
    val xPct: Float,
    val yPct: Float,
    val iconName: String?
)

/** Dot glyphs, selectable per element via "icon_name"; defaults to a lightbulb. */
private fun dotIcon(name: String?) = when (name) {
    "coffee" -> Icons.Filled.Coffee
    "tv" -> Icons.Filled.Tv
    "power" -> Icons.Filled.PowerSettingsNew
    "lights" -> LightbulbGroup
    else -> Icons.Filled.Lightbulb
}

/** mdi:lightbulb-group — the standard HA group-light icon; Material has no equivalent. */
private val LightbulbGroup: ImageVector by lazy {
    ImageVector.Builder(
        name = "LightbulbGroup",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).addPath(
        pathData = addPathNodes(
            "M15 14V16A1 1 0 0 1 14 17H10A1 1 0 0 1 9 16V14A5 5 0 1 1 15 14M14 18H10V19A1 1 0 " +
                "0 0 11 20H13A1 1 0 0 0 14 19M7 19V18H5V19A1 1 0 0 0 6 20H7.17A2.93 2.93 0 0 1 " +
                "7 19M5 10A6.79 6.79 0 0 1 5.68 7A4 4 0 0 0 4 14.45V16A1 1 0 0 0 5 17H7V14.88A6.92 " +
                "6.92 0 0 1 5 10M17 18V19A2.93 2.93 0 0 1 16.83 20H18A1 1 0 0 0 19 19V18M18.32 " +
                "7A6.79 6.79 0 0 1 19 10A6.92 6.92 0 0 1 17 14.88V17H19A1 1 0 0 0 20 16V14.45A4 " +
                "4 0 0 0 18.32 7Z"
        ),
        fill = SolidColor(Color.White)
    ).build()
}

/** Decode the floorplan once per process — re-decoding on every swipe back caused jank. */
private object FloorplanCache {
    @Volatile
    var entry: Pair<String, ImageBitmap>? = null
}

@Composable
fun PictureElementsCard(card: CardConfig, entities: Map<String, EntityState>, haClient: HaClient) {
    val imagePath = card.options.str("image") ?: "/sdcard/astrion/floorplan.png"
    val allOff = card.options["all_off"]?.toString() == "true"
    val elements = remember(card) {
        (card.options["elements"] as? JsonArray)?.mapNotNull { el ->
            val o = el.jsonObject
            // Either a single "entity_id" or an "entities" list — one dot, N targets.
            val ids = o.stringList("entities").ifEmpty { listOfNotNull(o.str("entity_id")) }
            if (ids.isEmpty()) return@mapNotNull null
            FloorplanElement(
                entityIds = ids,
                xPct = (o.double("x") ?: 50.0).toFloat() / 100f,
                yPct = (o.double("y") ?: 50.0).toFloat() / 100f,
                iconName = o.str("icon_name")
            )
        } ?: emptyList()
    }

    var bitmap by remember(imagePath) {
        mutableStateOf(FloorplanCache.entry?.takeIf { it.first == imagePath }?.second)
    }
    var loadFailed by remember(imagePath) { mutableStateOf(false) }
    LaunchedEffect(imagePath) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            try {
                BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
        if (loaded != null) FloorplanCache.entry = imagePath to loaded
        bitmap = loaded
        loadFailed = loaded == null
    }

    val bmp = bitmap
    if (bmp == null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Floorplan", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (loadFailed) "No image at $imagePath — push one with adb" else "Loading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val aspect = bmp.width.toFloat() / bmp.height.toFloat()
    val fullBleed = card.options["full_bleed"]?.toString() == "true"
    Card(
        Modifier.fillMaxWidth(),
        shape = if (fullBleed) RoundedCornerShape(0.dp) else CardDefaults.shape
    ) {
        Box {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val widthDp = maxWidth
                val heightDp = widthDp / aspect
                Box(Modifier.size(widthDp, heightDp)) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                    elements.forEach { element ->
                        val isOn = element.entityIds.any { entities[it]?.state == "on" }
                        val dotSize = if (element.iconName == "lights") GROUP_ICON_SIZE else ICON_SIZE
                        Box(
                            Modifier
                                .offset(
                                    x = widthDp * element.xPct - dotSize / 2,
                                    y = heightDp * element.yPct - dotSize / 2
                                )
                                .size(dotSize)
                                .clip(CircleShape)
                                .background(
                                    if (isOn) Brush.radialGradient(
                                        listOf(Color(0xCCFFD34D), Color(0x66FFB300))
                                    ) else Brush.radialGradient(
                                        listOf(Color(0x99FFFFFF), Color(0x40C9D6DE))
                                    )
                                )
                                .border(
                                    width = 1.5.dp,
                                    color = if (isOn) Color(0xCCFFE082) else Color(0x80FFFFFF),
                                    shape = CircleShape
                                )
                                .clickable {
                                    // homeassistant.toggle works for lights and switches alike.
                                    // If any target is on, turn all off; otherwise turn all on —
                                    // keeps multi-entity dots in sync instead of flip-flopping.
                                    val service = if (isOn) "turn_off" else "turn_on"
                                    element.entityIds.forEach {
                                        haClient.callService("homeassistant", service, it)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val glyphTint = if (isOn) Color(0xFF5A3D00) else Color(0xCC33454F)
                            Icon(
                                imageVector = dotIcon(element.iconName),
                                contentDescription = null,
                                tint = glyphTint,
                                modifier = Modifier.size(dotSize * 0.57f)
                            )
                        }
                    }
                    if (allOff && elements.isNotEmpty()) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xB3000000))
                                .clickable {
                                    elements.flatMap { it.entityIds }.distinct().forEach {
                                        haClient.callService("homeassistant", "turn_off", it)
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("All off", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

private val ICON_SIZE = 30.dp
// Group dots run ~10% larger so multi-light targets stand out.
private val GROUP_ICON_SIZE = 33.dp
