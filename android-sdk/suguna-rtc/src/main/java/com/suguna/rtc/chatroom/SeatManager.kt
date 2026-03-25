package com.suguna.rtc.chatroom

class SeatManager {

    private val seatList = mutableListOf<SeatParticipant>()

    fun getSeats(): List<SeatParticipant> = seatList

    // --------------------------
    // 1. Create seats by room level
    // --------------------------
    fun generateSeats(roomLevel: Int) {
        seatList.clear()

        // First add Host seat
        seatList.add(
            SeatParticipant(
                id = "host_placeholder",
                name = "Host",
                isHost = true,
                seatId = 0,
                gender = "Host"
            )
        )

        // Force exactly 7 audience seats
        for (i in 1..7) {
             seatList.add(
                 SeatParticipant(
                     id = "id_$i",
                     name = "Seat $i",
                     seatId = i,
                     gender = if (i % 2 == 0) "Male" else "Female"
                 )
             )
        }
    }

    // --------------------------
    // 2. Join a user into a seat (Optional helper for adaptive updates)
    // --------------------------
    fun joinUserToSeat(seatId: Int, userId: String, userName: String, userImage: String) {
        val index = seatList.indexOfFirst { it.seatId == seatId }
        if (index != -1) {
            val s = seatList[index]
            seatList[index] = s.copy(
                id = userId,
                name = userName,
                image = userImage
            )
        }
    }

    // --------------------------
    // 3. Remove user from a seat
    // --------------------------
    fun removeUserFromSeat(seatId: Int) {
        val index = seatList.indexOfFirst { it.seatId == seatId }
        if (index != -1) {
            val s = seatList[index]
            seatList[index] = s.copy(
                id = "id_$seatId",
                name = "Seat $seatId",
                image = ""
            )
        }
    }
}
