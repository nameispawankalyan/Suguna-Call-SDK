package com.suguna.rtc.chatroom

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.suguna.rtc.R

class QuickReceiverAdapter(
    private val users: List<SeatParticipant>,
    private val roomOwnerId: String? = null,
    private var initialSelectedIds: Set<String>? = null,
    private val onSelectionChanged: (List<String>) -> Unit
) : RecyclerView.Adapter<QuickReceiverAdapter.ViewHolder>() {

    private val selectedIds = mutableSetOf<String>().apply {
        if (initialSelectedIds != null) {
            addAll(initialSelectedIds!!)
        } else {
            // Default to Host if no initial selection provided
            roomOwnerId?.let { add(it) } ?: users.getOrNull(0)?.let { add(it.id) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gift_receiver_sdk, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = users[position]
        
        Glide.with(holder.itemView.context)
            .load(user.image)
            .placeholder(R.drawable.chair_icon)
            .circleCrop()
            .into(holder.image)
            
        holder.name.text = user.name
        
        // Host Badge
        holder.hostTag?.visibility = if (user.id == roomOwnerId) View.VISIBLE else View.GONE
        
        // Selection UI
        if (selectedIds.contains(user.id)) {
            holder.selectionOverlay?.visibility = View.VISIBLE
            holder.image.strokeWidth = 3f * holder.itemView.context.resources.displayMetrics.density
            holder.image.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFD700"))
        } else {
            holder.selectionOverlay?.visibility = View.GONE
            holder.image.strokeWidth = 1.5f * holder.itemView.context.resources.displayMetrics.density
            holder.image.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#40FFFFFF"))
        }

        holder.itemView.setOnClickListener {
            if (selectedIds.contains(user.id)) {
                selectedIds.remove(user.id)
            } else {
                selectedIds.add(user.id)
            }
            notifyItemChanged(position)
            onSelectionChanged(selectedIds.toList())
        }
    }

    override fun getItemCount() = users.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: com.google.android.material.imageview.ShapeableImageView = view.findViewById(R.id.ivReceiverImage)
        val name: TextView = view.findViewById(R.id.tvReceiverName)
        val hostTag: TextView? = view.findViewById(R.id.tvHostTag)
        val selectionOverlay: View? = view.findViewById(R.id.ivSelectionOverlay)
    }
}
