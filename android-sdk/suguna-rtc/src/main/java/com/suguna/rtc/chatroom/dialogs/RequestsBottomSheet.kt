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
import com.suguna.rtc.chatroom.adapters.RequestsAdapter

class RequestsBottomSheet(
    private val context: Context,
    private val requests: List<SeatParticipant>,
    private val onAccept: (SeatParticipant) -> Unit,
    private val onReject: (SeatParticipant) -> Unit
) {
    fun show() {
        val dialog = BottomSheetDialog(context, R.style.BottomSheetDialogTheme)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_requests_list, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.setBackgroundResource(android.R.color.transparent)
        (bottomSheet?.parent as? View)?.setBackgroundResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.tvTitle)?.text = "Seat Requests"

        val rv = view.findViewById<RecyclerView>(R.id.rvRequests)
        rv.layoutManager = LinearLayoutManager(context)

        val adapter = RequestsAdapter(requests, 
            onAccept = { req: SeatParticipant ->
                 dialog.dismiss()
                 onAccept(req)
            },
            onReject = { req: SeatParticipant ->
                 dialog.dismiss()
                 onReject(req)
            }
        )
        rv.adapter = adapter
        dialog.show()
    }
}
