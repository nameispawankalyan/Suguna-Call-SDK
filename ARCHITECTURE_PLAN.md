# 🚀 Suguna Calling SDK - Architecture Scaling Plan

## 📌 Context
You are planning to support **1 Lakh+ Concurrent Viewers (Live Streaming)** while maintaining high-quality **1-vs-1 Calls** and **Group Calls**.

## 🏗 Option 1: PEER-TO-PEER (Current Architecture)
*Tech: WebRTC Mesh*

*   **How it works**: Devices send video directly to each other.
*   **1-vs-1**: ✅ Excellent (Lowest Latency, Free).
*   **Group Calls**: ⚠️ Good for only 3-5 users.
*   **Live Streaming**: ❌ **Fail**. A mobile phone cannot upload video to more than 5-10 people simultaneously. It will crash.

## 🚀 Option 2: MEDIA SERVER / SFU (Recommended Architecture)
*Tech: Selective Forwarding Unit (SFU)*

*   **How it works**: Everyone sends/receives video via a central Server (or Cluster of servers).
*   **1-vs-1**: ✅ **Excellent**.
    *   Stable quality on 4G/5G.
    *   Works across Firewalls.
    *   Allows **Server-Side Recording**.
    *   **Cost**: You pay for bandwidth.
*   **Live Streaming**: ✅ **Success**.
    *   Host uploads ONCE to server.
    *   Server distributes to 1 Lakh viewers (using CDN/Clusters).

## 📊 Comparison for your Use Case

| Feature | P2P (Current) | Media Server (SFU) |
| :--- | :--- | :--- |
| **1 vs 1 Quality** | Excellent | Excellent (More Stable) |
| **Server Cost** | ₹0 (Free) | 💰 **High** (Bandwidth Bill) |
| **Max Audience** | ~5 Users | Unlimited (Depends on budget) |
| **Host Battery** | Drains fast in groups | ✅ Efficient |
| **Recording** | Device only | ✅ Cloud Recording |

## 🛠 Recommended Technology
To handle 1 Lakh users, you need a high-performance engine.

1.  **LiveKit** (Recommended): Modern, Go-based, easy SDKs for Android/React.
2.  **Mediasoup**: Extremely powerful Node.js C++ bindings, harder to scale manually.

## 📝 Next Steps
If you proceed with **Option 2** (Media Server):
1.  **Freeze** current P2P development.
2.  **Setup** LiveKit/Mediasoup cluster.
3.  **Update** SDK to consume server streams.
