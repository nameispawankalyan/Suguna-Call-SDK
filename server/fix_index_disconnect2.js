const fs = require('fs');

const filePath = 'c:\\Users\\ramch\\OneDrive\\Desktop\\SugunaCallingSDK\\server\\index.js';
let content = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n'); // Normalize Line Breaks

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
    console.log("Success");
} else {
    console.log("Failed again. searching regex...");
    const regex = /socket\.on\("disconnect",\s*\(\)\s*=>\s*\{[^}]+\{[^}]+BroadCast[^}]+delete[^}]+\}\s*\);\s*\};/m;
    
    // Instead of Regex, let's use IndexOf with direct text normalization
    const idx = content.indexOf('socket.on("disconnect"');
    if (idx !== -1) {
        const afterIndex = content.indexOf('});', idx);
        if (afterIndex !== -1) {
            const before = content.substring(0, idx);
            const after = content.substring(afterIndex + 3);
            
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
             console.log("Success with simple IndexOf");
        } else {
            console.log("Cannot find end bracket");
        }
    } else {
         console.log("Cannot find disconnect");
    }
}
