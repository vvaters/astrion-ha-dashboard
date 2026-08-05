package com.astrion.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrion.remote.config.ConfigRepository
import com.astrion.remote.config.ConfigResult
import com.astrion.remote.config.DashboardConfig
import com.astrion.remote.config.HotkeyConfig
import com.astrion.remote.ha.ConnectionStatus
import com.astrion.remote.ha.HaClient
import com.astrion.remote.input.HardwareKeyRouter
import com.astrion.remote.ui.DashboardPager
import com.astrion.remote.voice.AssistManager
import com.astrion.remote.voice.VoiceOverlay
import com.astrion.remote.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private data class Session(val config: DashboardConfig, val client: HaClient)

/** Navy/cyan palette modeled on the HA100 community look. */
private val AstrionColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF4FC3F7),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF06222E),
    background = androidx.compose.ui.graphics.Color(0xFF0B141E),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE4F1F8),
    surface = androidx.compose.ui.graphics.Color(0xFF0B141E),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE4F1F8),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1B3448),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF8FB4C6),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF10202E),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF142838),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF16222E),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF1B3448),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF204058),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF1E3A50),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFB8DCEC),
    outline = androidx.compose.ui.graphics.Color(0xFF3A5A70),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF24404F)
)

class MainActivity : ComponentActivity() {

    private val ioScope = CoroutineScope(SupervisorJob())
    private lateinit var configRepo: ConfigRepository
    private lateinit var assist: AssistManager

    private var session by mutableStateOf<Session?>(null)
    private var errorBanner by mutableStateOf<String?>(null)
    private var batteryReporter: com.astrion.remote.ha.BatteryReporter? = null

    private var keyRouter: HardwareKeyRouter? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startClient()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configRepo = ConfigRepository(applicationContext)
        assist = AssistManager(applicationContext, ioScope)
        // Pickup-to-wake: arms motion/proximity sensors whenever the screen sleeps.
        startForegroundService(
            android.content.Intent(this, com.astrion.remote.input.PickupWakeService::class.java)
        )
        ensureStockIrBridge()
        ensureAccessibilityBound()
        ensurePermissionsThenStart()

        setContent {
            MaterialTheme(colorScheme = AstrionColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
        // Physical d-pad presses flip Android into keyboard-navigation mode, which makes
        // Compose focus the first card and scroll the pager to it — even though we consume
        // the key. All key handling here is custom, so block view focus entirely.
        (window.decorView as? android.view.ViewGroup)?.descendantFocusability =
            android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    @Composable
    private fun AppRoot() {
        val s = session
        val voiceState by assist.state.collectAsStateWithLifecycle()
        if (voiceState !is VoiceState.Idle) {
            VoiceOverlay(state = voiceState, onDismiss = { assist.dismiss() })
        }
        Column(Modifier.fillMaxSize()) {
            errorBanner?.let {
                Text(
                    "Config warning: $it",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
                )
            }
            if (s == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Starting…")
                }
            } else {
                val status by s.client.status.collectAsStateWithLifecycle()
                // Only surface connection state when something is wrong.
                if (status !is ConnectionStatus.AuthOk) {
                    Text(
                        text = statusLabel(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                val entities by s.client.entities.collectAsStateWithLifecycle()

                val pageCount = s.config.pages.size
                if (pageCount == 0) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No pages configured in dashboard.json")
                    }
                } else {
                    val startIndex = s.config.startPage.coerceIn(0, pageCount - 1)
                    val pagerState = rememberPagerState(initialPage = startIndex) { pageCount }
                    // Page-scroll animations need Compose's frame clock, so navigation must run
                    // on the composition-aware scope — never ioScope (crashes with
                    // "MonotonicFrameClock is not available").
                    val uiScope = androidx.compose.runtime.rememberCoroutineScope()

                    LaunchedEffect(s.config, s.client, pagerState) {
                        keyRouter = HardwareKeyRouter(
                            scope = ioScope,
                            config = s.config,
                            onNavigate = { pageName ->
                                val idx = s.config.pages.indexOfFirst { it.name == pageName }
                                if (idx >= 0) uiScope.launch { pagerState.animateScrollToPage(idx) }
                            },
                            onService = { hotkey -> fireHotkeyService(hotkey, s.client) }
                        )
                    }

                    DashboardPager(config = s.config, entities = entities, haClient = s.client, pagerState = pagerState)
                }
            }
        }
    }

    private fun fireHotkeyService(hotkey: HotkeyConfig, client: HaClient) {
        if (hotkey.device == "sleep") {
            sleepScreen()
            return
        }
        val service = hotkey.service ?: return
        val parts = service.split(".", limit = 2)
        if (parts.size != 2) return
        client.callService(parts[0], parts[1], hotkey.entityId, hotkey.data)
    }

    private fun sleepScreen() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val admin = android.content.ComponentName(this, com.astrion.remote.input.SleepAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        } else {
            android.util.Log.w("AstrionKey", "Sleep requested but device admin not active — run: adb shell dpm set-active-admin com.astrion.remote/.input.SleepAdminReceiver")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Tap-to-talk: this remote's mic button emits an instant press+release, so a tap
        // starts listening (auto-stops on silence) and a second tap stops it manually.
        if (event.keyCode == voiceKeyCode()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (assist.state.value is VoiceState.Listening) {
                    assist.stopListening()
                } else {
                    assist.startListening(session?.config?.assist?.pipeline)
                }
            }
            return true
        }
        val router = keyRouter
        val consumed = when (event.action) {
            KeyEvent.ACTION_DOWN -> router?.onKeyDown(event) ?: false
            KeyEvent.ACTION_UP -> router?.onKeyUp(event) ?: false
            else -> false
        }
        return if (consumed) true else super.dispatchKeyEvent(event)
    }

    private fun statusLabel(status: ConnectionStatus): String = when (status) {
        is ConnectionStatus.Connecting -> "Connecting…"
        is ConnectionStatus.AuthOk -> "Connected"
        is ConnectionStatus.Error -> "Error: ${status.message}"
        is ConnectionStatus.Disconnected -> "Disconnected — reconnecting…"
    }

    override fun onResume() {
        super.onResume()
        // Skip the very first onResume: it fires before permissions are granted and
        // startClient() (triggered by the permission callback) hasn't run yet.
        if (session != null) {
            reloadConfig()
        }
    }

    /**
     * The stock HaRemote app is the bridge between HA and the IR blaster hardware: HA's
     * remote.sanytron_* entity only works while it runs. As the firmware launcher it
     * auto-started at boot; since our app took over as home, nothing starts it anymore
     * and IR silently dies after a reboot. So once per boot, launch it in the background
     * and bring ourselves back to the front.
     */
    private fun ensureStockIrBridge() {
        val prefs = getSharedPreferences("astrion", MODE_PRIVATE)
        val bootCount = try {
            android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT)
        } catch (e: Exception) {
            -1
        }
        if (bootCount != -1 && prefs.getInt("ir_bridge_kicked_boot", -2) == bootCount) return
        try {
            val intent = packageManager.getLaunchIntentForPackage(STOCK_APP)
                ?: android.content.Intent().setClassName(STOCK_APP, "$STOCK_APP.ui.index.HomeActivity")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            com.astrion.remote.input.StockGate.allow(this, 9_000)
            startActivity(intent)
            prefs.edit().putInt("ir_bridge_kicked_boot", bootCount).apply()
            android.util.Log.i("AstrionBridge", "Kicked stock HaRemote for IR bridge (boot $bootCount)")
            // Reclaim the foreground with several attempts — the stock app re-fronts
            // itself during its boot init, so a single attempt can lose the race.
            val handler = android.os.Handler(mainLooper)
            for (delayMs in longArrayOf(4_000, 10_000, 16_000)) {
                handler.postDelayed({
                    startActivity(
                        android.content.Intent(this, MainActivity::class.java)
                            .addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            )
                    )
                }, delayMs)
            }
        } catch (e: Exception) {
            android.util.Log.w("AstrionBridge", "Could not start stock HaRemote: ${e.message}")
        }
    }

    /**
     * This firmware fails to rebind our accessibility service after app updates and some
     * reboots even though the secure setting is intact (same quirk the community hit with
     * KeyMapper). With WRITE_SECURE_SETTINGS granted once over adb, the app can heal the
     * binding itself by toggling the setting off and on.
     */
    private fun ensureAccessibilityBound() {
        android.os.Handler(mainLooper).postDelayed({
            com.astrion.remote.input.KeyRescueService.ensureBound(this)
        }, 5_000)
    }

    private fun ensurePermissionsThenStart() {
        val needed = listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) {
            startClient()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startClient() {
        if (session != null) return
        val config = loadConfig()
        val client = HaClient(config.ha.host, config.ha.token, ioScope)
        client.connect()
        assist.client = client
        session = Session(config, client)
        startBatteryReporter(config)
    }

    private fun startBatteryReporter(config: DashboardConfig) {
        batteryReporter?.stop()
        batteryReporter = com.astrion.remote.ha.BatteryReporter(
            applicationContext, config.ha.host, config.ha.token, ioScope
        ).also { it.start() }
    }

    private fun reloadConfig() {
        val newConfig = loadConfig()
        val current = session
        session = if (current == null || newConfig.ha != current.config.ha) {
            current?.client?.close()
            val client = HaClient(newConfig.ha.host, newConfig.ha.token, ioScope)
            client.connect()
            assist.client = client
            startBatteryReporter(newConfig)
            Session(newConfig, client)
        } else {
            current.copy(config = newConfig)
        }
    }

    private companion object {
        const val STOCK_APP = "com.aiks.HaRemote"
    }

    /** Keycode bound to VOICE in the config keymap (this remote: 133 / F3). */
    private fun voiceKeyCode(): Int =
        session?.config?.keymap?.entries?.firstOrNull { it.value == "VOICE" }?.key?.toIntOrNull() ?: 133

    private fun loadConfig(): DashboardConfig = when (val result = configRepo.load()) {
        is ConfigResult.Success -> {
            errorBanner = null
            result.config
        }
        is ConfigResult.Failure -> {
            errorBanner = result.error
            result.fallback
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.client?.close()
    }
}
