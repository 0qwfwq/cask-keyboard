package com.example.cask.emoji

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * On-device usage frequency for the picker: how often you use each emoji and each emoticon, so the
 * panel can show your most-used at the top. Persisted to a small JSON file in `filesDir`; reads on
 * the UI thread, writes debounced on a background thread. No network, no telemetry.
 *
 * Exposes your top [TOP_EMOJI] (30) emoji and top [TOP_EMOTICON] (10) emoticons.
 */
class EmojiUsageStore private constructor(private val file: File) {

    private val lock = Any()
    private val emoji = HashMap<String, Int>()
    private val emoticon = HashMap<String, Int>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "cask-emoji-io").apply { isDaemon = true } }

    fun recordEmoji(e: String) {
        if (e.isBlank()) return
        synchronized(lock) { emoji[e] = (emoji[e] ?: 0) + 1 }
        save()
    }

    fun recordEmoticon(e: String) {
        if (e.isBlank()) return
        synchronized(lock) { emoticon[e] = (emoticon[e] ?: 0) + 1 }
        save()
    }

    /** Your most-used emoji, most-frequent first (max [TOP_EMOJI]). */
    fun topEmoji(): List<String> = synchronized(lock) {
        emoji.entries.sortedByDescending { it.value }.take(TOP_EMOJI).map { it.key }
    }

    /** Your most-used emoticons, most-frequent first (max [TOP_EMOTICON]). */
    fun topEmoticons(): List<String> = synchronized(lock) {
        emoticon.entries.sortedByDescending { it.value }.take(TOP_EMOTICON).map { it.key }
    }

    private fun save() {
        val json = synchronized(lock) {
            JSONObject()
                .put("emoji", JSONObject(emoji as Map<*, *>))
                .put("emoticon", JSONObject(emoticon as Map<*, *>))
                .toString()
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
        const val TOP_EMOJI = 30
        const val TOP_EMOTICON = 10
        private const val FILE_NAME = "cask_emoji_usage.json"

        fun load(context: Context): EmojiUsageStore {
            val file = File(context.filesDir, FILE_NAME)
            val store = EmojiUsageStore(file)
            runCatching {
                if (file.exists()) {
                    val root = JSONObject(file.readText())
                    root.optJSONObject("emoji")?.let { o -> for (k in o.keys()) store.emoji[k] = o.optInt(k) }
                    root.optJSONObject("emoticon")?.let { o -> for (k in o.keys()) store.emoticon[k] = o.optInt(k) }
                }
            }
            return store
        }
    }
}
