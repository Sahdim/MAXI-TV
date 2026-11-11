package com.maxitv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Lecteur vidéo : ExoPlayer (décodage matériel 4K / HEVC, mkv, HLS, Dolby) pour l'image,
 * et la page player.html (transparente, par-dessus) pour toute l'interface : même design que l'appli.
 * La page envoie ses ordres via window.NativePlayer, et reçoit l'état toutes les 500 ms via window.MP.state().
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var ui: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var url = ""
    private var title = ""
    private var triedTs = false
    private var queueBuilt = false
    private val queue = ArrayDeque<String>()
    private var failed = false

    private val tick = object : Runnable {
        override fun run() {
            pushState()
            handler.postDelayed(this, 500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player)
        ui = findViewById(R.id.ui)
        url = intent.getStringExtra("url") ?: ""
        title = intent.getStringExtra("title") ?: ""
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {   // jamais file:// ni content://
            finish()
            return
        }
        hideSystemUi()

        ui.setBackgroundColor(Color.TRANSPARENT)
        ui.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val s = ui.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        ui.addJavascriptInterface(PlayerBridge(), "NativePlayer")
        ui.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // cette interface ne charge que ses propres fichiers : tout le reste est refusé
                if (request.url.host == NetProxy.HOST) {          // seule exception : la recherche de sous-titres (wyzie.io)
                    val h = Uri.parse(request.url.getQueryParameter("u") ?: "").host ?: ""
                    if (h == "sub.wyzie.io" || h.endsWith(".wyzie.io")) return NetProxy.handle(request)
                }
                return LocalAssets.handle(this@PlayerActivity, request)
                    ?: WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", mapOf<String, String>(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        ui.isFocusable = true
        ui.isFocusableInTouchMode = true
        val query = "mode=native&u=" + Uri.encode(url) + "&t=" + Uri.encode(title)
        ui.loadUrl(LocalAssets.ORIGIN + "/player.html?" + query)
        ui.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        queueBuilt = false; queue.clear(); triedTs = false
        start(url)
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        release()
        super.onStop()
    }

    override fun onDestroy() {
        ui.destroy()
        super.onDestroy()
    }

    // ---------- lecture ----------
    private fun start(link: String) {
        release()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(NetProxy.WEB_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(25000)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20000, 60000, 2500, 5000)
            .build()
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(load)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onFailure(link)
            }
        })
        playerView.player = p
        p.setMediaItem(MediaItem.fromUri(link))
        p.playWhenReady = true
        p.prepare()
        player = p
        failed = false
    }

    private fun onFailure(link: String) {
        // certains fournisseurs n'ont pas de HLS (.m3u8) pour le direct : on réessaie en flux .ts
        if (!triedTs && link.contains(".m3u8") && link.contains("/live/")) {
            triedTs = true
            start(link.replace(".m3u8", ".ts"))
            return
        }
        // films / séries : si le format demandé ne passe pas (mkv, avi, flux HLS absent…), on essaie les autres formats du même titre
        if (!queueBuilt) {
            queueBuilt = true
            val m = Regex("^(.*/(?:movie|series)/[^/]+/[^/]+/[0-9]+)\\.([A-Za-z0-9]+)(\\?.*)?$").find(url)
            if (m != null) {
                val b = m.groupValues[1]; val e = m.groupValues[2].lowercase()
                for (x in listOf("m3u8", "mp4", "mkv", "ts")) if (x != e) queue.addLast("$b.$x")
            }
        }
        val next = queue.removeFirstOrNull()
        if (next != null) { start(next); return }
        failed = true
        pushState()
    }

    private fun release() {
        playerView.player = null
        player?.release()
        player = null
    }

    // ---------- état envoyé à l'interface ----------
    private fun pushState() {
        val p = player
        val o = JSONObject()
        if (p != null) {
            o.put("pos", p.currentPosition / 1000.0)
            o.put("dur", if (p.duration == C.TIME_UNSET) 0.0 else p.duration / 1000.0)
            o.put("buf", p.bufferedPosition / 1000.0)
            o.put("playing", p.playWhenReady && p.playbackState != Player.STATE_ENDED)
            o.put("wait", p.playbackState == Player.STATE_BUFFERING)
            o.put("audio", trackList(C.TRACK_TYPE_AUDIO))
            o.put("text", trackList(C.TRACK_TYPE_TEXT))
        }
        o.put("fail", failed)
        ui.evaluateJavascript("window.MP&&MP.state(" + JSONObject.quote(o.toString()) + ")", null)
    }

    /** Toutes les pistes d'un type, dans un ordre stable : l'indice sert d'identifiant côté interface. */
    private fun flat(type: Int): List<Pair<Tracks.Group, Int>> {
        val p = player ?: return emptyList()
        val out = ArrayList<Pair<Tracks.Group, Int>>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (g.isTrackSupported(i)) out.add(Pair(g, i))
            }
        }
        return out
    }

    private fun trackList(type: Int): JSONArray {
        val arr = JSONArray()
        val labels = ArrayList<String>()
        val items = flat(type)
        for ((n, t) in items.withIndex()) {
            val f = t.first.getTrackFormat(t.second)
            val fl: String? = f.label
            var label: String
            if (fl != null && fl.isNotEmpty()) {
                label = fl
            } else {
                val lang: String? = f.language
                if (lang != null && lang != "und") {
                    val name: String = Locale(lang).getDisplayLanguage(Locale.FRENCH)
                    label = if (name.isEmpty() || name.equals(lang, ignoreCase = true)) lang.uppercase() else name.substring(0, 1).uppercase() + name.substring(1)
                } else {
                    label = "Piste " + (n + 1)
                }
            }
            if (type == C.TRACK_TYPE_AUDIO && f.channelCount >= 6) label += " 5.1"
            labels.add(label)
        }
        for ((n, t) in items.withIndex()) {
            val base = labels[n]
            var label = base
            if (labels.count { it == base } > 1) label += " (" + (labels.subList(0, n).count { it == base } + 1) + ")"
            val o = JSONObject()
            o.put("id", n)
            o.put("label", label)
            o.put("sel", t.first.isTrackSelected(t.second))
            arr.put(o)
        }
        return arr
    }

    // ---------- ordres venant de l'interface ----------
    inner class PlayerBridge {
        private fun ok(t: String?) = t == LocalAssets.token

        @JavascriptInterface
        fun toggle(t: String) {
            if (!ok(t)) return
            runOnUiThread { player?.let { if (it.playWhenReady && it.playbackState != Player.STATE_ENDED) it.pause() else it.play() } }
        }

        @JavascriptInterface
        fun seekTo(ms: Double, t: String) {
            if (!ok(t)) return
            runOnUiThread { player?.seekTo(ms.toLong().coerceAtLeast(0L)) }
        }

        @JavascriptInterface
        fun seekBy(ms: Double, t: String) {
            if (!ok(t)) return
            runOnUiThread { player?.let { it.seekTo((it.currentPosition + ms.toLong()).coerceAtLeast(0L)) } }
        }

        @JavascriptInterface
        fun setFit(mode: String, t: String) {
            if (!ok(t)) return
            runOnUiThread {
                playerView.resizeMode = when (mode) {
                    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        }

        @JavascriptInterface
        fun selectAudio(id: Int, t: String) {
            if (!ok(t)) return
            runOnUiThread {
                val p = player ?: return@runOnUiThread
                val tr = flat(C.TRACK_TYPE_AUDIO).getOrNull(id) ?: return@runOnUiThread
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(tr.first.mediaTrackGroup, tr.second))
                    .build()
            }
        }

        @JavascriptInterface
        fun selectText(id: Int, t: String) {
            if (!ok(t)) return
            runOnUiThread {
                val p = player ?: return@runOnUiThread
                val b = p.trackSelectionParameters.buildUpon()
                if (id < 0) {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val tr = flat(C.TRACK_TYPE_TEXT).getOrNull(id) ?: return@runOnUiThread
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    b.setOverrideForType(TrackSelectionOverride(tr.first.mediaTrackGroup, tr.second))
                }
                p.trackSelectionParameters = b.build()
            }
        }

        @JavascriptInterface
        fun close(t: String) {
            if (!ok(t)) return
            runOnUiThread { finish() }
        }
    }

    // ---------- télécommande ----------
    /** Touches média : on les transmet à l'interface pour garder l'animation et la barre cohérentes. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            val k = when (e.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "MediaPlayPause"
                KeyEvent.KEYCODE_MEDIA_PLAY -> "MediaPlay"
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "MediaPause"
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "MediaFastForward"
                KeyEvent.KEYCODE_MEDIA_REWIND -> "MediaRewind"
                else -> null
            }
            if (k != null) {
                ui.evaluateJavascript("window.MP&&MP.key('$k')", null)
                return true
            }
            if (e.keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                finish()
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    /** Retour : l'interface ferme d'abord un menu ouvert ; sinon on quitte le lecteur. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        ui.evaluateJavascript("(function(){try{return window.MP?MP.back():false}catch(e){return false}})()") { r ->
            if (r != "true") finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}
