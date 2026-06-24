package com.example.cask.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.cask.CaskTheme

/**
 * The emoji / GIF / emoticon picker shown when the user holds the `?123` key. Three tabs:
 *
 *  - **Emojis** — every Unicode emoji, organized by category, with your top-30 most-used on top.
 *  - **GIFs** — GIPHY trending + search (needs a key in [GiphyConfig]).
 *  - **Emoticons** — a large, searchable kaomoji list, with your top-10 most-used on top.
 *
 * A search field drives all three tabs. Tapping it asks the host (the keyboard) to reveal the keys
 * and route typing here ([onSearchChar] / [onSearchDelete]) so the user can search without leaving
 * the picker. Emoji/emoticon picks are committed as text; GIF picks are handed back for rich insert.
 */
@SuppressLint("ViewConstructor", "NotifyDataSetChanged")
class EmojiPanel(
    context: Context,
    private val theme: CaskTheme,
    private val host: Host,
) : LinearLayout(context) {

    interface Host {
        fun onPickText(text: String)
        fun onPickGif(gif: GifResult)
        fun onBackspace()
        fun onClosePicker()
        /** Ask the keyboard to show/hide its keys and route typing to this panel for searching. */
        fun onSearchActive(active: Boolean)
    }

    private enum class Tab { EMOJI, GIF, EMOTICON }

    private val emojiData = EmojiData.load(context)
    private val emoticonData = EmoticonData.load(context)
    private val usage = EmojiUsageStore.load(context)
    private val giphy = GiphyClient()

    private var tab = Tab.EMOJI
    private var query = ""
    private var searchActive = false

    private val tabButtons = LinkedHashMap<Tab, TextView>()
    private val chromeButtons = ArrayList<TextView>() // top-bar ABC / ⌫ + the search clear button
    private lateinit var searchField: TextView
    private val emojiRecycler: RecyclerView
    private val emoticonRecycler: RecyclerView
    private val gifRecycler: RecyclerView
    private val gifStatus: TextView

    private val emojiAdapter = TokenGridAdapter(theme, cellTextSize = 40f, cellHeightDp = 66) { pick(it, emoji = true) }
    private val emoticonAdapter = TokenGridAdapter(theme, cellTextSize = 14f) { pick(it, emoji = false) }
    private val gifAdapter = GifAdapter(theme, context) { host.onPickGif(it) }

    init {
        orientation = VERTICAL
        background = theme.keyboardBackground()

        addView(buildTopBar())
        addView(buildSearchRow())

        val content = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        emojiRecycler = buildGrid(spanCount = 5, adapter = emojiAdapter, isHeader = emojiAdapter::isHeader)
        emoticonRecycler = buildGrid(spanCount = 2, adapter = emoticonAdapter, isHeader = emoticonAdapter::isHeader)
        gifRecycler = buildGrid(spanCount = 2, adapter = gifAdapter, isHeader = { false })
        gifStatus = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
            typeface = theme.keyTypeface
            textSize = 14f
            setTextColor(theme.textSecondary)
            val p = theme.dp(24)
            setPadding(p, p, p, p)
        }
        content.addView(emojiRecycler)
        content.addView(emoticonRecycler)
        content.addView(gifRecycler)
        content.addView(gifStatus)
        addView(content)

        selectTab(Tab.EMOJI)
    }

    // ---- Public API used by the keyboard host ------------------------------

    /**
     * Re-apply the (possibly recolored) theme so the picker always matches the live keyboard. The
     * panel is created lazily and then cached, but the keyboard recolors per foreground app — without
     * this the cached panel would keep the colours from whenever it was first opened.
     */
    fun applyTheme() {
        background = theme.keyboardBackground()
        searchField.background = theme.pillChip()
        searchField.setTextColor(theme.textSecondary)
        gifStatus.setTextColor(theme.textSecondary)
        chromeButtons.forEach { it.setTextColor(theme.textSecondary) }
        for ((t, btn) in tabButtons) btn.setTextColor(if (t == tab) theme.accent else theme.textSecondary)
        // Grid cells bake the theme in at creation; drop the pooled views so they rebuild recolored.
        emojiRecycler.recycledViewPool.clear()
        emoticonRecycler.recycledViewPool.clear()
        gifRecycler.recycledViewPool.clear()
    }

    /** Called when the picker becomes visible (refresh the current tab). */
    fun onShown() {
        searchActive = false
        query = ""
        updateSearchField()
        refreshContent()
    }

    /**
     * Collapse the search keyboard but keep the typed query and its results on screen (used when the
     * user presses the system down-arrow while searching). Tapping the search field re-opens the keys.
     */
    fun exitSearch() {
        if (searchActive) setSearchActive(false)
    }

    /** A character was typed while searching. */
    fun onSearchChar(text: CharSequence) {
        if (!searchActive) return
        query += text
        updateSearchField()
        refreshContent()
    }

    /** Backspace while searching: trim the query, or exit search when it's empty. */
    fun onSearchDelete() {
        if (!searchActive) return
        if (query.isNotEmpty()) {
            query = query.dropLast(1)
            updateSearchField()
            refreshContent()
        } else {
            setSearchActive(false)
        }
    }

    // ---- Building blocks ---------------------------------------------------

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(40))
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(iconButton("ABC", 1.4f) { host.onClosePicker() })
        bar.addView(tabButton(Tab.EMOJI, "Emojis"))
        bar.addView(tabButton(Tab.GIF, "GIFs"))
        bar.addView(tabButton(Tab.EMOTICON, "Emoticons"))
        bar.addView(iconButton("⌫", 1.4f) { host.onBackspace() })
        return bar
    }

    private fun tabButton(which: Tab, label: String): TextView {
        val tv = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 2f)
            gravity = Gravity.CENTER
            text = label
            typeface = theme.keyTypeface
            textSize = 13f
            isClickable = true
            setOnClickListener { selectTab(which) }
        }
        tabButtons[which] = tv
        return tv
    }

    private fun iconButton(label: String, weight: Float, onClick: () -> Unit): TextView =
        TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
            gravity = Gravity.CENTER
            text = label
            typeface = theme.keyTypeface
            textSize = 15f
            setTextColor(theme.textSecondary)
            isClickable = true
            setOnClickListener { onClick() }
            chromeButtons.add(this)
        }

    private fun buildSearchRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(38))
            val m = theme.dp(6)
            setPadding(m, theme.dp(2), m, theme.dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }
        searchField = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
            typeface = theme.keyTypeface
            textSize = 14f
            setTextColor(theme.textSecondary)
            background = theme.pillChip()
            val p = theme.dp(12)
            setPadding(p, 0, p, 0)
            isClickable = true
            setOnClickListener { setSearchActive(true) }
        }
        val clear = TextView(context).apply {
            layoutParams = LayoutParams(theme.dp(40), LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
            text = "✕"
            typeface = theme.keyTypeface
            textSize = 14f
            setTextColor(theme.textSecondary)
            isClickable = true
            setOnClickListener {
                query = ""
                setSearchActive(false)
            }
            chromeButtons.add(this)
        }
        row.addView(searchField)
        row.addView(clear)
        return row
    }

    private fun buildGrid(
        spanCount: Int,
        adapter: RecyclerView.Adapter<*>,
        isHeader: (Int) -> Boolean,
    ): RecyclerView {
        return RecyclerView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            val lm = GridLayoutManager(context, spanCount)
            lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (isHeader(position)) spanCount else 1
            }
            layoutManager = lm
            this.adapter = adapter
            setHasFixedSize(true)
            // Starting to swipe through results means the user is done typing: drop the search keyboard
            // (keeping the query + results) so they can scroll the full-height grid, rather than having
            // to hunt for the down-arrow (which would otherwise close the whole picker).
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) exitSearch()
                }
            })
        }
    }

    // ---- Tabs, search, content --------------------------------------------

    private fun selectTab(which: Tab) {
        tab = which
        for ((t, btn) in tabButtons) {
            val active = t == which
            btn.setTextColor(if (active) theme.accent else theme.textSecondary)
        }
        emojiRecycler.visibility = if (which == Tab.EMOJI) VISIBLE else GONE
        emoticonRecycler.visibility = if (which == Tab.EMOTICON) VISIBLE else GONE
        val gifTab = which == Tab.GIF
        gifRecycler.visibility = GONE // refreshContent decides
        gifStatus.visibility = if (gifTab) VISIBLE else GONE
        updateSearchField()
        refreshContent()
    }

    private fun setSearchActive(active: Boolean) {
        searchActive = active
        // The query is intentionally kept when deactivating so collapsing the search keyboard still
        // shows the filtered results; the ✕ button is what clears it.
        updateSearchField()
        host.onSearchActive(active)
        refreshContent()
    }

    private fun updateSearchField() {
        searchField.text = when {
            query.isNotEmpty() -> query
            else -> when (tab) {
                Tab.EMOJI -> "Search emojis"
                Tab.GIF -> "Search GIFs"
                Tab.EMOTICON -> "Search emoticons"
            }
        }
    }

    private fun refreshContent() {
        when (tab) {
            Tab.EMOJI -> emojiAdapter.submit(emojiItems())
            Tab.EMOTICON -> emoticonAdapter.submit(emoticonItems())
            Tab.GIF -> loadGifs()
        }
    }

    private fun emojiItems(): List<TokenGridAdapter.Item> {
        val items = ArrayList<TokenGridAdapter.Item>()
        if (query.isNotBlank()) {
            emojiData.search(query).forEach { items.add(TokenGridAdapter.Item.token(it.emoji, it.emoji)) }
            return items
        }
        val recents = usage.topEmoji()
        if (recents.isNotEmpty()) {
            items.add(TokenGridAdapter.Item.header("Recently used"))
            recents.forEach { items.add(TokenGridAdapter.Item.token(it, it)) }
        }
        for (cat in emojiData.categories) {
            items.add(TokenGridAdapter.Item.header(cat.title))
            cat.items.forEach { items.add(TokenGridAdapter.Item.token(it.emoji, it.emoji)) }
        }
        return items
    }

    private fun emoticonItems(): List<TokenGridAdapter.Item> {
        val items = ArrayList<TokenGridAdapter.Item>()
        if (query.isNotBlank()) {
            emoticonData.search(query).forEach { items.add(TokenGridAdapter.Item.token(it.text, it.text)) }
            return items
        }
        val recents = usage.topEmoticons()
        if (recents.isNotEmpty()) {
            items.add(TokenGridAdapter.Item.header("Recently used"))
            recents.forEach { items.add(TokenGridAdapter.Item.token(it, it)) }
        }
        items.add(TokenGridAdapter.Item.header("All emoticons"))
        emoticonData.all.forEach { items.add(TokenGridAdapter.Item.token(it.text, it.text)) }
        return items
    }

    private fun loadGifs() {
        if (!GiphyConfig.isConfigured) {
            showGifStatus("Add a free GIPHY API key in GiphyClient.kt to enable GIFs.")
            return
        }
        showGifStatus("Loading…")
        val onResult: (List<GifResult>) -> Unit = { results ->
            if (results.isEmpty()) {
                showGifStatus("No GIFs found.")
            } else {
                gifAdapter.submit(results)
                gifStatus.visibility = GONE
                gifRecycler.visibility = VISIBLE
            }
        }
        val onError: (String) -> Unit = { err ->
            showGifStatus(if (err == "no_key") "Add a GIPHY API key in GiphyClient.kt." else "Couldn't load GIFs.")
        }
        if (query.isBlank()) giphy.trending(GIF_LIMIT, onResult, onError)
        else giphy.search(query, GIF_LIMIT, onResult, onError)
    }

    private fun showGifStatus(message: String) {
        gifRecycler.visibility = GONE
        gifStatus.visibility = VISIBLE
        gifStatus.text = message
    }

    private fun pick(value: String, emoji: Boolean) {
        if (emoji) usage.recordEmoji(value) else usage.recordEmoticon(value)
        host.onPickText(value)
    }

    private companion object {
        const val GIF_LIMIT = 24
    }
}
