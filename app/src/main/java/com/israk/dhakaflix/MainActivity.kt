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
import com.israk.dhakaflix.ui.AppRoot
import com.israk.dhakaflix.ui.edgeFor
import com.israk.dhakaflix.ui.theme.Bg
import com.israk.dhakaflix.ui.theme.DhakaFlixTheme
import com.israk.dhakaflix.ui.theme.LocalStrings
import com.israk.dhakaflix.ui.theme.stringsFor

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DhakaFlixApp
        app.library.syncIfStale()
        val tv = isTvDevice(this)
        setContent {
            val accent by app.settings.accent.collectAsState()
            val lang by app.settings.language.collectAsState()
            val width = LocalConfiguration.current.screenWidthDp
            val strings = remember(lang) { stringsFor(lang) }
            DhakaFlixTheme(accentIndex = accent, isTv = tv, edge = edgeFor(tv, width)) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    Box(Modifier.fillMaxSize().background(Bg)) {
                        AppRoot(onExit = { finish() })
                    }
                }
            }
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
