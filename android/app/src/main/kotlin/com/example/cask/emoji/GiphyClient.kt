package com.example.cask.emoji

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** One GIF: a small [previewUrl] for the grid and the full [fullUrl] used when inserting. */
data class GifResult(val id: String, val previewUrl: String, val fullUrl: String)

/**
 * Paste your **free** GIPHY API key here to enable the GIFs tab. Get one in a couple of minutes:
 * https://developers.giphy.com/dashboard/ → "Create an App" → choose **API** (not SDK) → copy the
 * key. (We use GIPHY because Tenor stopped accepting new API clients in Jan 2026.) Leave it blank and
 * the GIFs tab shows a "set up your key" message; emojis and emoticons work regardless.
 */
object GiphyConfig {
    const val API_KEY = "ewYSkbVJx7YmdVDK0hoSaBxKk1IQm24O"

    /** Content rating ceiling for results (g, pg, pg-13, r). */
    const val RATING = "pg-13"
    val isConfigured: Boolean get() = API_KEY.isNotBlank()
}

/**
 * Minimal GIPHY v1 client (trending + search) over [HttpURLConnection] + org.json — no networking
 * library needed. Requests run on a small background pool; callbacks are delivered on the main
 * thread. All calls are no-ops returning an error if [GiphyConfig] has no key.
 */
class GiphyClient {

    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "cask-giphy").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun trending(limit: Int, onResult: (List<GifResult>) -> Unit, onError: (String) -> Unit) {
        request(buildUrl("trending", null, limit), onResult, onError)
    }

    fun search(query: String, limit: Int, onResult: (List<GifResult>) -> Unit, onError: (String) -> Unit) {
        request(buildUrl("search", query, limit), onResult, onError)
    }

    private fun buildUrl(endpoint: String, query: String?, limit: Int): String {
        val q = if (query != null) "&q=" + URLEncoder.encode(query, "UTF-8") else ""
        return "https://api.giphy.com/v1/gifs/$endpoint?api_key=${GiphyConfig.API_KEY}" +
            "$q&limit=$limit&rating=${GiphyConfig.RATING}&bundle=messaging_non_clips"
    }

    private fun request(url: String, onResult: (List<GifResult>) -> Unit, onError: (String) -> Unit) {
        if (!GiphyConfig.isConfigured) {
            main.post { onError("no_key") }
            return
        }
        io.execute {
            val outcome = runCatching { fetch(url) }
            main.post {
                outcome.onSuccess(onResult).onFailure { onError(it.message ?: "network error") }
            }
        }
    }

    private fun fetch(url: String): List<GifResult> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): List<GifResult> {
        val results = ArrayList<GifResult>()
        val arr = JSONObject(body).optJSONArray("data") ?: return results
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val images = obj.optJSONObject("images") ?: continue
            // A light, fixed-width still/animated for the grid; a reasonably-sized GIF for inserting.
            val preview = url(images, "fixed_width") ?: url(images, "preview_gif") ?: continue
            val full = url(images, "downsized") ?: url(images, "original") ?: preview
            results.add(GifResult(obj.optString("id"), preview, full))
        }
        return results
    }

    /** Non-blank `url` of a named GIPHY rendition, or null. */
    private fun url(images: JSONObject, name: String): String? =
        images.optJSONObject(name)?.optString("url")?.takeIf { it.isNotBlank() }
}
