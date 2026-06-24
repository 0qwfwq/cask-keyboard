package com.example.cask.emoji

import android.content.Context
import android.os.Build
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.cask.CaskTheme

/**
 * Grid of animated GIF previews (GIPHY results) loaded with Coil. We build a dedicated [ImageLoader]
 * with the GIF decoder so the previews actually animate. Tapping a cell hands the [GifResult] back to
 * be inserted into the text field.
 */
class GifAdapter(
    private val theme: CaskTheme,
    context: Context,
    private val onClick: (GifResult) -> Unit,
) : RecyclerView.Adapter<GifAdapter.VH>() {

    private val loader: ImageLoader = ImageLoader.Builder(context)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory())
            else add(GifDecoder.Factory())
        }
        .build()

    private var items: List<GifResult> = emptyList()

    fun submit(list: List<GifResult>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val img = ImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(110)).apply {
                val m = theme.dp(3)
                setMargins(m, m, m, m)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = true
        }
        return VH(img)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val gif = items[position]
        val request = ImageRequest.Builder(holder.img.context)
            .data(gif.previewUrl)
            .target(holder.img)
            .crossfade(true)
            .build()
        loader.enqueue(request)
        holder.img.setOnClickListener { onClick(gif) }
    }

    class VH(val img: ImageView) : RecyclerView.ViewHolder(img)
}
