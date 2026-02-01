# 🚀 Suguna Calling SDK
### The Next-Generation Real-Time Communication Engine

Suguna Calling SDK is a high-performance, secure, and developer-friendly RTC (Real-Time Communication) platform. It allows developers to integrate High-Definition Audio/Video calls into their applications with just a few lines of code.

---

## 🏗 Platform Support
- 🌐 **Web**: Vanilla JS / React / Next.js
- 📱 **Android**: Native Kotlin / Java
- 💻 **Desktop**: Windows (Electron-based)
- 🍏 **iOS**: Native Swift (Architecture Ready)
- 🏗 **Multi-platform**: Kotlin Multiplatform (KMP) support

---

## 🔥 Key Features
- **Ultra-Low Latency**: Built on WebRTC for sub-second lag.
- **Agora-Style Security**: Token-based authentication (RTC Tokens) with App ID & Certificate verification.
- **Privacy First**: Integrated hooks for AI Transcription, Audio Masking, and PII (Personal Identifiable Information) protection.
- **High Definition**: Adaptive bitrate support for crystal-clear 4K video.
- **Modular Design**: Lightweight and easy to extend.

---

## 🛠 Getting Started

### 1. Signaling Server Setup
The brain of the SDK. Ensure your signaling server is running.
```bash
cd server
npm install
node index.js
```

### 2. Web Integration
Include the SDK in your HTML and initialize.
```javascript
const suguna = new SugunaClient({
    serverUrl: 'http://localhost:5000',
    rtcToken: 'YOUR_RTC_TOKEN'
});

await suguna.initialize('room-id-123');
suguna.joinRoom();
```

### 3. Android Integration
Add the library to your `build.gradle` and start calling.
```kotlin
val suguna = SugunaClient(context, "http://localhost:5000")
suguna.initialize(roomId = "demo-room", rtcToken = "YOUR_RTC_TOKEN")
suguna.joinRoom()
```

---

## 🔐 Security Model (RTC Tokens)
Suguna SDK uses a dual-key security model:
1. **App ID**: Public identifier for your project.
2. **RTC Token**: A short-lived, encrypted JWT token generated from your server using your **App Certificate**.

To generate a token (Server-side):
`POST /generateRtcToken`
Payload: `{ "roomId": "...", "userId": "...", "role": "publisher" }`

---

## 🛣 Roadmap
- [x] Core RTC Protocol (WebRTC)
- [x] Android SDK (Kotlin)
- [x] Windows Desktop App
- [x] RTC Token Security
- [ ] iOS Native SDK (Phase 2)
- [ ] Flutter Wrapper (Phase 2)
- [ ] AI Privacy Guard - Voice Masking (Phase 3)

---

## 🤝 Contributing
Join us in building the world's most secure and private calling SDK.

**Created with ❤️ by Antigravity for Suguna Calling.**
