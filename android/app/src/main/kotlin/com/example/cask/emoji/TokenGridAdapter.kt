package com.example.cask.emoji

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.cask.CaskTheme

/**
 * A grid adapter shared by the emoji and emoticon tabs. The list mixes full-width section [Item]
 * headers (e.g. "Recently used", "Smileys & Emotion") with tappable token cells. The hosting
 * [androidx.recyclerview.widget.GridLayoutManager] uses [isHeader] to make headers span the row.
 */
class TokenGridAdapter(
    private val theme: CaskTheme,
    private val cellTextSize: Float,
    private val cellHeightDp: Int = 46,
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class Item private constructor(val isHeader: Boolean, val display: String, val value: String) {
        companion object {
            fun header(title: String) = Item(true, title, "")
            fun token(display: String, value: String) = Item(false, display, value)
        }
    }

    private var items: List<Item> = emptyList()

    fun submit(list: List<Item>) {
        items = list
        notifyDataSetChanged()
    }

    fun isHeader(position: Int): Boolean = position in items.indices && items[position].isHeader

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = if (items[position].isHeader) TYPE_HEADER else TYPE_TOKEN

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return if (viewType == TYPE_HEADER) HeaderVH(makeHeader(ctx)) else TokenVH(makeToken(ctx))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is HeaderVH -> holder.tv.text = item.display
            is TokenVH -> {
                holder.tv.text = item.display
                holder.tv.setOnClickListener { onClick(item.value) }
            }
        }
    }

    private fun makeHeader(ctx: Context): TextView = TextView(ctx).apply {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        text = ""
        textSize = 12f
        typeface = theme.keyTypeface
        setTextColor(theme.textSecondary)
        val h = theme.dp(10)
        val v = theme.dp(6)
        setPadding(h, v, h, theme.dp(2))
    }

    private fun makeToken(ctx: Context): TextView = TextView(ctx).apply {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(cellHeightDp))
        gravity = Gravity.CENTER
        textSize = cellTextSize
        typeface = theme.keyTypeface
        setTextColor(theme.textPrimary)
        isClickable = true
        val tv = TypedValue()
        if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            setBackgroundResource(tv.resourceId)
        }
    }

    private class HeaderVH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    private class TokenVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_TOKEN = 1
    }
}
