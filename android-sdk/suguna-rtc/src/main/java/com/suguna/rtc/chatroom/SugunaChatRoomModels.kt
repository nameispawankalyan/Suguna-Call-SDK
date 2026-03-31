package com.suguna.rtc.chatroom

data class SeatParticipant(
    val id: String,
    val name: String,
    val image: String = "",
    val isHost: Boolean = false,
    val isSpeaking: Boolean = false,
    val isMuted: Boolean = false,
    val seatId: Int = 0,
    val gender: String = "Audience",
    val reactionUrl: String? = null,
    val reactionType: String? = null
)

data class ChatMessage(
    val senderId: String,
    val name: String,
    val image: String = "",
    val message: String,
    val timestamp: String
)

data class ReactionModel(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val url: String = ""
)

interface ChatRoomActions {
    fun triggerReflectionCall(type: String, seat: SeatParticipant)
}
