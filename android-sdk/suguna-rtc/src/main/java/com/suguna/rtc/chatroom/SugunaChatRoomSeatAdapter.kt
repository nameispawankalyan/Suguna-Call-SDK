package com.suguna.rtc.chatroom

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.suguna.rtc.R

class SugunaChatRoomSeatAdapter : RecyclerView.Adapter<SugunaChatRoomSeatAdapter.SeatViewHolder>() {

    private val seats = mutableListOf<SeatParticipant>()

    fun setSeats(newSeats: List<SeatParticipant>) {
        val updatedNewSeats = newSeats.map { newSeat ->
            val oldSeat = seats.find { it.id == newSeat.id }
            if (oldSeat != null && oldSeat.reactionUrl != null) {
                newSeat.copy(reactionUrl = oldSeat.reactionUrl, reactionType = oldSeat.reactionType)
            } else {
                newSeat
            }
        }
        
        val diffCallback = object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize(): Int = seats.size
            override fun getNewListSize(): Int = updatedNewSeats.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                return seats[oldPos].id == updatedNewSeats[newPos].id
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = seats[oldPos]
                val new = updatedNewSeats[newPos]
                // Full visual compare including speaking and reactions
                return old.id == new.id && old.name == new.name && old.image == new.image && 
                       old.isMuted == new.isMuted && old.isHost == new.isHost &&
                       old.isSpeaking == new.isSpeaking && 
                       old.reactionUrl == new.reactionUrl
            }
        }
        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(diffCallback)
        seats.clear()
        seats.addAll(updatedNewSeats)
        diffResult.dispatchUpdatesTo(this)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val activeRunnables = mutableMapOf<String, Runnable>()

    fun playReaction(userId: String, url: String, type: String) {
        val position = seats.indexOfFirst { it.id == userId }
        if (position != -1) {
            // Cancel existing clear-out runnable if any
            activeRunnables[userId]?.let { handler.removeCallbacks(it) }

            val updated = seats[position].copy(reactionUrl = url, reactionType = type)
            seats[position] = updated
            notifyItemChanged(position, "REACTION_UPDATE")

            val runnable = Runnable {
                val currentPos = seats.indexOfFirst { it.id == userId }
                if (currentPos != -1) {
                    val cleared = seats[currentPos].copy(reactionUrl = null, reactionType = null)
                    seats[currentPos] = cleared
                    notifyItemChanged(currentPos, "REACTION_CLEAR")
                }
                activeRunnables.remove(userId)
            }
            activeRunnables[userId] = runnable
            handler.postDelayed(runnable, 3000)
        }
    }

    fun isReactionActive(userId: String): Boolean {
        return activeRunnables.containsKey(userId)
    }

    fun updateSpeakingStates(speakerIds: List<String>) {
        for (i in seats.indices) {
            val seat = seats[i]
            val isSpeaking = speakerIds.contains(seat.id)
            if (seat.isSpeaking != isSpeaking) {
                seats[i] = seat.copy(isSpeaking = isSpeaking)
                notifyItemChanged(i, "SPEAKING_UPDATE")
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_room_seat, parent, false)
        return SeatViewHolder(view)
    }

    interface OnSeatClickListener {
        fun onSeatClick(position: Int, seat: SeatParticipant)
    }

    private var clickListener: OnSeatClickListener? = null

    fun setOnSeatClickListener(listener: OnSeatClickListener) {
        this.clickListener = listener
    }

    override fun onBindViewHolder(holder: SeatViewHolder, position: Int) {
        val seat = seats[position]
        holder.bind(seat)
        holder.itemView.setOnClickListener {
            clickListener?.onSeatClick(position, seat)
        }
    }

    override fun onBindViewHolder(holder: SeatViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val seat = seats[position]
            // Only update specific parts requested by payload
            if (payloads.contains("SPEAKING_UPDATE")) {
                holder.updateSpeaking(seat.isSpeaking)
            }
            if (payloads.contains("REACTION_UPDATE") || payloads.contains("REACTION_CLEAR")) {
                holder.bindReaction(seat.reactionUrl, seat.reactionType)
            }
        }
    }

    override fun getItemCount(): Int = seats.size

    inner class SeatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivProfile: ImageView = itemView.findViewById(R.id.ivParticipantProfile)
        private val tvName: TextView = itemView.findViewById(R.id.tvParticipantName)
        private val ivMuted: ImageView = itemView.findViewById(R.id.ivMuted)
        private val lottieReaction: com.airbnb.lottie.LottieAnimationView = itemView.findViewById(R.id.lottieReaction)
        private val ivReactionEmoji: ImageView = itemView.findViewById(R.id.ivReactionEmoji)

        fun bind(seat: SeatParticipant) {
            val tvOverlayCount = itemView.findViewById<TextView>(R.id.tvOverlayCount)
            tvOverlayCount.visibility = View.GONE
            
            // Note: bindReaction below handles initial visibility based on model state
            // to prevent flickering when isSpeaking re-draws the holder.

            if (seat.id == "request_seat") {
                tvName.text = "Requests"
                val countNum = seat.name.replace("Requests (", "").replace(")", "")
                tvOverlayCount.text = countNum
                tvOverlayCount.visibility = View.VISIBLE
                ivProfile.setImageResource(0) // Hide chair
                ivProfile.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50")) // A Green circle
            } else if (seat.id.startsWith("audience_request_")) {
                tvName.text = seat.name
                val isSent = seat.image == "SENT"
                tvOverlayCount.text = if (isSent) "✓" else "+"
                tvOverlayCount.visibility = View.VISIBLE
                ivProfile.setImageResource(0)
                if (isSent) {
                    ivProfile.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                } else {
                    ivProfile.setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
                }
            } else if (seat.id.startsWith("id_")) {
                // Empty Seat Placeholder
                tvName.text = seat.name
                ivProfile.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                ivProfile.scaleType = ImageView.ScaleType.CENTER_INSIDE
                ivProfile.setImageResource(R.drawable.chair_icon)
            } else {
                // Real seated participant
                tvName.text = seat.name
                ivProfile.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                ivProfile.scaleType = ImageView.ScaleType.CENTER_CROP
                
                if (seat.image.isNotEmpty()) {
                    Glide.with(itemView.context)
                        .load(seat.image)
                        .placeholder(R.drawable.ic_default_avatar)
                        .into(ivProfile)
                } else {
                    ivProfile.setImageResource(R.drawable.ic_default_avatar)
                }
            }

            ivMuted.visibility = if (seat.isMuted) View.VISIBLE else View.INVISIBLE
            val viewRipple1 = itemView.findViewById<View>(R.id.viewRipple1)
            val viewRipple2 = itemView.findViewById<View>(R.id.viewRipple2)
            val viewRipple3 = itemView.findViewById<View>(R.id.viewRipple3)

            if (seat.isSpeaking) {
                startRippleAnimation(viewRipple1, viewRipple2, viewRipple3, itemView.context)
            } else {
                stopRippleAnimation(viewRipple1, viewRipple2, viewRipple3)
            }
            
            bindReaction(seat.reactionUrl, seat.reactionType)
        }

        fun updateSpeaking(isSpeaking: Boolean) {
            val viewRipple1 = itemView.findViewById<View>(R.id.viewRipple1)
            val viewRipple2 = itemView.findViewById<View>(R.id.viewRipple2)
            val viewRipple3 = itemView.findViewById<View>(R.id.viewRipple3)
            if (isSpeaking) {
                startRippleAnimation(viewRipple1, viewRipple2, viewRipple3, itemView.context)
            } else {
                stopRippleAnimation(viewRipple1, viewRipple2, viewRipple3)
            }
        }

        fun bindReaction(url: String?, type: String?) {
            if (url == null || type == null) {
                ivReactionEmoji.visibility = View.GONE
                lottieReaction.visibility = View.GONE
                ivReactionEmoji.setTag(null)
                lottieReaction.setTag(null)
                return
            }

            if (type.equals("Emoji", ignoreCase = true)) {
                lottieReaction.visibility = View.GONE
                if (ivReactionEmoji.getTag() == url) {
                    ivReactionEmoji.visibility = View.VISIBLE
                    return
                }
                
                ivReactionEmoji.visibility = View.VISIBLE
                ivReactionEmoji.setTag(url)
                com.bumptech.glide.Glide.with(itemView.context).load(url).into(ivReactionEmoji)
                ivEmojiAnimation(ivReactionEmoji)
            } else {
                ivReactionEmoji.visibility = View.GONE
                if (lottieReaction.getTag() == url) {
                    lottieReaction.visibility = View.VISIBLE
                    return
                }
                
                lottieReaction.visibility = View.VISIBLE
                lottieReaction.setTag(url)
                lottieReaction.cancelAnimation()
                lottieReaction.setAnimationFromUrl(url)
                lottieReaction.playAnimation()
            }
        }
        
        private fun ivEmojiAnimation(ivEmoji: ImageView) {
            ivEmoji.scaleX = 0.5f 
            ivEmoji.scaleY = 0.5f
            ivEmoji.animate().scaleX(1.1f).scaleY(1.1f).setDuration(400).withEndAction {
                ivEmoji.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).start()
            }
        }
        private fun startRippleAnimation(v1: View, v2: View, v3: View, context: android.content.Context) {
            if (v1.visibility == View.VISIBLE) return // Already animating

            v1.visibility = View.VISIBLE
            v2.visibility = View.VISIBLE
            v3.visibility = View.VISIBLE

            val pkg = context.packageName
            val id = context.resources.getIdentifier("ripple_pulse", "anim", pkg)
            if (id == 0) return

            val anim1 = android.view.animation.AnimationUtils.loadAnimation(context, id)
            val anim2 = android.view.animation.AnimationUtils.loadAnimation(context, id)
            val anim3 = android.view.animation.AnimationUtils.loadAnimation(context, id)

            anim2.startOffset = 300
            anim3.startOffset = 600

            v1.startAnimation(anim1)
            v2.startAnimation(anim2)
            v3.startAnimation(anim3)
        }

        private fun stopRippleAnimation(v1: View, v2: View, v3: View) {
            if (v1.visibility == View.INVISIBLE) return

            v1.clearAnimation()
            v2.clearAnimation()
            v3.clearAnimation()

            v1.visibility = View.INVISIBLE
            v2.visibility = View.INVISIBLE
            v3.visibility = View.INVISIBLE
        }
    }

    companion object {
        const val TYPE_HOST = 0
        const val TYPE_SPEAKER = 1
    }
}
