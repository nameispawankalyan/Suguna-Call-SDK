package com.suguna.rtc.chatroom.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.suguna.rtc.R
import com.suguna.rtc.chatroom.SeatParticipant

class RequestsAdapter(
    private val requests: List<SeatParticipant>,
    private val onAccept: (SeatParticipant) -> Unit,
    private val onReject: (SeatParticipant) -> Unit
) : RecyclerView.Adapter<RequestsAdapter.RequestViewHolder>() {
    
    inner class RequestViewHolder(val v: View) : RecyclerView.ViewHolder(v) {
        val tvName = v.findViewById<TextView>(R.id.tvUserName)
        val btnAccept = v.findViewById<View>(R.id.btnAccept)
        val btnReject = v.findViewById<View>(R.id.btnReject)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_request_user, parent, false)
        return RequestViewHolder(v)
    }

    override fun onBindViewHolder(holder: RequestViewHolder, position: Int) {
        val req = requests[position]
        holder.tvName.text = req.name
        holder.btnAccept.setOnClickListener { onAccept(req) }
        holder.btnReject.setOnClickListener { onReject(req) }
    }

    override fun getItemCount() = requests.size
}
