const fs = require('fs');

const filePath = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\server\\index.js';
let content = fs.readFileSync(filePath, 'utf8');

const target = `    socket.on("disconnect", () => {
        if (socket.userId) {
            const tenant = tenantManager.getApp(socket.appId || "friendzone_001");
            if (tenant) admin.database(tenant.firebaseApp).ref(\`BroadCast/\${socket.userId}\`).update({ isBusy: false });
            userSocketMap.delete(socket.userId);
        }
    });`;

const replacement = `    socket.on("disconnect", () => {
        if (socket.userId) {
            const tenant = tenantManager.getApp(socket.appId || "friendzone_001");
            if (tenant) admin.database(tenant.firebaseApp).ref(\`BroadCast/\${socket.userId}\`).update({ isBusy: false });
            userSocketMap.delete(socket.userId);

            // Cleanup any active call sessions where this user is the caller/owner
            for (const [rId, sess] of roomManager.sessions.entries()) {
                if (sess.userId === socket.userId) {
                    console.log(\`[Socket] Disconnected user \${socket.userId} had active session \${rId}. Stopping monitor.\`);
                    roomManager.stopMonitoring(rId);
                }
            }
        }
    });`;

if (content.includes(target)) {
    content = content.replace(target, replacement);
    fs.writeFileSync(filePath, content, 'utf8');
    console.log("Success with Direct Match");
} else {
    console.log("Failed to match direct target. trying substring matching...");
    const idx = content.indexOf('socket.on("disconnect", () => {');
    if (idx !== -1) {
        const nextIdx = content.indexOf('});', idx);
        if (nextIdx !== -1) {
            const before = content.substring(0, idx);
            const after = content.substring(nextIdx + 3);
            
            const middle = `socket.on("disconnect", () => {
        if (socket.userId) {
            const tenant = tenantManager.getApp(socket.appId || "friendzone_001");
            if (tenant) admin.database(tenant.firebaseApp).ref(\`BroadCast/\${socket.userId}\`).update({ isBusy: false });
            userSocketMap.delete(socket.userId);

            // Cleanup any active call sessions where this user is the caller/owner
            for (const [rId, sess] of roomManager.sessions.entries()) {
                if (sess.userId === socket.userId) {
                    console.log(\`[Socket] Disconnected user \${socket.userId} had active session \${rId}. Stopping monitor.\`);
                    roomManager.stopMonitoring(rId);
                }
            }
        }
    });`;
            fs.writeFileSync(filePath, before + middle + after, 'utf8');
            console.log("Success with Substring Match");
        } else {
            console.log("Failed index finding after");
        }
    } else {
        console.log("Failed index finding before");
    }
}
