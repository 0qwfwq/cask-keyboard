package com.example.cask.emoji

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Inserts a chosen GIF into the focused field. Android delivers rich content to apps via
 * [InputConnectionCompat.commitContent] with a content URI, but only apps that advertise GIF support
 * (most messengers do) accept it. When the field doesn't, we fall back to copying the GIF link to the
 * clipboard so the user can paste it — so the button always does *something* useful.
 */
class GifInserter(private val context: Context) {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "cask-gif-dl").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val authority = context.packageName + ".gifs"

    fun insert(ic: InputConnection?, editorInfo: EditorInfo?, gif: GifResult) {
        if (ic == null || editorInfo == null) return
        val accepts = EditorInfoCompat.getContentMimeTypes(editorInfo)
            .any { ClipDescription.compareMimeTypes(it, MIME) }
        if (!accepts) {
            fallback(gif)
            return
        }
        io.execute {
            val file = runCatching { download(gif) }.getOrNull()
            main.post {
                if (file == null) {
                    toast("Couldn't load GIF")
                    return@post
                }
                val uri = runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull()
                if (uri == null) {
                    fallback(gif)
                    return@post
                }
                val info = InputContentInfoCompat(uri, ClipDescription("gif", arrayOf(MIME)), null)
                val ok = runCatching {
                    InputConnectionCompat.commitContent(
                        ic, editorInfo, info,
                        InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
                    )
                }.getOrDefault(false)
                if (!ok) fallback(gif)
            }
        }
    }

    private fun download(gif: GifResult): File {
        val dir = File(context.cacheDir, "gifs").apply { mkdirs() }
        val out = File(dir, (gif.id.ifBlank { System.currentTimeMillis().toString() }) + ".gif")
        val conn = (URL(gif.fullUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
        }
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        return out
    }

    private fun fallback(gif: GifResult) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("gif", gif.fullUrl))
        }
        toast("GIF link copied — paste it here")
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val MIME = "image/gif"
    }
}
