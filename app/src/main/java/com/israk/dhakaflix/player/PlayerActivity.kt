package com.israk.dhakaflix.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.MainActivity
import com.israk.dhakaflix.data.PlayItem
import com.israk.dhakaflix.ui.edgeFor
import com.israk.dhakaflix.ui.theme.DhakaFlixTheme
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.ScaledDensity
import com.israk.dhakaflix.ui.theme.stringsFor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class PlayerActivity : ComponentActivity() {

    private var ctrl: PlayerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.keepScreenOn = true
        hideSystemBars()

        val app = application as DhakaFlixApp
        val list = runCatching {
            Json.decodeFromString(ListSerializer(PlayItem.serializer()), intent.getStringExtra(EXTRA_LIST)!!)
        }.getOrNull()
        if (list.isNullOrEmpty()) { finish(); return }
        val index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, list.lastIndex)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ASK
        val c = PlayerController(this, app, list, index, mode)
        ctrl = c
        val tv = MainActivity.isTvDevice(this)

        setContent {
            val appIcon by app.settings.appIcon.collectAsState()
            val lang by app.settings.language.collectAsState()
            val uiSize by app.settings.uiSize.collectAsState()
            ScaledDensity(isTv = tv, uiSize = uiSize) {
                DhakaFlixTheme(appIcon = appIcon, isTv = tv, edge = edgeFor(tv, LocalConfiguration.current.screenWidthDp)) {
                    CompositionLocalProvider(LocalStrings provides stringsFor(lang)) {
                        PlayerScreen(c)
                    }
                }
            }
        }
        c.begin()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = ctrl
        if (c != null && c.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Keeps the TV's own screensaver away while the player is open, paused or not. */
    private var wake: android.os.PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        if (wake == null) wake = runCatching {
            (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "dhakaflix:player")
                .apply { setReferenceCounted(false) }
        }.getOrNull()
        runCatching { wake?.acquire(6 * 3600_000L) }
    }

    override fun onPause() {
        runCatching { if (wake?.isHeld == true) wake?.release() }
        super.onPause()
        // stop sound the moment the player is closed, don't wait for onDestroy
        if (isFinishing) { ctrl?.release(); ctrl = null }
    }

    override fun onStop() {
        super.onStop()
        ctrl?.onStop()
    }

    override fun onDestroy() {
        ctrl?.release()
        ctrl = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LIST = "playlist"
        const val EXTRA_INDEX = "index"
        const val EXTRA_MODE = "mode"
        const val MODE_ASK = "ask"
        const val MODE_RESUME = "resume"
        const val MODE_START = "start"

        private var lastStart = 0L

        fun start(ctx: Context, list: List<PlayItem>, index: Int, mode: String = MODE_ASK) {
            if (list.isEmpty()) return
            // a double OK press must not open two players (the hidden one kept playing sound)
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastStart < 2000) return
            lastStart = now
            val json = Json.encodeToString(ListSerializer(PlayItem.serializer()), list)
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(EXTRA_LIST, json)
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_MODE, mode)
            )
        }
    }
}
