package com.example.cask.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.cask.CaskTheme
import java.io.File

/**
 * The clipboard tool's panel, shown when the clipboard icon in the suggestion strip is tapped. It
 * lists what you've copied in the last 12 hours (text, images and screenshots), with pinned/saved
 * items first. Tapping an item pastes it; pressing and holding pins it (or unpins a pinned one).
 *
 * Mirrors the emoji picker's host pattern: the keyboard hides its keys + strip while this is visible,
 * lets the panel's [RecyclerView] handle its own touches, and applies picks through the [Host].
 */
@SuppressLint("ViewConstructor")
class ClipboardPanel(
    context: Context,
    private val theme: CaskTheme,
    private val host: Host,
) : LinearLayout(context) {

    interface Host {
        fun onPickText(text: String)
        fun onPickImage(file: File)
        fun onClosePicker()
    }

    private val store = ClipboardStore.get(context)
    private val content: FrameLayout
    private val recycler: RecyclerView
    private val emptyView: TextView
    private val chromeViews = ArrayList<TextView>() // top-bar title + buttons, re-coloured by applyTheme

    private val adapter = ClipboardAdapter(
        theme, context,
        onPick = { entry ->
            if (entry.isImage) entry.imageFile?.let { host.onPickImage(it) } else entry.text?.let { host.onPickText(it) }
        },
        onPin = { entry ->
            store.togglePin(entry.id)
            refresh()
        },
    )

    init {
        orientation = VERTICAL
        background = theme.keyboardBackground()

        addView(buildTopBar())

        content = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        recycler = RecyclerView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            layoutManager = GridLayoutManager(context, 2)
            adapter = this@ClipboardPanel.adapter
            setHasFixedSize(true)
        }
        emptyView = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
            typeface = theme.keyTypeface
            textSize = 14f
            setTextColor(theme.textSecondary)
            val p = theme.dp(24)
            setPadding(p, p, p, p)
            text = "Things you copy (text, images, screenshots) show up here for 12 hours.\n\nTap to paste · press and hold to pin."
        }
        content.addView(recycler)
        content.addView(emptyView)
        addView(content)
    }

    /**
     * Re-apply the (possibly recolored) theme so the panel always matches the live keyboard. The panel
     * is cached after its first open, but the keyboard recolors per foreground app — without this the
     * cached panel would keep the colours from whenever it was first opened.
     */
    fun applyTheme() {
        background = theme.keyboardBackground()
        emptyView.setTextColor(theme.textSecondary)
        chromeViews.forEach { it.setTextColor(theme.textSecondary) }
        // Cards bake the theme in at creation; drop the pooled views so they rebuild recolored.
        recycler.recycledViewPool.clear()
    }

    /** Refresh the list each time the panel becomes visible. */
    fun onShown() {
        // Make sure whatever is on the clipboard right now is captured before we render.
        store.captureCurrent(context)
        refresh()
    }

    private fun refresh() {
        val items = store.snapshot()
        adapter.submit(items)
        val empty = items.isEmpty()
        emptyView.visibility = if (empty) VISIBLE else GONE
        recycler.visibility = if (empty) GONE else VISIBLE
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(40))
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(iconButton("ABC", 1.4f) { host.onClosePicker() })
        bar.addView(TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 4f)
            gravity = Gravity.CENTER
            text = "Clipboard"
            typeface = theme.keyTypeface
            textSize = 14f
            setTextColor(theme.textSecondary)
            chromeViews.add(this)
        })
        bar.addView(iconButton("", 1.4f) {}) // right spacer, keeps the title centred
        return bar
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
            chromeViews.add(this)
        }
}
