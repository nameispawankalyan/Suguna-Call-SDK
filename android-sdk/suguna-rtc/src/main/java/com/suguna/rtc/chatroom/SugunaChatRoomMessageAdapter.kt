package com.suguna.rtc.chatroom

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.suguna.rtc.R

class SugunaChatRoomMessageAdapter : RecyclerView.Adapter<SugunaChatRoomMessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun clearMessages() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_room_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivProfile: ImageView = itemView.findViewById(R.id.ivSenderProfile)
        private val tvName: TextView = itemView.findViewById(R.id.tvSenderName)
        private val tvTime: TextView = itemView.findViewById(R.id.tvMessageTime)
        private val tvBody: TextView = itemView.findViewById(R.id.tvMessageBody)

        fun bind(msg: ChatMessage) {
            tvName.text = msg.name
            tvTime.text = msg.timestamp
            tvBody.text = msg.message

            if (msg.image.isNotEmpty()) {
                Glide.with(itemView.context)
                    .load(msg.image)
                    .placeholder(R.drawable.ic_default_avatar)
                    .into(ivProfile)
            } else {
                ivProfile.setImageResource(R.drawable.ic_default_avatar)
            }
        }
    }
}
