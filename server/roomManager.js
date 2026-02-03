const { RoomServiceClient } = require('livekit-server-sdk');

// Initialize LiveKit Room Service to manage rooms (kick users if no coins)
const svc = new RoomServiceClient(
    process.env.LIVEKIT_HOST || 'https://your-livekit-project.livekit.cloud',
    process.env.LIVEKIT_API_KEY,
    process.env.LIVEKIT_API_SECRET
);

class RoomManager {
    constructor() {
        this.sessions = new Map(); // roomId -> { timer, userId, webhookUrl, price, elapsedMinutes }
    }

    startMonitoring(roomId, userId, webhookUrl, pricePerMin) {
        if (this.sessions.has(roomId)) {
            console.log(`Already monitoring room: ${roomId}`);
            return;
        }

        console.log(`Starting Coin Monitor for ${userId} in ${roomId}. Webhook: ${webhookUrl}`);

        const timer = setInterval(() => this._tick(roomId), 60 * 1000); // Check every 60s

        this.sessions.set(roomId, {
            timer,
            userId,
            webhookUrl,
            price: pricePerMin,
            elapsedMinutes: 0
        });
    }

    stopMonitoring(roomId) {
        const session = this.sessions.get(roomId);
        if (session) {
            clearInterval(session.timer);
            this.sessions.delete(roomId);
            console.log(`Stopped monitoring room: ${roomId}`);
            
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

    async _tick(roomId) {
        const session = this.sessions.get(roomId);
        if (!session) return;

        session.elapsedMinutes += 1;
        const amountToDeduct = session.price;

        console.log(`Tick: Deducting ${amountToDeduct} coins for ${roomId} (Min: ${session.elapsedMinutes})`);

        try {
            // 1. Call Webhook
            const response = await fetch(session.webhookUrl, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    event: 'DEDUCT_COINS',
                    roomId: roomId,
                    userId: session.userId,
                    amount: amountToDeduct,
                    minutes: session.elapsedMinutes
                })
            });

            if (!response.ok) {
                throw new Error(`Webhook Failed: ${response.statusText}`);
            }
            
            const data = await response.json();
            
            // 2. If Webhook says "Low Balance" or returns error status in body
            if (data.status === 'insufficient_funds' || data.allow === false) {
                console.warn(`Insufficient funds for ${roomId}. Ending call.`);
                await this._forceEndCall(roomId);
            }

        } catch (error) {
            console.error(`Deduction Error for ${roomId}:`, error.message);
            // Optional: End call on network failure or let it retry?
            // For safety, we might want to warn or end.
            // await this._forceEndCall(roomId); 
        }
    }

    async _sendWebhook(url, body) {
        try {
            await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
        } catch(e) { console.error("Webhook Send Error:", e); }
    }

    async _forceEndCall(roomId) {
        this.stopMonitoring(roomId);
        try {
            // Remove the room from LiveKit server
            await svc.deleteRoom(roomId);
            console.log(`Room ${roomId} deleted from LiveKit.`);
        } catch (e) {
            console.error("Failed to delete room:", e);
        }
    }
}

module.exports = new RoomManager();
