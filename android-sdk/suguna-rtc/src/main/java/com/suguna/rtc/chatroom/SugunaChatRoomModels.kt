package com.suguna.rtc.chatroom

data class SeatParticipant(
    val id: String,
    val name: String,
    val image: String = "",
    val isHost: Boolean = false,
    val isSpeaking: Boolean = false,
    val isMuted: Boolean = false,
    val seatId: Int = 0,
    val gender: String = "Audience"
)

data class ChatMessage(
    val senderId: String,
    val name: String,
    val image: String = "",
    val message: String,
    val timestamp: String
)
