package com.astrion.remote.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

fun JsonObject.rgbColor(key: String): List<Int>? =
    (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }?.takeIf { it.size == 3 }
