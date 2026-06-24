package com.example.cask.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.cask.CaskTheme

/**
 * Grid of clipboard-history cards. Each card shows a text preview or an image thumbnail; a small
 * "📌" badge marks pinned (saved) entries. A tap pastes the entry; a press-and-hold toggles its
 * pinned state ([onPin]) — exactly as the panel advertises in its hint.
 */
@SuppressLint("NotifyDataSetChanged", "ClickableViewAccessibility")
class ClipboardAdapter(
    private val theme: CaskTheme,
    context: Context,
    private val onPick: (ClipboardStore.ClipEntry) -> Unit,
    private val onPin: (ClipboardStore.ClipEntry) -> Unit,
) : RecyclerView.Adapter<ClipboardAdapter.VH>() {

    private val loader = ImageLoader.Builder(context).build()
    private var items: List<ClipboardStore.ClipEntry> = emptyList()

    fun submit(list: List<ClipboardStore.ClipEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(118)).apply {
                val m = theme.dp(4)
                setMargins(m, m, m, m)
            }
            isClickable = true
            isLongClickable = true
        }
        val image = ImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val preview = TextView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            typeface = theme.keyTypeface
            textSize = 13f
            setTextColor(theme.textPrimary)
            // Cap at the number of lines that actually fit the card height so long text ellipsizes
            // cleanly ("…") instead of being clipped mid-line at the bottom edge.
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            val p = theme.dp(10)
            setPadding(p, p, p, p)
        }
        val badge = TextView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
            ).apply { val m = theme.dp(4); setMargins(0, m, m, 0) }
            text = "📌"
            textSize = 12f
        }
        card.addView(image)
        card.addView(preview)
        card.addView(badge)
        return VH(card, image, preview, badge)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        // Set on bind (not create) so a re-themed panel recolours recycled cards too.
        holder.itemView.background = theme.pillChip()
        if (entry.isImage) {
            holder.text.visibility = android.view.View.GONE
            holder.image.visibility = android.view.View.VISIBLE
            loader.enqueue(ImageRequest.Builder(holder.image.context).data(entry.imageFile).target(holder.image).build())
        } else {
            holder.image.visibility = android.view.View.GONE
            holder.text.visibility = android.view.View.VISIBLE
            holder.text.text = entry.text
        }
        holder.badge.visibility = if (entry.pinned) android.view.View.VISIBLE else android.view.View.GONE
        holder.itemView.setOnClickListener { onPick(entry) }
        holder.itemView.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onPin(entry)
            true
        }
    }

    class VH(card: FrameLayout, val image: ImageView, val text: TextView, val badge: TextView) :
        RecyclerView.ViewHolder(card)
}
