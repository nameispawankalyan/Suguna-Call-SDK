package com.suguna.rtc.chatroom.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.suguna.rtc.R

class ChatRoomMenuBottomSheet(
    private val context: Context,
    private val isHost: Boolean,
    private val onMessengerClick: () -> Unit,
    private val onClearChatClick: () -> Unit
) {
    fun show() {
        val dialog = BottomSheetDialog(context, R.style.BottomSheetDialogTheme)
        val view = LayoutInflater.from(context).inflate(R.layout.layout_chat_room_menu, null)
        dialog.setContentView(view)
        
        // Apply corner theme compatible with transparent background
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.setBackgroundResource(android.R.color.transparent)
        (bottomSheet?.parent as? View)?.setBackgroundResource(android.R.color.transparent)

        val llMessenger = view.findViewById<LinearLayout>(R.id.llMessenger)
        val llClearChat = view.findViewById<LinearLayout>(R.id.llClearChat)

        llMessenger.setOnClickListener {
            onMessengerClick()
            dialog.dismiss()
        }

        // Only allow host to clear chat for everyone
        llClearChat.visibility = if (isHost) View.VISIBLE else View.GONE 
        
        llClearChat.setOnClickListener {
            onClearChatClick()
            dialog.dismiss()
        }
        
        dialog.show()
    }
}
