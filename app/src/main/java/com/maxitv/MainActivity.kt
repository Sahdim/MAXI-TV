package com.maxitv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * Écran principal : affiche l'appli web (assets/index.html) en plein écran.
 * La télécommande envoie des touches fléchées que la page gère elle-même (navigation spatiale).
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var lastBack = 0L

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()

        // pas de débogage à distance (chrome://inspect) : personne ne peut lire la page depuis le réseau
        WebView.setWebContentsDebuggingEnabled(false)

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false           // aperçus et bandes-annonces automatiques
        s.allowFileAccess = false                            // la page n'a accès à aucun fichier de l'appareil
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        if (Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.useWideViewPort = true                             // la page se règle sur une largeur de 1920 px
        s.loadWithOverviewMode = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.textZoom = 100
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = s.userAgentString + " AFTMM MAXItv/1.0"
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        web.addJavascriptInterface(Bridge(), "Native")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // 1) nos pages (https://maxi.app)  2) le relais réseau (https://maxi.proxy)  3) le reste passe normalement
                return LocalAssets.handle(this@MainActivity, request)
                    ?: NetProxy.handle(request)
                    ?: super.shouldInterceptRequest(view, request)
            }

            // la page principale ne peut pas être détournée vers un autre site
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.isForMainFrame && request.url.host != LocalAssets.HOST
            }

            // le moteur d'affichage a été arrêté par Android (manque de mémoire) : on relance proprement au lieu de planter
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                runOnUiThread { recreate() }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun getDefaultVideoPoster(): Bitmap {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)   // pas de grande icône « lecture » sur les vidéos
            }
        }

        web.loadUrl(LocalAssets.ORIGIN + "/index.html")
        web.requestFocus()
    }

    /**
     * Fonctions que la page peut appeler : window.Native.play(...), etc.
     * Chaque appel doit fournir le jeton secret de la session : une iframe tierce (YouTube...) ne le connaît pas.
     */
    inner class Bridge {
        private fun ok(t: String?) = t == LocalAssets.token

        @JavascriptInterface
        fun play(url: String, title: String, t: String) {
            if (!ok(t)) return
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return    // jamais file:// ni content://
            runOnUiThread {
                val i = Intent(this@MainActivity, PlayerActivity::class.java)
                i.putExtra("url", url)
                i.putExtra("title", title)
                startActivity(i)
            }
        }

        @JavascriptInterface
        fun config(t: String): String {
            if (!ok(t)) return ""
            return try {
                assets.open("config.json").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }
        }

        /** Le serveur IPTV saisi à la connexion : seul lui peut avoir un certificat auto-signé. */
        @JavascriptInterface
        fun trust(host: String, t: String) {
            if (!ok(t)) return
            NetProxy.trust(host)
        }

        @JavascriptInterface
        fun secureGet(name: String, t: String): String {
            if (!ok(t)) return ""
            return Secure.get(this@MainActivity, name)
        }

        @JavascriptInterface
        fun secureSet(name: String, value: String, t: String) {
            if (!ok(t)) return
            Secure.set(this@MainActivity, name, value)
        }

        @JavascriptInterface
        fun openUrl(url: String, t: String) {                 // uniquement des recherches / vidéos YouTube, ouvertes dans l'appli YouTube ou le navigateur
            if (!ok(t) || !url.startsWith("https://www.youtube.com/")) return
            runOnUiThread { try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (e: Exception) { Toast.makeText(this@MainActivity, "YouTube indisponible", Toast.LENGTH_SHORT).show() } }
        }

        @JavascriptInterface
        fun toast(message: String, t: String) {
            if (!ok(t)) return
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }
    }

    /** Touche Retour : la page décide (fermer une fiche, revenir à l'accueil...). Sinon, double appui pour quitter. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(function(){try{return window.tvBack?window.tvBack():false}catch(e){return false}})()") { result ->
            if (result != "true") {
                val now = System.currentTimeMillis()
                if (now - lastBack < 2500) {
                    finish()
                } else {
                    lastBack = now
                    Toast.makeText(this, "Appuie encore sur Retour pour quitter", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        hideSystemUi()
        web.requestFocus()
        web.evaluateJavascript("window.tvResume && window.tvResume()", null)
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
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
