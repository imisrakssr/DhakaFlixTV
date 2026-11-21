package com.israk.dhakaflix.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Opens a YouTube trailer in the YouTube app (TV or phone), falling back to the browser. */
fun openTrailer(ctx: Context, key: String) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(app) }.recoverCatching { ctx.startActivity(web) }
}

/**
 * Muted, control-less YouTube trailer that fades in over the backdrop once it actually plays.
 * Never takes focus; pauses with the screen; disappears silently if YouTube refuses to embed it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BackgroundTrailer(key: String, modifier: Modifier = Modifier) {
    var playing by remember(key) { mutableStateOf(false) }
    var failed by remember(key) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (playing && !failed) 1f else 0f, tween(900), label = "trailer")
    var web by remember { mutableStateOf<WebView?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> web?.let { it.onPause(); it.evaluateJavascript("try{p.pauseVideo()}catch(e){}", null) }
                Lifecycle.Event.ON_RESUME -> web?.let { it.onResume(); it.evaluateJavascript("try{p.playVideo()}catch(e){}", null) }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (failed) return
    AndroidView(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        factory = { ctx ->
            WebView(ctx).apply {
                isFocusable = false
                isFocusableInTouchMode = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setBackgroundColor(AColor.BLACK)
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                addJavascriptInterface(object {
                    @JavascriptInterface fun playing() { post { playing = true } }
                    @JavascriptInterface fun error(code: Int) { post { failed = true } }
                }, "Android")
                loadDataWithBaseURL("https://dhakaflix.tv/", html(key), "text/html", "utf-8", null)
                web = this
            }
        },
        onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy(); web = null },
    )
}

private fun html(key: String) = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1">
<style>html,body{margin:0;background:#000;overflow:hidden;height:100%}#p,iframe{position:fixed;top:-12%;left:-6%;width:112%;height:124%;border:0;pointer-events:none}</style>
</head><body><div id=p></div><script>
var p;
function onYouTubeIframeAPIReady(){p=new YT.Player('p',{videoId:'$key',host:'https://www.youtube-nocookie.com',
playerVars:{autoplay:1,mute:1,controls:0,disablekb:1,fs:0,rel:0,iv_load_policy:3,playsinline:1,loop:1,playlist:'$key',start:4,origin:'https://dhakaflix.tv'},
events:{onReady:function(e){e.target.mute();e.target.playVideo();},
onStateChange:function(e){if(e.data==1){setTimeout(function(){Android.playing()},600)}},
onError:function(e){Android.error(e.data)}}});}
setTimeout(function(){if(!p||!p.getPlayerState||p.getPlayerState()!=1)Android.error(-1)},15000);
</script><script src="https://www.youtube.com/iframe_api"></script></body></html>"""
