package com.example.cask.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.CRC32

/**
 * On-device clipboard history for the keyboard's clipboard tool.
 *
 * Android only ever exposes the *current* primary clip, so to offer a history we snapshot the clip
 * each time it changes (and whenever the keyboard regains focus). Text is stored verbatim; images /
 * screenshots are copied into `filesDir/clipboard/` so they survive after the source app revokes the
 * original content URI. Everything stays on-device — no network, no telemetry.
 *
 * Unpinned entries expire after [RETENTION_MS] (12 hours). Pinning an entry keeps it indefinitely
 * (and surfaces it at the top); pinning is toggled by a press-and-hold in the panel. A single shared
 * instance backs both the capturing service and the displaying panel ([get]).
 *
 * Reads happen on the UI/IME thread; mutations + serialization are serialised on [lock], and saves
 * are written on a background thread.
 */
class ClipboardStore private constructor(
    private val imagesDir: File,
    private val file: File,
) {

    /**
     * One remembered clip. Exactly one of [text] / [imageFile] is set. [hash] dedupes images (and is
     * reused as the on-disk filename so identical copies don't pile up). [timestamp] / [pinned] are
     * mutable because re-copying bumps recency and the user can pin in place.
     */
    class ClipEntry(
        val id: String,
        val text: String?,
        val imageFile: File?,
        var timestamp: Long,
        var pinned: Boolean,
        val hash: String? = null,
    ) {
        val isImage: Boolean get() = imageFile != null
    }

    private val lock = Any()
    private val entries = ArrayList<ClipEntry>() // unordered pool; ordering is computed in [snapshot]
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "cask-clipboard-io").apply { isDaemon = true } }

    // ---- Capture -----------------------------------------------------------

    /** Snapshot the system's current primary clip (called on clip-change and when the keyboard shows). */
    fun captureCurrent(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        capture(context, clip)
    }

    /** Remember [clip] (best-effort: clipboard reads can be denied while we're not focused). */
    fun capture(context: Context, clip: ClipData) {
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val now = System.currentTimeMillis()

        val uri = item.uri
        val looksImage = uri != null && clip.description?.let { d ->
            (0 until d.mimeTypeCount).any { d.getMimeType(it).startsWith("image/") }
        } == true
        if (looksImage) {
            captureImage(context, uri!!, now)
            return
        }

        val text = item.coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) captureText(text, now)
    }

    private fun captureText(text: String, now: Long) {
        synchronized(lock) {
            prune()
            val existing = entries.firstOrNull { !it.isImage && it.text == text }
            if (existing != null) {
                existing.timestamp = now // re-copying an item just refreshes its recency
            } else {
                entries.add(ClipEntry(UUID.randomUUID().toString(), text, null, now, pinned = false))
                trimUnpinned()
            }
        }
        save()
    }

    private fun captureImage(context: Context, uri: Uri, now: Long) {
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull() ?: return
        if (bytes.isEmpty()) return
        val hash = hashOf(bytes)

        synchronized(lock) {
            prune()
            val existing = entries.firstOrNull { it.hash == hash }
            if (existing != null) {
                existing.timestamp = now
                save()
                return
            }
        }
        imagesDir.mkdirs()
        val f = File(imagesDir, "$hash.png")
        if (!f.exists()) runCatching { f.writeBytes(bytes) }.getOrElse { return }
        synchronized(lock) {
            entries.add(ClipEntry(UUID.randomUUID().toString(), null, f, now, pinned = false, hash = hash))
            trimUnpinned()
        }
        save()
    }

    // ---- Reads / mutations -------------------------------------------------

    /** Current history, pinned items first, each group newest-first. Expired unpinned items are pruned. */
    fun snapshot(): List<ClipEntry> = synchronized(lock) {
        prune()
        entries.sortedWith(compareByDescending<ClipEntry> { it.pinned }.thenByDescending { it.timestamp })
    }

    /** Toggle the pinned/saved state of an entry (press-and-hold in the panel). */
    fun togglePin(id: String) {
        synchronized(lock) {
            entries.firstOrNull { it.id == id }?.let { it.pinned = !it.pinned }
        }
        save()
    }

    // ---- Maintenance -------------------------------------------------------

    /** Drop unpinned entries past the retention window, deleting any backing image file. */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!e.pinned && e.timestamp < cutoff) {
                e.imageFile?.let { f -> runCatching { f.delete() } }
                it.remove()
            }
        }
    }

    /** Cap the number of kept *unpinned* entries so storage stays bounded (oldest dropped first). */
    private fun trimUnpinned() {
        val unpinned = entries.filter { !it.pinned }.sortedBy { it.timestamp }
        var excess = unpinned.size - MAX_UNPINNED
        var i = 0
        while (excess > 0 && i < unpinned.size) {
            val e = unpinned[i++]
            e.imageFile?.let { f -> runCatching { f.delete() } }
            entries.remove(e)
            excess--
        }
    }

    private fun hashOf(bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toString(16) + "_" + bytes.size
    }

    // ---- Persistence -------------------------------------------------------

    private fun save() {
        val json = synchronized(lock) {
            val arr = JSONArray()
            for (e in entries) {
                val o = JSONObject()
                    .put("id", e.id)
                    .put("ts", e.timestamp)
                    .put("pinned", e.pinned)
                if (e.isImage) {
                    o.put("type", "image").put("file", e.imageFile!!.name).put("hash", e.hash)
                } else {
                    o.put("type", "text").put("text", e.text)
                }
                arr.put(o)
            }
            JSONObject().put("entries", arr).toString()
        }
        io.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) { file.writeText(json); tmp.delete() }
            }
        }
    }

    companion object {
        /** Unpinned clips live for 12 hours. */
        const val RETENTION_MS = 12L * 60 * 60 * 1000
        private const val MAX_UNPINNED = 60
        private const val FILE_NAME = "cask_clipboard.json"
        private const val IMAGES_DIR = "clipboard"

        @Volatile
        private var instance: ClipboardStore? = null

        /** Process-wide singleton so the capturing service and the panel share one in-memory history. */
        fun get(context: Context): ClipboardStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: load(context.applicationContext).also { instance = it }
            }
        }

        private fun load(context: Context): ClipboardStore {
            val imagesDir = File(context.filesDir, IMAGES_DIR)
            val file = File(context.filesDir, FILE_NAME)
            val store = ClipboardStore(imagesDir, file)
            runCatching {
                if (file.exists()) {
                    val arr = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id", UUID.randomUUID().toString())
                        val ts = o.optLong("ts", System.currentTimeMillis())
                        val pinned = o.optBoolean("pinned", false)
                        if (o.optString("type") == "image") {
                            val f = File(imagesDir, o.optString("file"))
                            if (f.exists()) {
                                store.entries.add(ClipEntry(id, null, f, ts, pinned, o.optString("hash").ifEmpty { null }))
                            }
                        } else {
                            val text = o.optString("text")
                            if (text.isNotEmpty()) store.entries.add(ClipEntry(id, text, null, ts, pinned))
                        }
                    }
                }
            }
            synchronized(store.lock) { store.prune() }
            return store
        }
    }
}
