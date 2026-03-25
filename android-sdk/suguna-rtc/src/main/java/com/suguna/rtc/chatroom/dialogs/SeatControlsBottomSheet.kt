package com.suguna.rtc.chatroom.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.suguna.rtc.R
import com.suguna.rtc.chatroom.SeatParticipant

class SeatControlsBottomSheet(
    private val context: Context,
    private val seat: SeatParticipant,
    private val isHostLocal: Boolean,
    private val localUserId: String,
    private val isMutedLocal: Boolean,
    private val hostMutedUsers: List<String>,
    private val selfMutedUsers: List<String>,
    private val onMuteClick: () -> Unit,
    private val onRemoveClick: () -> Unit,
    private val onLeaveClick: () -> Unit
) {
    fun show() {
        val dialog = BottomSheetDialog(context, R.style.BottomSheetDialogTheme)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_seat_controls, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.setBackgroundResource(android.R.color.transparent)
        (bottomSheet?.parent as? View)?.setBackgroundResource(android.R.color.transparent)

        val iv = view.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivProfile)
        val tv = view.findViewById<TextView>(R.id.tvName)
        val btnMute = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMute)
        val btnRemove = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRemove)
        val btnLeave = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLeave)

        tv.text = seat.name
        if (!seat.image.isNullOrEmpty()) {
             com.bumptech.glide.Glide.with(context).load(seat.image).into(iv)
        }

        val isTargetSelf = seat.id == localUserId

        if (isHostLocal) {
            if (isTargetSelf) {
                btnMute.visibility = View.VISIBLE
                btnMute.text = if (isMutedLocal) "Unmute" else "Mute"
                btnMute.setOnClickListener {
                     onMuteClick()
                     dialog.dismiss()
                }
            } else {
                btnMute.visibility = View.VISIBLE
                btnRemove.visibility = View.VISIBLE
                
                val isTargetHostMuted = hostMutedUsers.contains(seat.id)
                val isTargetSelfMuted = selfMutedUsers.contains(seat.id)

                btnMute.text = if (isTargetHostMuted) "Unmute User" else "Mute User"
                
                if (isTargetSelfMuted) {
                    btnMute.text = "Locked by Audience"
                    btnMute.setOnClickListener {
                         android.widget.Toast.makeText(context, "Locked by Audience", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    btnMute.setOnClickListener {
                         onMuteClick()
                         dialog.dismiss()
                    }
                }
                btnRemove.setOnClickListener {
                     onRemoveClick()
                     dialog.dismiss()
                }
            }
        } else {
            if (isTargetSelf) {
                btnMute.visibility = View.VISIBLE
                btnLeave.visibility = View.VISIBLE
                btnMute.text = if (isMutedLocal) "Unmute" else "Mute"

                val isHostLocked = hostMutedUsers.contains(localUserId)
                if (isHostLocked) {
                     btnMute.text = "Locked by Host"
                     btnMute.setOnClickListener {
                         android.widget.Toast.makeText(context, "Locked by Host", android.widget.Toast.LENGTH_SHORT).show()
                     }
                } else {
                     btnMute.setOnClickListener {
                         onMuteClick()
                         dialog.dismiss()
                     }
                }
                btnLeave.setOnClickListener {
                     onLeaveClick()
                     dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
