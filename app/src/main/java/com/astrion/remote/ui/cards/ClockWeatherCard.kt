package com.astrion.remote.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import com.astrion.remote.config.CardConfig
import com.astrion.remote.ha.EntityState
import com.astrion.remote.ha.ForecastDay
import com.astrion.remote.ha.HaClient
import com.astrion.remote.ha.double
import com.astrion.remote.ha.str
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ClockWeatherCard(card: CardConfig, entity: EntityState?, haClient: HaClient) {
    val entityId = card.options.str("entity_id")
    if (entityId == null) {
        UnknownCard(card, entity)
        return
    }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val timeText = remember(now / 60_000) { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(now)) }
    val dateText = remember(now / 60_000) { SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(now)) }

    var forecast by remember(entityId) { mutableStateOf<List<ForecastDay>>(emptyList()) }
    LaunchedEffect(entityId) {
        forecast = try {
            haClient.getForecast(entityId)
        } catch (e: Exception) {
            emptyList()
        }
    }

    val attrs = entity?.attributes
    val condition = entity?.state?.replace("-", " ")?.replaceFirstChar { it.uppercase() } ?: "—"
    val temp = attrs?.double("temperature")

    if (card.variant == "compact") {
        // HA100-style header: big clock + date on the left, 3-day forecast column on the right.
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(timeText, style = MaterialTheme.typography.displaySmall)
                    Text(dateText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        (temp?.let { "${it.roundToInt()}° " } ?: "") + condition,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    forecast.take(3).forEach { day ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatDayLabelPublic(day.datetime),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.width(34.dp)
                            )
                            Text(
                                conditionEmoji(day.condition),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(24.dp)
                            )
                            Text(
                                (day.templow?.let { "${it.roundToInt()}°" } ?: "--") + " | " +
                                    (day.temperature?.let { "${it.roundToInt()}°" } ?: "--"),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
        return
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(timeText, style = MaterialTheme.typography.displaySmall)
            Text(dateText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(temp?.let { "${it.roundToInt()}°" } ?: "--", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(10.dp))
                Text(condition, style = MaterialTheme.typography.bodyMedium)
            }
            if (forecast.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                val highs = forecast.mapNotNull { it.temperature }
                val lows = forecast.mapNotNull { it.templow }
                val globalMax = (highs + lows).maxOrNull() ?: 1.0
                val globalMin = (highs + lows).minOrNull() ?: 0.0
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    forecast.take(5).forEach { day -> ForecastRow(day, globalMin, globalMax) }
                }
            }
        }
    }
}

private fun conditionEmoji(condition: String?): String = when (condition) {
    "sunny", "clear" -> "☀️"
    "clear-night" -> "🌙"
    "partlycloudy" -> "⛅"
    "cloudy" -> "☁️"
    "rainy", "pouring" -> "🌧️"
    "lightning", "lightning-rainy" -> "⛈️"
    "snowy", "snowy-rainy" -> "🌨️"
    "fog" -> "🌫️"
    "windy", "windy-variant" -> "💨"
    else -> "🌡️"
}

internal fun formatDayLabelPublic(datetime: String): String = formatDayLabel(datetime)

@Composable
private fun ForecastRow(day: ForecastDay, globalMin: Double, globalMax: Double) {
    val dayLabel = remember(day.datetime) { formatDayLabel(day.datetime) }
    val range = (globalMax - globalMin).coerceAtLeast(1.0)
    val endFrac = (((day.temperature ?: globalMax) - globalMin) / range).toFloat().coerceIn(0f, 1f)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(dayLabel, modifier = Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall)
        Text(
            day.templow?.let { "${it.roundToInt()}°" } ?: "--",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(30.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(endFrac.coerceAtLeast(0.04f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF42A5F5), Color(0xFFFFA726))))
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            day.temperature?.let { "${it.roundToInt()}°" } ?: "--",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(30.dp)
        )
    }
}

private fun formatDayLabel(datetime: String): String = try {
    val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).parse(datetime)
        ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(datetime)
    parsed?.let { SimpleDateFormat("EEE", Locale.getDefault()).format(it) } ?: datetime.take(3)
} catch (e: Exception) {
    datetime.take(3)
}
