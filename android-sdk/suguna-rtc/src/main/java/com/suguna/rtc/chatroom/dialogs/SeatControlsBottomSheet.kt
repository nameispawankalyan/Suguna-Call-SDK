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
    private val localName: String,
    private val localImage: String,
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
        
        val btnMessage = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMessage)
        val btnAudioCall = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAudioCall)
        val btnVideoCall = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnVideoCall)

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
        
        // ----------------------------------------------------
        
        // ----------------------------------------------------
        // Message, Audio, and Video Call Integrations
        // ----------------------------------------------------
        if (!isTargetSelf && seat.id.isNotEmpty() && !seat.id.startsWith("id_") && !seat.id.startsWith("host_")) {
             btnMessage.visibility = View.VISIBLE
             btnMessage.setOnClickListener {
                 try {
                     val intent = android.content.Intent().apply {
                         setClassName(context, "pawankalyan.gpk.friendzone.UI.Activities.Messages.MessageActivity")
                         putExtra("profileId", seat.id)
                     }
                     context.startActivity(intent)
                 } catch (e: Exception) { e.printStackTrace() }
                 dialog.dismiss()
             }

             try {
                 val dbRef = com.google.firebase.database.FirebaseDatabase.getInstance()
                      .reference.child("BroadCast").child(seat.id)
                 dbRef.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
                      override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                          if (snapshot.exists()) {
                              val encStatus = snapshot.child("Status").getValue(String::class.java) ?: ""
                              val encCallEnabled = snapshot.child("CallEnabled").getValue(String::class.java) ?: ""
                              val encAudio = snapshot.child("AudioCallEnabled").getValue(String::class.java) ?: ""
                              val encVideo = snapshot.child("VideoCallEnabled").getValue(String::class.java) ?: ""

                              val status = com.suguna.rtc.utils.Encryption.decrypt(encStatus) ?: ""
                              val callEnabled = com.suguna.rtc.utils.Encryption.decrypt(encCallEnabled) ?: "false"
                              val audio = com.suguna.rtc.utils.Encryption.decrypt(encAudio) ?: "false"
                              val video = com.suguna.rtc.utils.Encryption.decrypt(encVideo) ?: "false"

                              if (status.equals("Activated", ignoreCase = true) && callEnabled.toBoolean()) {
                                  if (audio.toBoolean()) {
                                      btnAudioCall.visibility = View.VISIBLE
                                      btnAudioCall.setOnClickListener {
                                           initiateReflectionCall("Audio")
                                           dialog.dismiss()
                                      }
                                  }
                                  if (video.toBoolean()) {
                                      btnVideoCall.visibility = View.VISIBLE
                                      btnVideoCall.setOnClickListener {
                                           initiateReflectionCall("Video")
                                           dialog.dismiss()
                                      }
                                  }
                               }
                           }
                      }
                      override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
                 })
             } catch (e: Exception) { e.printStackTrace() }
        }

        dialog.show()
    }
    
    // Fallback reflection invoking SocketManager in FriendZone App dynamically
    private fun initiateReflectionCall(type: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                // Fetch dynamic coins first, realistically falling back to default if unavailable
                val coinRef = com.google.firebase.database.FirebaseDatabase.getInstance()
                    .reference.child("Wallet").child("CoinBalance").child(localUserId)
                coinRef.get().addOnSuccessListener { snapshot ->
                    var totalCoins = 0L
                    if (snapshot.exists()) {
                        val bonusEnc = snapshot.child("BonusCoins").getValue(String::class.java) ?: "0"
                        val rechargeEnc = snapshot.child("RechargeCoins").getValue(String::class.java) ?: "0"
                        val b = com.suguna.rtc.utils.Encryption.decrypt(bonusEnc)?.toLongOrNull() ?: 0L
                        val r = com.suguna.rtc.utils.Encryption.decrypt(rechargeEnc)?.toLongOrNull() ?: 0L
                        totalCoins = b + r
                    }
                    if ((type == "Audio" && totalCoins < 100) || (type == "Video" && totalCoins < 300)) {
                         android.widget.Toast.makeText(context, "Insufficient Coins for $type Call", android.widget.Toast.LENGTH_SHORT).show()
                         return@addOnSuccessListener
                    }
                    
                    try {
                        val socketClass = Class.forName("pawankalyan.gpk.friendzone.Utils.SocketManager")
                        val method = socketClass.getMethod("initiateCall", String::class.java, String::class.java, String::class.java, String::class.java, Long::class.java)
                        
                        // We need the socketclass object instance because it's an object singleton
                        val objectInstanceField = socketClass.getDeclaredField("INSTANCE")
                        val instance = objectInstanceField.get(null)
                        
                        // Pass local user details properly
                        method.invoke(instance, seat.id, type, localName, localImage, totalCoins)
                    } catch (e: Exception) { 
                        e.printStackTrace() 
                        android.widget.Toast.makeText(context, "Unable to init call from Room. Link missing.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
