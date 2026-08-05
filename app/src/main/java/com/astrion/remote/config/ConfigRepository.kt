package com.astrion.remote.config

import android.content.Context
import android.os.Environment
import android.util.Log
import com.astrion.remote.R
import kotlinx.serialization.json.Json
import java.io.File

sealed class ConfigResult {
    data class Success(val config: DashboardConfig) : ConfigResult()
    data class Failure(val error: String, val fallback: DashboardConfig) : ConfigResult()
}

class ConfigRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val configDir = File(Environment.getExternalStorageDirectory(), "astrion")
    private val configFile = File(configDir, "dashboard.json")

    private var lastGood: DashboardConfig? = null

    fun load(): ConfigResult {
        ensureSeeded()
        val text = try {
            configFile.readText()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read ${configFile.path}: ${e.message}")
            return ConfigResult.Failure("Could not read dashboard.json: ${e.message}", lastGoodOrDefault())
        }
        return try {
            val config = json.decodeFromString(DashboardConfig.serializer(), text)
            lastGood = config
            ConfigResult.Success(config)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid dashboard.json: ${e.message}")
            ConfigResult.Failure("dashboard.json is invalid: ${e.message}", lastGoodOrDefault())
        }
    }

    private fun lastGoodOrDefault(): DashboardConfig = lastGood ?: defaultConfig()

    private fun defaultConfig(): DashboardConfig {
        val text = readDefaultAsset()
        val config = json.decodeFromString(DashboardConfig.serializer(), text)
        lastGood = config
        return config
    }

    private fun readDefaultAsset(): String =
        context.resources.openRawResource(R.raw.default_dashboard)
            .bufferedReader()
            .use { it.readText() }

    private fun ensureSeeded() {
        if (!configFile.exists()) {
            try {
                configDir.mkdirs()
                configFile.writeText(readDefaultAsset())
                Log.i(TAG, "Seeded default dashboard.json at ${configFile.path}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not seed dashboard.json: ${e.message}")
            }
        }
        // Baked-in copies of the image assets make the APK a complete restore unit:
        // after a factory reset (or wiped /sdcard) the app rebuilds its whole world
        // on first launch. Existing files are never overwritten, so user-customized
        // images survive app updates.
        seedAsset(R.raw.floorplan, File(configDir, "floorplan.png"))
        seedAsset(R.raw.icon_plex, File(File(configDir, "icons"), "plex.png"))
    }

    private fun seedAsset(resId: Int, target: File) {
        if (target.exists()) return
        try {
            target.parentFile?.mkdirs()
            context.resources.openRawResource(resId).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Seeded ${target.path}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not seed ${target.name}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AstrionConfig"
    }
}
