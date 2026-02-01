/**
 * Suguna Calling SDK - Web
 * A lightweight WebRTC wrapper for high-quality audio/video calls.
 */

class SugunaClient {
    constructor(config) {
        this.serverUrl = config.serverUrl || 'http://localhost:5000';
        this.rtcToken = config.rtcToken || null; // Official RTC Token
        this.socket = null;
        this.localStream = null;
        this.peerConnections = new Map();
        this.config = {
            iceServers: [
                { urls: 'stun:stun.l.google.com:19302' },
                { urls: 'stun:stun1.l.google.com:19302' }
            ]
        };
        this.events = {};
        this.userId = config.userId || 'user_' + Math.random().toString(36).substr(2, 9);
        this.currentRoom = null;
    }

    on(event, callback) {
        this.events[event] = callback;
    }

    emit(event, data) {
        if (this.events[event]) {
            this.events[event](data);
        }
    }

    async initialize(roomId) {
        if (!this.rtcToken) throw new Error("Security Error: Suguna SDK requires a valid RTC Token.");
        this.currentRoom = roomId;

        // Load Socket.io client dynamically if not present
        if (typeof io === 'undefined') {
            await this._loadScript('https://cdn.socket.io/4.7.2/socket.io.min.js');
        }

        this.socket = io(this.serverUrl, {
            auth: { token: this.rtcToken },
            query: { roomId: roomId }
        });

        this._setupSocketListeners();
    }

    async startLocalStream(video = true, audio = true) {
        try {
            this.localStream = await navigator.mediaDevices.getUserMedia({ video, audio });
            this.emit('local-stream-ready', this.localStream);
            return this.localStream;
        } catch (error) {
            console.error('Error accessing media devices:', error);
            throw error;
        }
    }

    joinRoom() {
        // Join room logic is handled during connection in secure mode
        this.socket.emit('join-room');
    }

    _setupSocketListeners() {
        this.socket.on('user-joined', async (userId) => {
            console.log('New user joined:', userId);
            const pc = this._createPeerConnection(userId);

            // Create offer
            const offer = await pc.createOffer();
            await pc.setLocalDescription(offer);

            this.socket.emit('offer', {
                roomId: this.currentRoom,
                offer: offer,
                fromUserId: this.userId,
                toUserId: userId
            });
        });

        this.socket.on('offer', async (data) => {
            if (data.toUserId && data.toUserId !== this.userId) return; // Not for me

            const pc = this._createPeerConnection(data.fromUserId);
            await pc.setRemoteDescription(new RTCSessionDescription(data.offer));

            const answer = await pc.createAnswer();
            await pc.setLocalDescription(answer);

            this.socket.emit('answer', {
                roomId: this.currentRoom,
                answer: answer,
                fromUserId: this.userId,
                toUserId: data.fromUserId
            });
        });

        this.socket.on('answer', async (data) => {
            const pc = this.peerConnections.get(data.fromUserId);
            if (pc) {
                await pc.setRemoteDescription(new RTCSessionDescription(data.answer));
            }
        });

        this.socket.on('ice-candidate', async (data) => {
            const pc = this.peerConnections.get(data.fromUserId);
            if (pc) {
                await pc.addIceCandidate(new RTCIceCandidate(data.candidate));
            }
        });

        this.socket.on('user-left', (userId) => {
            const pc = this.peerConnections.get(userId);
            if (pc) {
                pc.close();
                this.peerConnections.delete(userId);
                this.emit('user-left', userId);
            }
        });
    }

    _createPeerConnection(userId) {
        const pc = new RTCPeerConnection(this.config);
        this.peerConnections.set(userId, pc);

        // Add local tracks
        if (this.localStream) {
            this.localStream.getTracks().forEach(track => {
                pc.addTrack(track, this.localStream);
            });
        }

        pc.onicecandidate = (event) => {
            if (event.candidate) {
                this.socket.emit('ice-candidate', {
                    roomId: this.currentRoom,
                    candidate: event.candidate,
                    fromUserId: this.userId,
                    toUserId: userId
                });
            }
        };

        pc.ontrack = (event) => {
            this.emit('remote-stream-added', {
                userId: userId,
                stream: event.streams[0]
            });
        };

        return pc;
    }

    _loadScript(src) {
        return new Promise((resolve, reject) => {
            const script = document.createElement('script');
            script.src = src;
            script.onload = resolve;
            script.onerror = reject;
            document.head.appendChild(script);
        });
    }

    leaveRoom() {
        if (this.socket) {
            this.socket.disconnect();
        }
        if (this.localStream) {
            this.localStream.getTracks().forEach(track => track.stop());
        }
        this.peerConnections.forEach(pc => pc.close());
        this.peerConnections.clear();
    }
}

// Export for different environments
if (typeof module !== 'undefined' && module.exports) {
    module.exports = SugunaClient;
} else {
    window.SugunaClient = SugunaClient;
}
