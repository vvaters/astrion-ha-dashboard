package com.astrion.remote.ui.cards

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.astrion.remote.config.CardConfig
import com.astrion.remote.ha.EntityState
import com.astrion.remote.ha.HaClient
import com.astrion.remote.ha.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Plex poster rows (On Deck + Recently Added), adapted from baes-cloud/astrion-dashboard's
 * PlexCard for this app's card registry. Talks straight to the Plex server's HTTP API;
 * posters come pre-scaled from Plex's photo transcoder so the decode stays tiny.
 * Tapping an item plays it via the HA Plex client entity when it's online, otherwise
 * falls back to opening the Plex app on the TV (select_source).
 */
private data class PlexItem(val key: String, val ratingKey: String?, val type: String?, val title: String, val subtitle: String, val thumb: String?)
private data class PlexRow(val title: String, val items: List<PlexItem>)

private val plexHttp = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
private val plexJson = Json { ignoreUnknownKeys = true }
private val posterCache = LruCache<String, ImageBitmap>(40)
private const val PLAY_COOLDOWN_MS = 8_000L
private const val POSTERS_PER_ROW = 3

/** Rows survive page swipes; refetch only when stale so re-entering Media is instant. */
private object PlexRowCache {
    @Volatile var rows: List<PlexRow>? = null
    @Volatile var machineId: String? = null
    @Volatile var fetchedAt = 0L
    const val TTL_MS = 5 * 60_000L
}

@Composable
fun PlexCard(card: CardConfig, entities: Map<String, EntityState>, haClient: HaClient) {
    val host = card.options.str("host")?.trimEnd('/')
    val token = card.options.str("token")
    val playEntity = card.options.str("play_entity")
    val mediaEntity = card.options.str("media_entity")
    val source = card.options.str("source") ?: "Plex"

    if (host.isNullOrBlank() || token.isNullOrBlank()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Plex", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Add your X-Plex-Token to this card in dashboard.json to see posters",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    var rows by remember(host) { mutableStateOf(PlexRowCache.rows) }
    var machineId by remember(host) { mutableStateOf(PlexRowCache.machineId) }
    var error by remember(host) { mutableStateOf<String?>(null) }
    var pendingMsg by remember { mutableStateOf<String?>(null) }
    var lastPlayAt by remember { mutableStateOf(0L) }
    var pendingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val liveEntities by rememberUpdatedState(entities)

    LaunchedEffect(host, token) {
        val fresh = System.currentTimeMillis() - PlexRowCache.fetchedAt < PlexRowCache.TTL_MS
        if (fresh && PlexRowCache.rows != null) return@LaunchedEffect
        try {
            machineId = plexGet(plexUrl(host, token, "/identity"))
                ?.mc()?.strOf("machineIdentifier")
            val out = mutableListOf<PlexRow>()
            fetchPlexItems(host, token, "/library/onDeck")?.takeIf { it.isNotEmpty() }
                ?.let { out += PlexRow("On Deck", it) }
            fetchPlexItems(host, token, "/library/recentlyAdded")?.takeIf { it.isNotEmpty() }
                ?.let { out += PlexRow("Recently Added", it) }
            rows = out
            PlexRowCache.rows = out
            PlexRowCache.machineId = machineId
            PlexRowCache.fetchedAt = System.currentTimeMillis()
            if (out.isEmpty()) error = "No items returned from Plex"
        } catch (ex: Exception) {
            error = ex.message ?: "Plex error"
            if (rows == null) rows = emptyList()
        }
    }

    fun clientOnline() = playEntity != null &&
        liveEntities[playEntity]?.state !in listOf(null, "unavailable", "unknown")

    fun sendPlay(ratingKey: String) {
        // HA's Plex integration resolves plex_key server-side and tells the client
        // to play it — reliable, unlike app deep links.
        haClient.callService(
            "media_player", "play_media", playEntity,
            buildJsonObject {
                put("media_content_type", "video")
                put("media_content_id", "plex://{\"plex_key\": $ratingKey}")
            }
        )
    }

    fun play(item: PlexItem) {
        if (item.ratingKey == null) return
        // The LG Plex client wedges its play queue if commands arrive while it's still
        // loading the previous one ("error loading items into play queue"). Enforce a
        // cooldown and never let two pending plays stack.
        val now = System.currentTimeMillis()
        if (now - lastPlayAt < PLAY_COOLDOWN_MS) {
            pendingMsg = "Plex is still loading the last pick — try again in a few seconds"
            scope.launch {
                delay(3_000)
                if (pendingMsg?.startsWith("Plex is still") == true) pendingMsg = null
            }
            return
        }
        pendingJob?.cancel()
        pendingMsg = "▶ ${item.title}…"
        pendingJob = scope.launch {
            // Season/show tiles are containers — resolve to the next unwatched episode.
            val playKey = resolvePlayableKey(host, token, item)
            if (playKey == null) {
                pendingMsg = "Couldn't find an episode to play"
                delay(4_000)
                pendingMsg = null
                return@launch
            }
            if (clientOnline()) {
                lastPlayAt = System.currentTimeMillis()
                sendPlay(playKey)
                delay(4_000)
                if (pendingMsg?.startsWith("▶") == true) pendingMsg = null
                return@launch
            }
            if (mediaEntity == null) return@launch
            // Cold start: open the Plex app on the TV, then wait for it to register as
            // an HA client (nudging HA to rescan) and fire the play automatically.
            haClient.callService(
                "media_player", "select_source", mediaEntity,
                buildJsonObject { put("source", source) }
            )
            pendingMsg = "Opening Plex on the TV…"
            haClient.callService("plex", "scan_for_clients")
            // The LG registers as an HA client lazily (sometimes minutes after the app
            // opens), so don't wait for it: fire the play command periodically and stop
            // once the TV reports it's actually playing. Early sends that the client
            // isn't ready for fail harmlessly inside HA.
            val deadline = System.currentTimeMillis() + 60_000
            var lastSend = 0L
            while (System.currentTimeMillis() < deadline) {
                delay(2_000)
                val st = liveEntities[playEntity]?.state
                if (st == "playing" || st == "buffering") {
                    lastPlayAt = System.currentTimeMillis()
                    pendingMsg = "▶ ${item.title}…"
                    delay(4_000)
                    if (pendingMsg?.startsWith("▶") == true) pendingMsg = null
                    return@launch
                }
                val now2 = System.currentTimeMillis()
                if (now2 - lastSend > 7_000) {
                    lastSend = now2
                    sendPlay(playKey)
                }
                haClient.callService("plex", "scan_for_clients")
            }
            pendingMsg = "Plex didn't start — tap the poster again"
            delay(5_000)
            pendingMsg = null
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                "Plex",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            pendingMsg?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            when {
                rows == null -> Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                error != null -> Text(
                    error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                else -> rows!!.forEach { row ->
                    Text(
                        row.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    // Fixed Row (not LazyRow): a scrollable row would swallow the pager's
                    // horizontal swipes, making it hard to leave the Media page.
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
                    ) {
                        row.items.take(POSTERS_PER_ROW).forEach { item ->
                            Box(Modifier.weight(1f)) {
                                PosterTile(host, token, item) { play(item) }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun PosterTile(host: String, token: String, item: PlexItem, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        val posterUrl = item.thumb?.let {
            "$host/photo/:/transcode?width=140&height=210&minSize=1&upscale=1" +
                "&url=${URLEncoder.encode(it, "UTF-8")}&X-Plex-Token=$token"
        }
        var bmp by remember(posterUrl) { mutableStateOf(posterUrl?.let { posterCache.get(it) }) }
        LaunchedEffect(posterUrl) {
            if (bmp == null && posterUrl != null) {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        plexHttp.newCall(Request.Builder().url(posterUrl).build()).execute().use { r ->
                            r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    }.getOrNull()?.asImageBitmap()
                }
                if (loaded != null) {
                    posterCache.put(posterUrl, loaded)
                    bmp = loaded
                }
            }
        }
        val posterMod = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(10.dp))
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = item.title, modifier = posterMod, contentScale = ContentScale.Crop)
        } else {
            Box(posterMod.background(MaterialTheme.colorScheme.surfaceContainerHigh))
        }
        Spacer(Modifier.height(4.dp))
        Text(item.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            item.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---- Plex HTTP helpers ----

private fun plexUrl(host: String, token: String, path: String): String =
    host + path + (if ('?' in path) "&" else "?") + "X-Plex-Token=$token"

private suspend fun plexGet(u: String): JsonObject? = withContext(Dispatchers.IO) {
    runCatching {
        plexHttp.newCall(
            Request.Builder().url(u).header("Accept", "application/json").build()
        ).execute().use { r ->
            if (!r.isSuccessful) null
            else plexJson.parseToJsonElement(r.body?.string() ?: return@use null) as? JsonObject
        }
    }.getOrNull()
}

private suspend fun fetchPlexItems(host: String, token: String, path: String): List<PlexItem>? {
    val container = plexGet(plexUrl(host, token, path) + "&X-Plex-Container-Size=12")?.mc() ?: return null
    val meta = container["Metadata"] as? JsonArray ?: return emptyList()
    return meta.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val key = o.strOf("key") ?: return@mapNotNull null
        when (o.strOf("type")) {
            "episode" -> PlexItem(
                key = key,
                ratingKey = o.strOf("ratingKey"),
                type = "episode",
                title = o.strOf("grandparentTitle") ?: o.strOf("title") ?: "?",
                subtitle = "S${o.intOf("parentIndex") ?: "?"}E${o.intOf("index") ?: "?"} · ${o.strOf("title") ?: ""}",
                thumb = o.strOf("grandparentThumb") ?: o.strOf("thumb")
            )
            "season" -> PlexItem(
                key = key,
                ratingKey = o.strOf("ratingKey"),
                type = "season",
                title = o.strOf("parentTitle") ?: o.strOf("title") ?: "?",
                subtitle = o.strOf("title") ?: "Season",
                thumb = o.strOf("thumb") ?: o.strOf("parentThumb")
            )
            else -> PlexItem(
                key = key,
                ratingKey = o.strOf("ratingKey"),
                type = o.strOf("type"),
                title = o.strOf("title") ?: "?",
                subtitle = o.intOf("year")?.toString() ?: (o.strOf("type") ?: ""),
                thumb = o.strOf("thumb")
            )
        }
    }
}

/** Seasons/shows are containers; resolve to the next unwatched episode's ratingKey. */
private suspend fun resolvePlayableKey(host: String, token: String, item: PlexItem): String? {
    val rk = item.ratingKey ?: return null
    val path = when (item.type) {
        "season" -> "/library/metadata/$rk/children"
        "show" -> "/library/metadata/$rk/allLeaves"
        else -> return rk
    }
    val meta = plexGet(plexUrl(host, token, path))?.mc()?.get("Metadata") as? JsonArray ?: return null
    val episodes = meta.mapNotNull { it as? JsonObject }
    val next = episodes.firstOrNull { (it.intOf("viewCount") ?: 0) == 0 } ?: episodes.firstOrNull()
    return next?.strOf("ratingKey")
}

private fun JsonObject.mc(): JsonObject? = this["MediaContainer"] as? JsonObject
private fun JsonObject.strOf(k: String): String? = (this[k] as? JsonPrimitive)?.content
private fun JsonObject.intOf(k: String): Int? = (this[k] as? JsonPrimitive)?.content?.toIntOrNull()
