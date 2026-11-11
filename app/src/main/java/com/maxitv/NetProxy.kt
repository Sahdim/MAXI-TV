package com.maxitv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Relais réseau de l'appli.
 *
 * La page web demande des adresses du type https://maxi.proxy/f?u=<adresse>. Cette classe les
 * intercepte, télécharge l'adresse réelle côté Android puis renvoie la réponse à la page.
 * Avantages : plus de blocage « CORS », les serveurs qui refusent les navigateurs (ou les adresses
 * http) répondent, et les serveurs IPTV aux certificats auto-signés fonctionnent.
 */
object NetProxy {
    const val HOST = "maxi.proxy"
    const val WEB_UA = "Mozilla/5.0 (Linux; Android 9; AFTMM) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Certificats : vérifiés normalement partout. Seul le serveur IPTV saisi par l'utilisateur à la connexion peut
    // avoir un certificat auto-signé (fréquent chez les fournisseurs IPTV) : l'exception ne vaut que pour ce nom exact.
    @Volatile private var trustedHost: String = ""

    fun trust(host: String) {
        trustedHost = host.trim().lowercase()
    }

    private val lenientFactory: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), null)
        ctx.socketFactory
    }

    fun handle(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.host != HOST) return null
        if (request.method.equals("OPTIONS", ignoreCase = true)) return reply(204, "text/plain", "")
        if (!request.method.equals("GET", ignoreCase = true) && !request.method.equals("HEAD", ignoreCase = true)) return reply(405, "text/plain", "méthode refusée")
        val target = uri.getQueryParameter("u")
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
            return reply(400, "text/plain", "adresse manquante")
        }
        return try {
            fetch(target, uri.path == "/i")
        } catch (e: SecurityException) {
            reply(403, "text/plain", "adresse refusée")
        } catch (e: Exception) {
            reply(502, "text/plain", "erreur réseau")        // pas de détails : rien de sensible dans les messages
        }
    }

    /** Refuse les adresses qui visent l'appareil lui-même ou ses services internes (boucle locale, lien local, multicast). */
    private fun guard(link: String) {
        val u = URL(link)
        val proto = u.protocol.lowercase()
        if (proto != "http" && proto != "https") throw SecurityException("protocole")
        val host = u.host
        if (host.isNullOrEmpty()) throw SecurityException("hôte")
        for (a in InetAddress.getAllByName(host)) {
            if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) throw SecurityException("adresse interne")
        }
    }

    private fun fetch(first: String, isImage: Boolean = false): WebResourceResponse {
        if (isImage) imgCache.get(first)?.let { hit ->
            return WebResourceResponse(hit.second, null, 200, "OK", mapOf("Access-Control-Allow-Origin" to LocalAssets.ORIGIN), ByteArrayInputStream(hit.first))
        }
        var link = first
        for (hop in 0 until 7) {
            guard(link)
            val conn = URL(link).openConnection() as HttpURLConnection
            if (conn is HttpsURLConnection && URL(link).host.lowercase() == trustedHost) {
                conn.sslSocketFactory = lenientFactory
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            conn.instanceFollowRedirects = false          // on suit nous-mêmes les redirections (http <-> https)
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", WEB_UA)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location != null) {
                    link = URL(URL(link), location).toString()
                    continue
                }
            }
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val type = (conn.contentType ?: "application/octet-stream").substringBefore(';').trim()
            val encoding = if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) "utf-8" else null
            val status = if (code in 100..599 && code !in 300..399) code else 502
            val headers = mapOf("Access-Control-Allow-Origin" to LocalAssets.ORIGIN, "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
            if (isImage && status == 200 && stream != null && type.startsWith("image/") && !type.contains("svg") && !type.contains("gif")) {
                val small = shrink(stream, type)      // affiche réduite : beaucoup moins de RAM dans la WebView
                if (small != null) {
                    if (small.first.size < 400_000) imgCache.put(first, small)
                    return WebResourceResponse(small.second, null, 200, "OK", headers, ByteArrayInputStream(small.first))
                }
            }
            return WebResourceResponse(type, encoding, status, reason(status), headers, stream ?: ByteArrayInputStream(ByteArray(0)))
        }
        return reply(508, "text/plain", "trop de redirections")
    }

    // petit cache en mémoire des affiches déjà réduites (6 Mo max) : évite de retélécharger celles qu'on libère hors écran
    private val imgCache = object : android.util.LruCache<String, Pair<ByteArray, String>>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Pair<ByteArray, String>): Int = value.first.size
    }

    private const val MAX_W = 400            // largeur maximale d'une affiche (suffisant pour une carte TV)
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** Réduit une image à ~400 px de large. Renvoie (octets, type) ou null si on doit laisser l'original. */
    private fun shrink(input: java.io.InputStream, type: String): Pair<ByteArray, String>? {
        return try {
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(16384); var total = 0
            while (true) { val n = input.read(chunk); if (n < 0) break; total += n; buf.write(chunk, 0, n) }
            input.close()
            val data = buf.toByteArray()
            if (data.size > MAX_BYTES) return Pair(data, type)       // image énorme : on la laisse telle quelle
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= MAX_W) return Pair(data, type)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_W) sample *= 2
            val png = type.contains("png")
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = if (png) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565 }
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return Pair(data, type)
            val out = ByteArrayOutputStream()
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 82, out)
            bmp.recycle()
            Pair(out.toByteArray(), if (png) "image/png" else "image/jpeg")
        } catch (e: Throwable) { null }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        405 -> "Method Not Allowed"
        404 -> "Not Found"
        502 -> "Bad Gateway"
        else -> "Status"
    }

    private fun reply(code: Int, type: String, body: String): WebResourceResponse {
        val headers = mapOf("Access-Control-Allow-Origin" to LocalAssets.ORIGIN, "Access-Control-Allow-Headers" to "*")
        return WebResourceResponse(type, "utf-8", code, reason(code), headers, ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
    }
}
