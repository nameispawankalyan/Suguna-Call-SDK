package com.suguna.rtc.chatroom.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import com.suguna.rtc.R
import com.suguna.rtc.chatroom.SeatParticipant

class SeatInviteDialog(
    private val context: Context,
    private val hostName: String,
    private val hostImage: String?,
    private val onAccept: () -> Unit,
    private val onReject: () -> Unit
) {
    fun show() {
         val builder = AlertDialog.Builder(context)
         val view = LayoutInflater.from(context).inflate(R.layout.dialog_seat_invite, null)
         builder.setView(view)

         val tvMessage = view.findViewById<TextView>(R.id.tvMessage)
         val ivHostProfile = view.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivHostProfile)
         val btnAccept = view.findViewById<View>(R.id.btnAccept)
         val btnReject = view.findViewById<View>(R.id.btnReject)

         tvMessage.text = "$hostName has invited you to take a seat."

         if (!hostImage.isNullOrEmpty()) {
              Glide.with(context).load(hostImage).circleCrop().into(ivHostProfile)
         } else {
              ivHostProfile.setImageResource(android.R.color.darker_gray)
         }

         val dialog = builder.create()
         dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

         btnAccept.setOnClickListener {
              onAccept()
              dialog.dismiss()
         }
         
         btnReject.setOnClickListener {
              onReject()
              dialog.dismiss()
         }

         dialog.show()
    }
}
