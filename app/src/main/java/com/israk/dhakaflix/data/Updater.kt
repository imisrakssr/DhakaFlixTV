package com.israk.dhakaflix.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.israk.dhakaflix.BuildConfig
import com.israk.dhakaflix.DhakaFlixApp
import com.israk.dhakaflix.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updates from the GitHub releases: check → show the notes → download → install, all inside the app.
 * TV always takes the armv7 APK (Mi TV Stick), phones the arm64 one (armv7 on old 32-bit phones).
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/imisrakssr/DhakaFlixTV/releases/latest"

    data class Release(val version: String, val code: Int, val notes: String, val apkUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** A newer release than the installed one: drives the dot on the Settings tab / About me. */
    private val _available = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = _available
    @Volatile private var lastCheck = 0L

    /** Automatic check: on app open and every few hours while it's open; silent when it fails. */
    fun checkIfStale(maxAgeMs: Long = 6 * 3600_000L) {
        if (System.currentTimeMillis() - lastCheck > maxAgeMs) check(quiet = true)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val app get() = DhakaFlixApp.instance

    private fun abiSuffix(ctx: Context): String {
        if (MainActivity.isTvDevice(ctx)) return "-armv7.apk"
        return if (Build.SUPPORTED_64_BIT_ABIS.any { it.startsWith("arm64") }) "-arm64.apk" else "-armv7.apk"
    }

    fun check(quiet: Boolean = false) {
        val before = _state.value
        if (before is State.Checking || before is State.Downloading || before is State.Installing) return
        lastCheck = System.currentTimeMillis()
        if (!quiet) _state.value = State.Checking
        app.appScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val req = Request.Builder().url(LATEST)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "DhakaFlixTV/${BuildConfig.VERSION_NAME}")
                    .build()
                val body = app.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("GitHub HTTP ${r.code}")
                    r.body?.string().orEmpty()
                }
                val o = json.parseToJsonElement(body) as JsonObject
                val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: throw java.io.IOException("No release found")
                val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
                if (code <= BuildConfig.VERSION_CODE) return@runCatching State.UpToDate
                val suffix = abiSuffix(app)
                val asset = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(suffix) == true }
                    ?: throw java.io.IOException("No ${suffix.removePrefix("-").removeSuffix(".apk")} APK in $tag")
                State.Available(
                    Release(
                        version = tag.removePrefix("v"), code = code,
                        notes = cleanNotes(o["body"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                        apkUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: throw java.io.IOException("No download link"),
                        size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                )
            }.getOrElse { State.Failed(it.message ?: it.toString()) }
            when (result) {
                is State.Available -> _available.value = result.release
                State.UpToDate -> _available.value = null
                else -> Unit
            }
            // a silent check only changes what's shown when it found something
            if (!quiet || result is State.Available) _state.value = result
        }
    }

    /** The release body minus the "which APK" lines (the app picks the right one by itself). */
    private fun cleanNotes(body: String): String = body.lines()
        .filterNot { it.contains("APK", ignoreCase = false) && it.contains("→") }
        .joinToString("\n").replace("**", "").trim()

    fun downloadAndInstall(release: Release) {
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(release, 0f)
        app.appScope.launch(Dispatchers.IO) {
            runCatching {
                val file = File(app.cacheDir, "update.apk")
                val client = app.http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                val req = Request.Builder().url(release.apkUrl).header("User-Agent", "DhakaFlixTV/${BuildConfig.VERSION_NAME}").build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("Download HTTP ${r.code}")
                    val b = r.body ?: throw java.io.IOException("Empty download")
                    val total = b.contentLength().takeIf { it > 0 } ?: release.size
                    b.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; _state.value = State.Downloading(release, pct / 100f) }
                                }
                            }
                        }
                    }
                }
                _state.value = State.Installing(release)
                install(file)
            }.onFailure { _state.value = State.Failed(it.message ?: it.toString(), release) }
        }
    }

    /** Hands the APK to Android's package installer; Android shows its own "Update this app?" screen. */
    private fun install(file: File) {
        val ctx = app
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(ctx, InstallReceiver::class.java)
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val rel = (_state.value as? State.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit // the confirm screen is being shown
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle // the app restarts as the new version
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = rel?.let { State.Available(it) } ?: State.Idle
            else -> _state.value = State.Failed(message ?: "Install failed ($status)", rel)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirm) }
        }
        Updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
