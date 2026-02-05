const crypto = require("crypto");

const keyHex = "90083A40204036E21A98F25FDAD274D4A65E4A1A2F70C0B37013DD3FCDE3E277";
const key = Buffer.from(keyHex, "hex");

const IV_SIZE = 12;
const TAG_SIZE = 16; // 128 bits

class Encryption {
    static generateIvFromEmail(email) {
        const hash = Encryption.hashCode(email);
        const iv = Buffer.alloc(IV_SIZE);

        iv[0] = hash & 0xff;
        iv[1] = (hash >> 8) & 0xff;
        iv[2] = (hash >> 16) & 0xff;
        iv[3] = (hash >> 24) & 0xff;

        for (let i = 4; i < IV_SIZE; i++) {
            iv[i] = i * 13;
        }

        return iv;
    }

    static hashCode(str) {
        let hash = 0;
        for (let i = 0; i < str.length; i++) {
            hash = (hash << 5) - hash + str.charCodeAt(i);
            hash |= 0; // convert to 32bit int
        }
        return hash;
    }

    static encrypt(text) {
        if (text === null || text === undefined) return "";
        const strText = String(text);

        // Critical Fix: Explicitly use class name 'Encryption' instead of 'this'
        // 'this' is undefined when static methods are destructured and called directly.
        const iv = Encryption.generateIvFromEmail(strText);
        const cipher = crypto.createCipheriv("aes-256-gcm", key, iv, {
            authTagLength: TAG_SIZE,
        });

        const encrypted = Buffer.concat([
            cipher.update(strText, "utf8"),
            cipher.final(),
        ]);
        const tag = cipher.getAuthTag();

        const combined = Buffer.concat([iv, encrypted, tag]);
        return combined.toString("base64");
    }

    static decrypt(base64Str) {
        if (!base64Str) return null;
        const combined = Buffer.from(base64Str, "base64");
        if (combined.length < IV_SIZE + TAG_SIZE) return null;

        const iv = combined.slice(0, IV_SIZE);
        const encrypted = combined.slice(IV_SIZE, combined.length - TAG_SIZE);
        const tag = combined.slice(combined.length - TAG_SIZE);

        const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv, {
            authTagLength: TAG_SIZE,
        });
        decipher.setAuthTag(tag);

        try {
            const decrypted = Buffer.concat([
                decipher.update(encrypted),
                decipher.final(),
            ]);
            return decrypted.toString("utf8");
        } catch (e) {
            console.error("Decryption error:", e.message);
            return null;
        }
    }
}

module.exports = Encryption;
