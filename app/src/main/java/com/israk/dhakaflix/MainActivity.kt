package com.israk.dhakaflix

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.israk.dhakaflix.data.LibraryRepository
import com.israk.dhakaflix.ui.AppRoot
import com.israk.dhakaflix.ui.SplashScreen
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.israk.dhakaflix.ui.edgeFor
import com.israk.dhakaflix.ui.theme.Bg
import com.israk.dhakaflix.ui.theme.DhakaFlixTheme
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.ScaledDensity
import com.israk.dhakaflix.ui.theme.stringsFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DhakaFlixApp
        app.library.verifyTmdb()
        app.library.mdblist.verify()

        // New uploads show up on their own: incremental scan when the app opens and every 30 minutes.
        // Simkl (watched / watchlist / Continue Watching from other devices): on open and every 5 minutes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    app.library.syncIfStale(LibraryRepository.THIRTY_MINUTES)
                    app.library.checkOtherServers()
                    app.library.simkl.syncIfStale()
                    com.israk.dhakaflix.data.Updater.checkIfStale()
                    delay(5 * 60_000L)
                }
            }
        }

        val tv = isTvDevice(this)
        if (!tv) transparentStatusBar()
        // Compose paints the whole screen itself: drop the window background (one less full-screen fill per frame)
        window.decorView.post { window.setBackgroundDrawable(null) }
        setContent {
            val appIcon by app.settings.appIcon.collectAsState()
            // with the app's own screensaver on, keep the TV's screensaver from taking over while we're open
            val saverMin by app.settings.screensaver.collectAsState()
            // ...but after 30 minutes without a button press, let the TV sleep as usual
            // phone: never hold the screen on while browsing — the phone's own sleep setting decides
            if (tv) androidx.compose.runtime.LaunchedEffect(saverMin) {
                while (true) {
                    val idle = android.os.SystemClock.elapsedRealtime() - com.israk.dhakaflix.ui.AppEvents.lastInput >
                        com.israk.dhakaflix.player.PlayerActivity.IDLE_SLEEP_MS
                    if (saverMin > 0 && !idle) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    kotlinx.coroutines.delay(30_000)
                }
            }
            val lang by app.settings.language.collectAsState()
            val uiSize by app.settings.uiSize.collectAsState()
            val width = LocalConfiguration.current.screenWidthDp
            val strings = remember(lang) { stringsFor(lang) }
            ScaledDensity(isTv = tv, uiSize = uiSize) {
                DhakaFlixTheme(appIcon = appIcon, isTv = tv, edge = edgeFor(tv, width)) {
                    val newEpisodes by app.library.newEpisodeIds.collectAsState()
                    CompositionLocalProvider(LocalStrings provides strings, com.israk.dhakaflix.ui.components.LocalNewEpisodes provides newEpisodes) {
                        var splashDone by rememberSaveable { mutableStateOf(false) }
                        Box(Modifier.fillMaxSize().background(Bg)) {
                            Crossfade(targetState = splashDone, animationSpec = tween(450), label = "splash") { done ->
                                if (done) AppRoot(onExit = { finish() }) else SplashScreen { splashDone = true }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        com.israk.dhakaflix.ui.AppEvents.lastInput = android.os.SystemClock.elapsedRealtime()
        if (event.action == android.view.KeyEvent.ACTION_DOWN) when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                com.israk.dhakaflix.ui.NavGate.lastDir = event.keyCode
        }
        // any key just wakes the screensaver up
        if (com.israk.dhakaflix.ui.AppEvents.screensaverOn.value) {
            if (event.action == android.view.KeyEvent.ACTION_UP) com.israk.dhakaflix.ui.AppEvents.screensaverOn.value = false
            return true
        }
        if (event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH || event.keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST) {
            if (event.action == android.view.KeyEvent.ACTION_UP) com.israk.dhakaflix.ui.AppEvents.voiceSearch.value++
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        com.israk.dhakaflix.ui.AppEvents.lastInput = android.os.SystemClock.elapsedRealtime()
        if (com.israk.dhakaflix.ui.AppEvents.screensaverOn.value) { com.israk.dhakaflix.ui.AppEvents.screensaverOn.value = false; return true }
        return super.dispatchTouchEvent(ev)
    }

    /** Phone: the status bar stays visible but transparent; the app draws underneath it. */
    @Suppress("DEPRECATION")
    private fun transparentStatusBar() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            isAppearanceLightStatusBars = false // white icons on our dark pages
        }
    }

    companion object {
        fun isTvDevice(ctx: Context): Boolean {
            val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
            val pm = ctx.packageManager
            return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        }
    }
}
