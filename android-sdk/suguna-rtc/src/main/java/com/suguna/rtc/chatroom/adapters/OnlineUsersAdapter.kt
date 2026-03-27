package com.suguna.rtc.chatroom.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.suguna.rtc.R
import com.suguna.rtc.chatroom.SeatParticipant

class OnlineUsersAdapter(
    private val context: Context,
    private val users: List<SeatParticipant>,
    private val isHostLocal: Boolean,
    private val localUserId: String,
    private val seatedUsers: Map<Int, SeatParticipant>,
    private val isBlockMode: Boolean = false,
    private val invitedUserIds: Set<String> = emptySet(),
    private val onInvite: (SeatParticipant) -> Unit,
    private val onBlock: (SeatParticipant) -> Unit
) : RecyclerView.Adapter<OnlineUsersAdapter.OnlineViewHolder>() {

    inner class OnlineViewHolder(val v: View) : RecyclerView.ViewHolder(v) {
        val tvName = v.findViewById<TextView>(R.id.tvUserName)
        val ivProfile = v.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivProfile)
        val btnAccept = v.findViewById<android.view.View>(R.id.btnAccept)
        val btnReject = v.findViewById<android.view.View>(R.id.btnReject)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OnlineViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_request_user, parent, false)
        return OnlineViewHolder(v)
    }

    override fun onBindViewHolder(holder: OnlineViewHolder, position: Int) {
        val user = users[position]
        holder.tvName.text = user.name

        if (!user.image.isNullOrEmpty()) {
             com.bumptech.glide.Glide.with(context).load(user.image).into(holder.ivProfile)
        } else {
             holder.ivProfile.setImageResource(android.R.color.darker_gray)
        }

        if (isBlockMode) {
            holder.btnAccept.visibility = View.GONE
            holder.btnReject.visibility = View.VISIBLE
            (holder.btnReject as? com.google.android.material.button.MaterialButton)?.text = "UNBLOCK"
            holder.btnReject.setOnClickListener { onBlock(user) }
            return
        }

        val isSeated = seatedUsers.values.any { it.id == user.id }

        if (isHostLocal && !isSeated && user.id != localUserId) {
            holder.btnAccept.visibility = View.VISIBLE
            val btnAcc = holder.btnAccept as? com.google.android.material.button.MaterialButton
            if (invitedUserIds.contains(user.id)) {
                btnAcc?.text = "Invited"
                btnAcc?.isEnabled = false
                btnAcc?.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GRAY)
            } else {
                btnAcc?.text = "Invite"
                btnAcc?.isEnabled = true
                btnAcc?.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#388E3C")) // Green
            }
            holder.btnAccept.setOnClickListener { 
                if (!invitedUserIds.contains(user.id)) {
                    onInvite(user) 
                }
            }
        } else {
            holder.btnAccept.visibility = View.GONE
        }

        if (isHostLocal && user.id != localUserId) {
            holder.btnReject.visibility = View.VISIBLE
            (holder.btnReject as? com.google.android.material.button.MaterialButton)?.text = "Block"
            holder.btnReject.setOnClickListener {
                onBlock(user)
            }
        } else {
            holder.btnReject.visibility = View.GONE
        }
    }

    override fun getItemCount() = users.size
}
