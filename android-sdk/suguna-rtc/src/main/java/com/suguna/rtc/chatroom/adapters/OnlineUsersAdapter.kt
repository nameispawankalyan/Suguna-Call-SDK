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
    private val onInvite: (SeatParticipant) -> Unit
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

        val isSeated = seatedUsers.values.any { it.id == user.id }

        if (isHostLocal && !isSeated && user.id != localUserId) {
            holder.btnAccept.visibility = View.VISIBLE
            (holder.btnAccept as? com.google.android.material.button.MaterialButton)?.text = "Invite"
            holder.btnAccept.setOnClickListener { onInvite(user) }
        } else {
            holder.btnAccept.visibility = View.GONE
        }

        if (isHostLocal && user.id != localUserId) {
            holder.btnReject.visibility = View.VISIBLE
            (holder.btnReject as? com.google.android.material.button.MaterialButton)?.text = "Block"
            holder.btnReject.setOnClickListener {
                Toast.makeText(context, "Coming Soon!", Toast.LENGTH_SHORT).show()
            }
        } else {
            holder.btnReject.visibility = View.GONE
        }
    }

    override fun getItemCount() = users.size
}
