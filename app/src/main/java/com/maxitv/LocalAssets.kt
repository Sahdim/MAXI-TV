package com.maxitv

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.security.SecureRandom

/**
 * Sert les pages de l'appli (assets) sous une vraie adresse https://maxi.app au lieu de file://.
 * Avantages sécurité : plus aucun accès aux fichiers de la tablette depuis la page, politique CSP stricte,
 * et un jeton secret propre à chaque lancement que seule notre page connaît (les iframes tierces,
 * comme YouTube, ne peuvent donc pas appeler les fonctions Android).
 */
object LocalAssets {
    const val HOST = "maxi.app"
    const val ORIGIN = "https://maxi.app"

    val token: String by lazy {
        val b = ByteArray(24)
        SecureRandom().nextBytes(b)
        b.joinToString("") { "%02x".format(it) }
    }

    // seules ces pages sont servies (config.json n'en fait pas partie : il passe par le pont Android)
    private val allowed = setOf("index.html", "player.html", "hls.min.js")

    private const val CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
        "img-src * data: blob:; media-src * data: blob:; connect-src *; " +
        "frame-src 'self' https://www.youtube.com https://www.youtube-nocookie.com; worker-src blob:; " +
        "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'"

    fun handle(ctx: Context, req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        if (u.host != HOST) return null
        if (u.scheme != "https") return empty(400)
        var name = (u.path ?: "/").trimStart('/')
        if (name.isEmpty()) name = "index.html"
        if (name !in allowed) return empty(404)
        return try {
            val raw = ctx.assets.open(name).use { it.readBytes() }
            val bytes = if (name.endsWith(".html")) inject(raw) else raw
            val headers = mapOf(
                "Content-Security-Policy" to CSP,
                "X-Content-Type-Options" to "nosniff",
                "Referrer-Policy" to "no-referrer",
                "Cache-Control" to "no-store"
            )
            WebResourceResponse(mime(name), "utf-8", 200, "OK", headers, ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            empty(404)
        }
    }

    /** Place le jeton de la session tout en haut de la page, avant tout autre script. */
    private fun inject(raw: ByteArray): ByteArray {
        val html = String(raw, Charsets.UTF_8)
        val tag = "<head>"
        val i = html.indexOf(tag)
        if (i < 0) return raw
        val out = html.substring(0, i + tag.length) + "<script>window.__NT=\"" + token + "\";</script>" + html.substring(i + tag.length)
        return out.toByteArray(Charsets.UTF_8)
    }

    private fun mime(name: String): String = when {
        name.endsWith(".html") -> "text/html"
        name.endsWith(".js") -> "text/javascript"
        else -> "application/octet-stream"
    }

    fun empty(code: Int): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", code, if (code == 404) "Not Found" else "Bad Request", mapOf<String, String>(), ByteArrayInputStream(ByteArray(0)))
}
