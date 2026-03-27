package com.suguna.rtc.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

object SocketManager {
    private var socket: Socket? = null
    private const val SERVER_URL = "https://call.suguna.co" 

    private var isReceiverRegistered = false
    private val sdkReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                "com.suguna.rtc.ACTION_END_CALL" -> {
                    val roomName = intent.getStringExtra("ROOM_NAME") ?: ""
                    if (roomName.isNotEmpty()) endCall(roomName)
                }
            }
        }
    }

    fun connect(context: Context, userId: String, userName: String = "", userImage: String = "", appId: String = "friendzone_001") {
        if (socket?.connected() == true) return

        if (!isReceiverRegistered) {
            val filter = android.content.IntentFilter("com.suguna.rtc.ACTION_END_CALL")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(sdkReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(sdkReceiver, filter)
            }
            isReceiverRegistered = true
        }

        try {
            val options = IO.Options()
            options.forceNew = true
            options.reconnection = true
            options.query = "userId=$userId&appId=$appId"
            
            socket = IO.socket(SERVER_URL, options)
            socket?.connect()

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("SocketManager", "Connected to Suguna Signaling Server")
                val joinData = JSONObject()
                joinData.put("userId", userId)
                joinData.put("appId", appId)
                joinData.put("userName", userName)
                joinData.put("userImage", userImage)
                socket?.emit("join", joinData)
            }

            // Relay incoming calls back to the App via Broadcast
            socket?.on("incoming_call") { args ->
                val data = args?.getOrNull(0) as? JSONObject
                if (data != null) {
                    val intent = Intent("com.suguna.rtc.ACTION_INCOMING_CALL")
                    intent.putExtra("DATA", data.toString())
                    intent.setPackage(context.packageName)
                    context.sendBroadcast(intent)
                }
            }

            socket?.on("call_cancelled") { args ->
                 val from = (args[0] as? JSONObject)?.optString("from") ?: ""
                 val intent = Intent("com.suguna.rtc.ACTION_CLOSE_INCOMING")
                 intent.putExtra("TARGET_ID", from)
                 intent.setPackage(context.packageName)
                 context.sendBroadcast(intent)
            }

            socket?.on("call_rejected") { args ->
                 val intent = Intent("com.suguna.rtc.ACTION_CLOSE_OUTGOING")
                 intent.setPackage(context.packageName)
                 context.sendBroadcast(intent)
            }

            socket?.on("call_success") { args ->
                val data = args?.getOrNull(0) as? JSONObject
                if (data != null) {
                    val intent = Intent("com.suguna.rtc.ACTION_CALL_SUCCESS")
                    intent.putExtra("TARGET_ID", data.optString("targetId"))
                    intent.putExtra("TARGET_NAME", data.optString("targetName", "User"))
                    intent.putExtra("TARGET_IMAGE", data.optString("targetImage"))
                    intent.putExtra("TYPE", data.optString("type"))
                    intent.putExtra("ROOM_NAME", data.optString("roomId"))
                    intent.setPackage(context.packageName)
                    context.sendBroadcast(intent)
                }
            }
            
            socket?.on("call_failed") { args ->
                val data = args?.getOrNull(0) as? JSONObject
                if (data != null) {
                    val intent = Intent("com.suguna.rtc.ACTION_CALL_FAILED")
                    intent.putExtra("REASON", data.optString("reason", "Failed"))
                    intent.setPackage(context.packageName)
                    context.sendBroadcast(intent)
                }
            }

        } catch (e: Exception) {
            Log.e("SocketManager", "Socket Exception: ${e.message}")
        }
    }

    fun getSocket(): Socket? = socket

    fun initiateCall(targetUserId: String, callType: String, senderName: String, senderImage: String, coins: Long) {
        val data = JSONObject()
        data.put("targetId", targetUserId)
        data.put("type", callType)
        data.put("senderName", senderName)
        data.put("senderImage", senderImage)
        data.put("coins", coins)
        socket?.emit("make_call", data)
    }

    fun acceptCall(senderUserId: String, callType: String, webhookUrl: String = "", pricePerMin: Int = 20, roomId: String = "") {
        val data = JSONObject()
        data.put("senderUserId", senderUserId)
        data.put("callType", callType)
        data.put("webhookUrl", webhookUrl)
        data.put("pricePerMin", pricePerMin)
        data.put("roomId", roomId)
        socket?.emit("accept_call", data)
    }
    
    fun cancelCall(targetUserId: String, roomId: String = "") {
        val data = JSONObject()
        data.put("targetUserId", targetUserId)
        data.put("roomId", roomId)
        socket?.emit("cancel_call", data)
    }

    fun rejectCall(targetUserId: String, roomId: String = "") {
        val data = JSONObject()
        data.put("targetUserId", targetUserId)
        data.put("roomId", roomId)
        socket?.emit("reject_call", data)
    }

    fun timeoutCall(targetUserId: String, roomId: String = "") {
        val data = JSONObject()
        data.put("targetUserId", targetUserId)
        data.put("roomId", roomId)
        socket?.emit("call_timeout_sender", data)
    }

    fun requestRandomCall(callType: String, senderName: String, senderImage: String, coins: Long, language: String, fromUserId: String) {
        val data = JSONObject()
        data.put("type", callType)
        data.put("senderName", senderName)
        data.put("senderImage", senderImage)
        data.put("coins", coins)
        data.put("language", language)
        data.put("fromUserId", fromUserId)
        socket?.emit("random_call", data)
    }

    fun endCall(roomName: String) {
        val data = JSONObject()
        data.put("roomName", roomName)
        socket?.emit("end_call", data)
    }

    fun crJoin(roomId: String, userId: String, name: String, image: String, isHost: Boolean) {
        val data = JSONObject().apply {
            put("roomId", roomId); put("userId", userId); put("name", name); put("image", image); put("isHost", isHost)
        }
        socket?.emit("cr_join", data)
    }

    fun crSeatRequest(roomId: String, userId: String, name: String, image: String, hostId: String) {
        val data = JSONObject().apply {
            put("roomId", roomId); put("userId", userId); put("name", name); put("image", image); put("hostId", hostId)
        }
        socket?.emit("cr_seat_request", data)
    }

    fun crSeatAction(roomId: String, action: String, userId: String, name: String, image: String, seatId: Int) {
        val data = JSONObject().apply {
            put("roomId", roomId); put("action", action); put("userId", userId); put("name", name); put("image", image); put("seatId", seatId)
        }
        socket?.emit("cr_seat_action", data)
    }

    fun crLeave(roomId: String, userId: String) {
        val data = JSONObject().apply { put("roomId", roomId); put("userId", userId) }
        socket?.emit("cr_leave", data)
    }

    fun crSyncState(roomId: String, state: JSONObject) {
        val data = JSONObject().apply {
            put("roomId", roomId)
            put("state", state)
        }
        socket?.emit("cr_sync_state", data)
    }

    fun crChat(roomId: String, userId: String, name: String, image: String, msg: String) {
        val data = JSONObject().apply {
            put("roomId", roomId); put("userId", userId); put("name", name); put("image", image); put("msg", msg)
        }
        socket?.emit("cr_chat", data)
    }

    fun crInvite(roomId: String, targetId: String, hostName: String) {
        val data = JSONObject().apply { put("roomId", roomId); put("targetId", targetId); put("hostName", hostName) }
        socket?.emit("cr_invite", data)
    }

    fun crInviteAccept(roomId: String, userId: String, name: String, image: String, hostId: String) {
        val data = JSONObject().apply {
            put("roomId", roomId); put("userId", userId); put("name", name); put("image", image); put("hostId", hostId)
        }
        socket?.emit("cr_invite_accept", data)
    }

    fun crBlockUser(roomId: String, targetId: String, name: String, image: String) {
        val data = JSONObject().apply { 
            put("roomId", roomId); put("targetId", targetId); put("name", name); put("image", image)
        }
        socket?.emit("cr_block_user", data)
    }

    fun crUnblockUser(roomId: String, targetId: String) {
        val data = JSONObject().apply { put("roomId", roomId); put("targetId", targetId) }
        socket?.emit("cr_unblock_user", data)
    }

    fun crCheckBlock(roomId: String, userId: String) {
        val data = JSONObject().apply { put("roomId", roomId); put("userId", userId) }
        socket?.emit("cr_check_block", data)
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
    }
}
