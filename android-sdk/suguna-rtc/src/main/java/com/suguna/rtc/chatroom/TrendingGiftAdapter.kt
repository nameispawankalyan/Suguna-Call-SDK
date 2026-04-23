package com.suguna.rtc.chatroom

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.airbnb.lottie.LottieAnimationView
import com.suguna.rtc.R

class TrendingGiftAdapter(
    private val gifts: List<GiftModel>,
    private val onGiftClick: (GiftModel) -> Unit
) : RecyclerView.Adapter<TrendingGiftAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trending_gift_small, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val gift = gifts[position]
        holder.price.text = gift.price.toString()
        
        val url = gift.image
        if (gift.type == "Lottie" || url.endsWith(".json", true) || url.endsWith(".lottie", true)) {
            holder.image.setAnimationFromUrl(url)
            holder.image.playAnimation()
        } else {
            Glide.with(holder.itemView.context).load(url).into(holder.image)
        }
        
        holder.itemView.setOnClickListener { onGiftClick(gift) }
    }

    override fun getItemCount() = gifts.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: LottieAnimationView = view.findViewById(R.id.ivGiftImage)
        val price: TextView = view.findViewById(R.id.tvGiftPrice)
    }
}
