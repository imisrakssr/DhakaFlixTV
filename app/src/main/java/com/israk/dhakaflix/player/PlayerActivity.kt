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
import com.israk.dhakaflix.ui.theme.stringsFor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class PlayerActivity : ComponentActivity() {

    private var ctrl: PlayerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            val accent by app.settings.accent.collectAsState()
            val lang by app.settings.language.collectAsState()
            DhakaFlixTheme(accentIndex = accent, isTv = tv, edge = edgeFor(tv, LocalConfiguration.current.screenWidthDp)) {
                CompositionLocalProvider(LocalStrings provides stringsFor(lang)) {
                    PlayerScreen(c)
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

        fun start(ctx: Context, list: List<PlayItem>, index: Int, mode: String = MODE_ASK) {
            if (list.isEmpty()) return
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
