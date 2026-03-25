package com.suguna.rtc.chatroom.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.suguna.rtc.R
import com.suguna.rtc.chatroom.SeatParticipant
import com.suguna.rtc.chatroom.adapters.OnlineUsersAdapter

class OnlineUsersBottomSheet(
    private val context: Context,
    private val listParticipants: List<SeatParticipant>,
    private val isHostLocal: Boolean,
    private val localUserId: String,
    private val localName: String,
    private val localImage: String,
    private val roomOwnerId: String,
    private val seatedUsers: Map<Int, SeatParticipant>,
    private val onInviteSent: (SeatParticipant) -> Unit
) {
    fun show() {
        val dialog = BottomSheetDialog(context, R.style.BottomSheetDialogTheme)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_requests_list, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.setBackgroundResource(android.R.color.transparent)
        (bottomSheet?.parent as? View)?.setBackgroundResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.tvTitle)?.text = "Online Users"

        val rv = view.findViewById<RecyclerView>(R.id.rvRequests)
        rv.layoutManager = LinearLayoutManager(context)

        val fullList = listParticipants.toMutableList()
        if (!fullList.any { it.id == localUserId }) {
             fullList.add(SeatParticipant(localUserId, localName, localImage, isHost = isHostLocal))
        }

        val sortedList = fullList.filter { !it.id.startsWith("id_") }.sortedByDescending { target ->
             seatedUsers.values.any { it.id == target.id } || target.id == roomOwnerId
        }

        val adapter = OnlineUsersAdapter(context, sortedList, isHostLocal, localUserId, seatedUsers) { targetUser: SeatParticipant ->
             dialog.dismiss()
             onInviteSent(targetUser)
        }
        rv.adapter = adapter
        dialog.show()
    }
}
