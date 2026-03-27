const { RoomServiceClient } = require('livekit-server-sdk');

// Initialize LiveKit Room Service to manage rooms (kick users if no coins)
const livekitUrl = (process.env.LIVEKIT_URL || "").replace('wss://', 'https://');
const svc = new RoomServiceClient(
    livekitUrl || 'https://your-livekit-project.livekit.cloud',
    process.env.LIVEKIT_API_KEY,
    process.env.LIVEKIT_API_SECRET
);

class RoomManager {
    constructor() {
        this.sessions = new Map(); // roomId -> { timer, userId, webhookUrl, price, elapsedMinutes }
    }

    startMonitoring(roomId, userId, webhookUrl, pricePerMin, appId, callType, receiverId) {
        if (this.sessions.has(roomId)) {
            console.log(`Already monitoring room: ${roomId}`);
            return;
        }

        console.log(`[Monitor] Starting Coin Monitor for ${userId} in ${roomId}. Webhook: ${webhookUrl} (App: ${appId})`);

        const sessionData = {
            timer: null,
            userId,
            webhookUrl,
            price: pricePerMin,
            elapsedMinutes: 0,
            appId: appId || "friendzone_001",
            callType: callType || "Audio",
            receiverId: receiverId
        };
        this.sessions.set(roomId, sessionData);

        // 1. Initial Deduction - Instant (T=0)
        console.log(`[Monitor] T=0: Triggering initial deduction for ${roomId}`);
        this._tick(roomId, true);

        // 2. Periodic Deductions (Every 60s)
        const timer = setInterval(() => this._tick(roomId, false), 60 * 1000);
        sessionData.timer = timer;
    }

    stopMonitoring(roomId) {
        const session = this.sessions.get(roomId);
        if (session) {
            clearInterval(session.timer);
            const tenantManager = require('./tenantManager');
            tenantManager.updateCallStatus(session.appId, roomId, "Ended");

            this.sessions.delete(roomId);
            console.log(`[Monitor] Stopped monitoring room: ${roomId}`);

            // Final Webhook: Call Ended
            this._sendWebhook(session.webhookUrl, {
                event: 'CALL_ENDED',
                roomId: roomId,
                userId: session.userId,
                totalMinutes: session.elapsedMinutes,
                msg: "Call ended gracefully"
            });
        }
    }

    async _tick(roomId, isInitial = false) {
        const session = this.sessions.get(roomId);
        if (!session) return;

        // 0. Check if room still exists in LiveKit (Prevention for hanging sessions)
        if (!isInitial) {
            try {
                const rooms = await svc.listRooms([roomId]);
                if (!rooms || rooms.length === 0 || rooms[0].numParticipants < 2) {
                    console.log(`[Monitor] Room ${roomId} has ${rooms[0] ? rooms[0].numParticipants : 0} participants. Skipping tick.`);
                    if (rooms.length === 0 || rooms[0].numParticipants === 0) {
                        this.stopMonitoring(roomId);
                    }
                    return;
                }
            } catch (e) {
                console.error(`[Monitor] Error checking room ${roomId}:`, e.message);
            }
        }

        if (!isInitial) session.elapsedMinutes += 1;
        const amountToDeduct = session.price;

        console.log(`[Monitor] Tick: Deducting ${amountToDeduct} coins for ${roomId} (Min: ${session.elapsedMinutes}, Initial: ${isInitial})`);

        try {
            // 1. Call Webhook - BROAD PAYLOAD and WRAPPED DATA (for Callable compatibility)
            const payload = {
                data: { // Wrapped for Firebase Callable compatibility
                    event: 'DEDUCT_COINS',
                    roomId: roomId,
                    callId: roomId,
                    userId: session.userId,
                    uid: session.userId,
                    receiverId: session.receiverId,
                    receiverUid: session.receiverId,
                    targetId: session.receiverId,
                    targetUid: session.userId, // Some backends swap these
                    amount: amountToDeduct,
                    pricePerMin: session.price,
                    minutes: session.elapsedMinutes,
                    isInitial: isInitial,
                    callType: session.callType
                },
                // Flat version for standard REST HTTPS compatibility
                event: 'DEDUCT_COINS',
                roomId: roomId,
                callId: roomId,
                userId: session.userId,
                uid: session.userId,
                receiverId: session.receiverId,
                amount: amountToDeduct,
                pricePerMin: session.price,
                minutes: session.elapsedMinutes,
                isInitial: isInitial,
                callType: session.callType
            };

            const response = await fetch(session.webhookUrl, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            console.log(`[Monitor] Webhook ${session.webhookUrl} status: ${response.status}`);
            
            const text = await response.text();
            console.log(`[Monitor] Webhook body: ${text}`);
            
            let resultData = {};
            try { resultData = JSON.parse(text); } catch(e) {}
            
            // If it's a callable function, actual result is inside 'result' or 'data'
            const finalResult = resultData.result || resultData.data || resultData;

            // 2. If Webhook says "Low Balance" or returns error status in body
            if (finalResult.status === 'insufficient_funds' || finalResult.allow === false || finalResult.success === false) {
                console.warn(`[Monitor] Insufficient funds/Limit for ${roomId}. Ending call.`);
                await this._forceEndCall(roomId);
            }

        } catch (error) {
            console.error(`[Monitor] Deduction Fail for ${roomId}:`, error.message);
        }
    }

    async _sendWebhook(url, body) {
        try {
            await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
        } catch (e) { console.error("[Monitor] Webhook Error:", e); }
    }

    async _forceEndCall(roomId) {
        this.stopMonitoring(roomId);
        try {
            await svc.deleteRoom(roomId);
            console.log(`[Monitor] Room ${roomId} deleted from LiveKit.`);
        } catch (e) {
            console.error("[Monitor] Failed to delete room:", e);
        }
    }
}

module.exports = new RoomManager();
