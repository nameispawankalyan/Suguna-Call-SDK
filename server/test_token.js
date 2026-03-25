require('dotenv').config();
const { AccessToken } = require('livekit-server-sdk');

const at = new AccessToken(process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET, {
    identity: 'test_user',
    name: 'Test User'
});
at.addGrant({ roomJoin: true, room: 'test_room', canPublish: true, canSubscribe: true, canPublishData: true });

at.toJwt().then(token => {
    console.log("Token Generated!");
    const jwt = require('jsonwebtoken');
    const decoded = jwt.decode(token);
    console.log(JSON.stringify(decoded, null, 2));
}).catch(console.error);
