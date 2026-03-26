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
        seats.clear()
        seats.addAll(newSeats)
        notifyDataSetChanged()
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

    override fun getItemCount(): Int = seats.size

    inner class SeatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivProfile: ImageView = itemView.findViewById(R.id.ivParticipantProfile)
        private val tvName: TextView = itemView.findViewById(R.id.tvParticipantName)
        private val ivMuted: ImageView = itemView.findViewById(R.id.ivMuted)

        fun bind(seat: SeatParticipant) {
            val tvOverlayCount = itemView.findViewById<TextView>(R.id.tvOverlayCount)
            tvOverlayCount.visibility = View.GONE

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
                val density = itemView.context.resources.displayMetrics.density
                val pad = (10 * density).toInt() 
               // ivProfile.setPadding(pad, pad, pad, pad)
                ivProfile.setImageResource(R.drawable.chair_icon)
            } else {
                // Real seated participant
                tvName.text = seat.name
                ivProfile.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                ivProfile.scaleType = ImageView.ScaleType.CENTER_CROP
               // ivProfile.setPadding(0, 0, 0, 0)
                
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
