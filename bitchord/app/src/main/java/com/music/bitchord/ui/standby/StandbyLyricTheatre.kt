package com.music.bitchord.ui.standby

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.MotionEvent
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.playback.PlaybackPosition
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Retains one attached WebView instance per Dock session so track changes do not re-parse the
 * bundled JIZURA runtime, while holding WebView creation until attachment so Chromium uses GPU
 * canvas rendering rather than falling back to software rasterization.
 */
internal class LyricPageHolder(private val context: Context) {
    var page: LyricPage? = null
        private set

    fun getOrCreate(): LyricPage = page ?: LyricPage(context.applicationContext).also { page = it }

    fun destroy() {
        page?.destroy()
        page = null
    }
}

@Composable
internal fun rememberStandbyLyricPageHolder(): LyricPageHolder {
    val context = LocalContext.current.applicationContext
    val holder = remember(context) { LyricPageHolder(context) }
    DisposableEffect(holder) {
        onDispose { holder.destroy() }
    }
    return holder
}

/**
 * Offline-isolated StaaaaandBy MIT JIZURA Lyric Theatre WebView renderer.
 *
 * Touch events pass straight through to Compose so Dock Mode keeps 3-zone gestures and Bedside
 * Lock without delegating UI control or credentials to JavaScript.
 */
@Composable
internal fun StandbyLyricTheatre(
    holder: LyricPageHolder,
    songKey: String,
    title: String,
    artist: String,
    durationMs: Long,
    lrcText: String,
    lyricTheme: StandbyLyricTheme = StandbyLyricTheme.CLASSY,
    artBitmap: Bitmap?,
    isPlaying: Boolean,
    position: PlaybackPosition,
    lyricsOffsetMs: Int,
    onHealthyChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = remember(holder) { holder.getOrCreate() }

    AndroidView(
        factory = {
            (page.view.parent as? android.view.ViewGroup)?.removeView(page.view)
            page.view
        },
        modifier = modifier,
    )

    DisposableEffect(page) {
        onDispose { page.clear() }
    }

    val ready by page.ready.collectAsStateWithLifecycle()
    val healthy by page.healthy.collectAsStateWithLifecycle()

    LaunchedEffect(healthy) {
        onHealthyChanged(healthy)
    }

    val schemes by produceState<JSONArray?>(initialValue = null, artBitmap) {
        value = artBitmap?.takeUnless { it.isRecycled }?.let { bmp ->
            withContext(Dispatchers.Default) {
                runCatching { StandbyArtColors.schemesJsonFor(bmp) }.getOrNull()
            }
        }
    }

    LaunchedEffect(ready, songKey, lrcText, durationMs) {
        if (ready && lrcText.isNotBlank()) {
            page.setSong(
                key = songKey,
                title = title,
                artist = artist,
                durationMs = durationMs,
                lrc = lrcText,
                theme = lyricTheme.id,
                schemes = schemes,
            )
        }
    }

    LaunchedEffect(ready, lyricTheme) {
        if (ready) {
            page.setTheme(lyricTheme.id)
        }
    }

    LaunchedEffect(ready, songKey, schemes) {
        if (ready) {
            page.setColors(songKey, schemes)
        }
    }

    val effectivePlayheadKey = "${position.positionMs}:${position.sampledAtNanos}:${position.advancing}:$lyricsOffsetMs"
    LaunchedEffect(ready, songKey, isPlaying, effectivePlayheadKey) {
        if (ready) {
            val activePlaying = isPlaying && (position.advancing || position.sampledAtNanos == 0L)
            val extraMs = if (activePlaying && position.sampledAtNanos > 0L) {
                (System.nanoTime() - position.sampledAtNanos).coerceAtLeast(0L) / 1_000_000L
            } else {
                0L
            }
            val rawPos = position.positionMs.coerceAtLeast(0L) + extraMs
            val adjustedPos = (rawPos - lyricsOffsetMs.toLong()).coerceAtLeast(0L)
            page.setAnchor(positionMs = adjustedPos, playing = isPlaying, speed = 1f)
        }
    }
}

private class PassThroughWebView(context: Context) : WebView(context) {
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}

internal class LyricPage(context: Context) {

    companion object {
        private const val ALLOWED_HOST = "appassets.androidplatform.net"
        private const val ENTRY_URL = "https://$ALLOWED_HOST/assets/lyric/index.html"
    }

    private val appContext = context.applicationContext
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _healthy = MutableStateFlow(true)
    val healthy: StateFlow<Boolean> = _healthy.asStateFlow()

    val view: WebView = PassThroughWebView(appContext)

    init {
        @SuppressLint("SetJavaScriptEnabled")
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
        }
        view.setBackgroundColor(Color.BLACK)
        view.isClickable = false
        view.isFocusable = false
        view.isLongClickable = false
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                serveLocalOnly(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                if (url == ENTRY_URL) {
                    _ready.value = true
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    _ready.value = false
                    _healthy.value = false
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                _ready.value = false
                _healthy.value = false
                return true
            }
        }
        view.loadUrl(ENTRY_URL)
    }

    fun setSong(
        key: String,
        title: String,
        artist: String,
        durationMs: Long,
        lrc: String,
        theme: String = StandbyLyricTheme.CLASSY.id,
        schemes: JSONArray?,
    ) {
        val json = JSONObject()
            .put("key", key)
            .put("title", title)
            .put("artist", artist)
            .put("durationMs", durationMs.coerceAtLeast(0L))
            .put("lrc", lrc)
            .put("theme", theme)
            .put("schemes", schemes ?: JSONObject.NULL)
        view.evaluateJavascript("window.lyric && window.lyric.setSong($json)", null)
    }

    fun setTheme(themeId: String) {
        if (_ready.value) {
            val quoted = JSONObject.quote(themeId)
            view.evaluateJavascript("window.lyric && window.lyric.setTheme && window.lyric.setTheme($quoted)", null)
        }
    }

    fun setColors(key: String, schemes: JSONArray?) {
        val json = JSONObject()
            .put("key", key)
            .put("schemes", schemes ?: JSONObject.NULL)
        view.evaluateJavascript("window.lyric && window.lyric.setColors($json)", null)
    }

    fun setAnchor(positionMs: Long, playing: Boolean, speed: Float = 1f) {
        val json = JSONObject()
            .put("positionMs", positionMs.coerceAtLeast(0L))
            .put("playing", playing)
            .put("speed", speed.toDouble())
        view.evaluateJavascript("window.lyric && window.lyric.setAnchor($json)", null)
    }

    fun reroll() {
        if (_ready.value) {
            view.evaluateJavascript("window.lyric && window.lyric.reroll()", null)
        }
    }

    fun clear() {
        if (_ready.value) {
            view.evaluateJavascript("window.lyric && window.lyric.clear()", null)
        }
    }

    fun destroy() {
        _ready.value = false
        view.stopLoading()
        view.loadUrl("about:blank")
        view.destroy()
    }

    @SuppressLint("ResourceType")
    private fun serveLocalOnly(uri: Uri): WebResourceResponse {
        if (uri.scheme != "https" || uri.host != ALLOWED_HOST) {
            return emptyResponse(403, "Forbidden")
        }
        val path = uri.encodedPath.orEmpty()
        if (path.contains("..")) {
            return emptyResponse(403, "Forbidden")
        }
        return when {
            path == "/res/font/fira_code.ttf" -> runCatching {
                WebResourceResponse(
                    "font/ttf",
                    null,
                    200,
                    "OK",
                    mapOf("Cache-Control" to "max-age=31536000"),
                    appContext.resources.openRawResource(R.font.fira_code),
                )
            }.getOrElse { emptyResponse(404, "Not Found") }

            path.startsWith("/assets/") -> {
                val assetRel = path.removePrefix("/assets/")
                runCatching {
                    val stream = appContext.assets.open(assetRel)
                    WebResourceResponse(
                        mimeTypeFor(assetRel),
                        if (mimeTypeFor(assetRel).startsWith("text/") || mimeTypeFor(assetRel).endsWith("javascript")) "utf-8" else null,
                        200,
                        "OK",
                        mapOf("Cache-Control" to "max-age=3600"),
                        stream,
                    )
                }.getOrElse { emptyResponse(404, "Not Found") }
            }

            else -> emptyResponse(403, "Forbidden")
        }
    }

    private fun mimeTypeFor(path: String): String = when {
        path.endsWith(".html", ignoreCase = true) -> "text/html"
        path.endsWith(".js", ignoreCase = true) -> "application/javascript"
        path.endsWith(".css", ignoreCase = true) -> "text/css"
        path.endsWith(".json", ignoreCase = true) -> "application/json"
        path.endsWith(".ttf", ignoreCase = true) -> "font/ttf"
        path.endsWith(".woff2", ignoreCase = true) -> "font/woff2"
        path.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
        path.endsWith(".png", ignoreCase = true) -> "image/png"
        else -> "application/octet-stream"
    }

    private fun emptyResponse(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            code,
            reason,
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
}
