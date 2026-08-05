package com.astrion.remote.ha

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject

/**
 * @Immutable lets Compose skip re-rendering any card whose entity hasn't changed,
 * instead of redrawing every card on every state_changed event.
 */
@Immutable
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    val lastChanged: String? = null,
    val lastUpdated: String? = null
)

sealed class ConnectionStatus {
    data object Connecting : ConnectionStatus()
    data object AuthOk : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
    data object Disconnected : ConnectionStatus()
}

data class ForecastDay(
    val datetime: String,
    val temperature: Double?,
    val templow: Double?,
    val condition: String?
)
