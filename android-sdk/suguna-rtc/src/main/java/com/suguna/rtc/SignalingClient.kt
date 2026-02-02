package com.suguna.rtc

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

class SignalingClient(
    private val serverUrl: String,
    private var currentRoom: String,
    private val rtcToken: String,
    private val role: String
) {
    private var socket: Socket? = null
    private val userId = "android_" + (0..1000).random()

    interface Callback {
        fun onUserJoined(userId: String, role: String)
        fun onOfferReceived(fromUserId: String, offer: JSONObject)
        fun onAnswerReceived(fromUserId: String, answer: JSONObject)
        fun onIceCandidateReceived(fromUserId: String, candidate: JSONObject)
        fun onUserLeft(userId: String)
    }

    var callback: Callback? = null

    fun connect() {
        try {
            val options = IO.Options.builder()
                .setAuth(mutableMapOf("token" to rtcToken))
                .setQuery("roomId=$currentRoom&role=$role")
                .build()

            socket = IO.socket(serverUrl, options)
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("SugunaSignaling", "Connected to signaling server")
            }

            socket?.on("user-joined") { args ->
                val data = args[0] as JSONObject
                val joinedUserId = data.getString("userId")
                val joinedUserRole = data.optString("role", "host")
                callback?.onUserJoined(joinedUserId, joinedUserRole)
            }

            socket?.on("offer") { args ->
                val data = args[0] as JSONObject
                callback?.onOfferReceived(data.getString("fromUserId"), data.getJSONObject("offer"))
            }

            socket?.on("answer") { args ->
                val data = args[0] as JSONObject
                callback?.onAnswerReceived(data.getString("fromUserId"), data.getJSONObject("answer"))
            }

            socket?.on("ice-candidate") { args ->
                val data = args[0] as JSONObject
                callback?.onIceCandidateReceived(data.getString("fromUserId"), data.getJSONObject("candidate"))
            }

            socket?.on("user-left") { args ->
                val leftUserId = args[0] as String
                callback?.onUserLeft(leftUserId)
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e("SugunaSignaling", "Connection error", e)
        }
    }

    fun joinRoom() {
        socket?.emit("join-room")
    }

    fun sendOffer(toUserId: String, offer: JSONObject) {
        val data = JSONObject().apply {
            put("roomId", currentRoom)
            put("offer", offer)
            put("fromUserId", userId)
            put("toUserId", toUserId)
        }
        socket?.emit("offer", data)
    }

    fun sendAnswer(toUserId: String, answer: JSONObject) {
        val data = JSONObject().apply {
            put("roomId", currentRoom)
            put("answer", answer)
            put("fromUserId", userId)
            put("toUserId", toUserId)
        }
        socket?.emit("answer", data)
    }

    fun sendIceCandidate(toUserId: String, candidate: JSONObject) {
        val data = JSONObject().apply {
            put("roomId", currentRoom)
            put("candidate", candidate)
            put("fromUserId", userId)
            put("toUserId", toUserId)
        }
        socket?.emit("ice-candidate", data)
    }

    fun disconnect() {
        socket?.disconnect()
    }
}
