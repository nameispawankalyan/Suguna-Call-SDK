package com.suguna.rtc.chatroom.dialogs

import android.content.Context

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private val roomId: String,
    private val roomOwnerName: String,
    private val invitedUserIds: Set<String>,
    private val onInviteSent: (SeatParticipant) -> Unit,
    private val onBlockUser: (SeatParticipant) -> Unit
) {
    private var isBlockView = false
    private val blockList = mutableListOf<SeatParticipant>()

    private fun showConfirmDialog(title: String, message: String, isDestructive: Boolean, onConfirm: () -> Unit) {
        val builder = AlertDialog.Builder(context)
        val confirmView = LayoutInflater.from(context).inflate(R.layout.dialog_confirm_action, null)
        builder.setView(confirmView)
        
        val tvConfirmTitle = confirmView.findViewById<TextView>(R.id.tvConfirmTitle)
        val tvConfirmMessage = confirmView.findViewById<TextView>(R.id.tvConfirmMessage)
        val btnCancel = confirmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)
        val btnConfirm = confirmView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnConfirm)
        val ivIcon = confirmView.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivIcon)
        
        tvConfirmTitle.text = title
        tvConfirmMessage.text = message
        btnConfirm.text = if (isDestructive) "Block" else "Unblock"
        btnConfirm.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (isDestructive) "#E53935" else "#43A047") // Red vs Green
        )
        ivIcon.setColorFilter(android.graphics.Color.parseColor(if (isDestructive) "#E53935" else "#43A047"))
        ivIcon.setImageResource(if (isDestructive) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_menu_revert)

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener { 
            onConfirm()
            dialog.dismiss() 
        }
        
        dialog.show()
    }
    
    fun show() {
        val dialog = BottomSheetDialog(context, R.style.BottomSheetDialogTheme)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_requests_list, null)
        dialog.setContentView(view)
        
        val btnOnlineTab = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOnlineTab)
        val btnBlockTab = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBlockTab)

        if (!isHostLocal && localUserId != roomOwnerId) {
            btnBlockTab.visibility = View.GONE
        }

        val rv = view.findViewById<RecyclerView>(R.id.rvRequests)
        rv.layoutManager = LinearLayoutManager(context)

        fun refreshAdapter() {
             val emptyStateContainer = view.findViewById<android.widget.LinearLayout>(R.id.emptyStateContainer)
             val tvEmptyStateMessage = view.findViewById<TextView>(R.id.tvEmptyStateMessage)
             val rvList = view.findViewById<RecyclerView>(R.id.rvRequests)

             if (isBlockView) {
                 btnBlockTab.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#7B1FA2"))
                 btnBlockTab.setTextColor(android.graphics.Color.WHITE)
                 btnOnlineTab.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                 btnOnlineTab.setTextColor(android.graphics.Color.parseColor("#808080"))
                 
                 val adapter = OnlineUsersAdapter(context, blockList, isHostLocal || localUserId == roomOwnerId, localUserId, emptyMap(), 
                    isBlockMode = true,
                    invitedUserIds = this@OnlineUsersBottomSheet.invitedUserIds,
                    onInvite = { },
                    onBlock = { target: SeatParticipant ->
                        showConfirmDialog(
                            "Unblock User", 
                            "Are you sure you want to unblock ${target.name}?", 
                            false
                        ) {
                            com.suguna.rtc.utils.SocketManager.crUnblockUser(roomId, target.id)
                            blockList.remove(target)
                            refreshAdapter()
                            Toast.makeText(context, "${target.name} has been unblocked.", Toast.LENGTH_SHORT).show()
                        }
                    }
                 )
                 rvList.adapter = adapter
                 
                 if (blockList.isEmpty()) {
                     emptyStateContainer.visibility = View.VISIBLE
                     rvList.visibility = View.GONE
                     tvEmptyStateMessage.text = "No blocked users"
                 } else {
                     emptyStateContainer.visibility = View.GONE
                     rvList.visibility = View.VISIBLE
                 }
             } else {
                 btnOnlineTab.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#7B1FA2"))
                 btnOnlineTab.setTextColor(android.graphics.Color.WHITE)
                 btnBlockTab.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                 btnBlockTab.setTextColor(android.graphics.Color.parseColor("#808080"))

                  val fullList = listParticipants.toMutableList()
                  
                  // 1. Add Room Owner (Host)
                  if (!fullList.any { it.id == roomOwnerId }) {
                      val hName = if (localUserId == roomOwnerId) localName else roomOwnerName
                      val hImage = if (localUserId == roomOwnerId) localImage else ""
                      fullList.add(0, SeatParticipant(roomOwnerId, hName, hImage, true))
                  }
                  
                  // 2. Add Local User (If not host and not in list)
                  if (!fullList.any { it.id == localUserId }) {
                      fullList.add(SeatParticipant(localUserId, localName, localImage, isHost = isHostLocal))
                  }
                  
                  // 3. Deduplicate by ID
                  val dedupedList = fullList.distinctBy { it.id }
                  
                  val sortedList = dedupedList.sortedByDescending { target -> 
                       seatedUsers.values.any { it.id == target.id } || target.id == roomOwnerId
                  }

                 val adapter = OnlineUsersAdapter(context, sortedList, isHostLocal || localUserId == roomOwnerId, localUserId, seatedUsers, 
                   isBlockMode = false,
                   invitedUserIds = this@OnlineUsersBottomSheet.invitedUserIds,
                   onInvite = onInviteSent,
                   onBlock = { target: SeatParticipant ->
                       showConfirmDialog(
                           "Block User", 
                           "Are you sure you want to completely block ${target.name} from the room?", 
                           true
                       ) {
                           onBlockUser(target)
                           refreshAdapter()
                       }
                   }
                 )
                 rvList.adapter = adapter
                 
                 if (sortedList.isEmpty()) {
                     emptyStateContainer.visibility = View.VISIBLE
                     rvList.visibility = View.GONE
                     tvEmptyStateMessage.text = "No active users"
                 } else {
                     emptyStateContainer.visibility = View.GONE
                     rvList.visibility = View.VISIBLE
                 }
             }
        }

        btnOnlineTab.setOnClickListener { 
            isBlockView = false
            refreshAdapter()
        }

        btnBlockTab.setOnClickListener {
             if (isHostLocal || localUserId == roomOwnerId) {
                 isBlockView = true
                 refreshAdapter() // Updates UI immediately
                 btnBlockTab.isEnabled = false // prevent spam until response
                 
                 com.suguna.rtc.utils.SocketManager.getSocket()?.off("cr_blocklist_res") // Clear old
                 com.suguna.rtc.utils.SocketManager.getSocket()?.on("cr_blocklist_res") { args ->
                     (context as? android.app.Activity)?.runOnUiThread {
                         blockList.clear()
                         val arg0 = args?.getOrNull(0)
                         var list: org.json.JSONArray? = null
                         
                         if (arg0 is org.json.JSONObject) {
                             list = arg0.optJSONArray("list") ?: arg0.optJSONArray("blocklist")
                         } else if (arg0 is org.json.JSONArray) {
                             list = arg0
                         }
                         
                         if (list != null && list.length() > 0) {
                             for (i in 0 until list.length()) {
                                 val obj = list.getJSONObject(i)
                                 blockList.add(SeatParticipant(obj.getString("id"), obj.getString("name"), obj.optString("image"), isHost = false))
                             }
                         }
                         btnBlockTab.isEnabled = true
                         if (isBlockView) refreshAdapter()
                     }
                 }
                 com.suguna.rtc.utils.SocketManager.getSocket()?.emit("cr_get_blocklist", org.json.JSONObject().apply { put("roomId", roomId) })
             }
        }

        refreshAdapter()
        dialog.show()
    }
}
