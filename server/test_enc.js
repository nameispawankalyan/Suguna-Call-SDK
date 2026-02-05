const Encryption = require('./encryption');
try {
    const enc = Encryption.encrypt("Hello");
    console.log("Encrypted:", enc);
    const dec = Encryption.decrypt(enc);
    console.log("Decrypted:", dec);
} catch (e) {
    console.error("Error:", e);
}
