package com.astrion.remote.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class HaConfig(
    val host: String,
    val token: String
)

@Serializable
data class CardConfig(
    val type: String,
    val variant: String? = null,
    val options: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class PageConfig(
    val name: String,
    val showTitle: Boolean = true,
    /** false = fixed page (content must fit; no vertical scrolling) */
    val scroll: Boolean = true,
    val cards: List<CardConfig> = emptyList()
)

@Serializable
data class HotkeyConfig(
    val key: String,
    val page: String? = null,
    val service: String? = null,
    val entityId: String? = null,
    val data: JsonObject? = null,
    /** Device-local action instead of an HA call. Supported: "sleep" (screen off). */
    val device: String? = null
)

@Serializable
data class AssistConfig(
    /** Assist pipeline id/name; null = HA's preferred (default) pipeline. */
    val pipeline: String? = null
)

@Serializable
data class DashboardConfig(
    val ha: HaConfig,
    val assist: AssistConfig? = null,
    val startPage: Int = 0,
    val pages: List<PageConfig> = emptyList(),
    val hotkeys: List<HotkeyConfig> = emptyList(),
    val longHotkeys: List<HotkeyConfig> = emptyList(),
    val keymap: Map<String, String> = emptyMap()
)
