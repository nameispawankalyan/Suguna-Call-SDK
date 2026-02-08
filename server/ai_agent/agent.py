import logging
import os
import asyncio
import json
from dotenv import load_dotenv
from livekit import agents, rtc
from livekit.plugins import deepgram
from openai import AsyncOpenAI
import httpx
import time

load_dotenv()
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("suguna-moderator")

# OpenAI Client
openai_client = AsyncOpenAI(api_key=os.getenv("OPENAI_API_KEY"))

# Room-level Context Store
# Structure: { room_name: { "transcript": [], "receiver_id": None, "webhook": None, "app_id": None, "last_analysis_time": 0 } }
room_context = {}

async def handle_violation_report(ctx: agents.JobContext, receiver_id, sender_id, app_id, webhook_url, reason, proof):
    if not webhook_url: 
        logger.error("❌ No Webhook URL found for reporting violation")
        return
    
    # ALWAYS Report against the Receiver (Host)
    target_user_id = receiver_id 
    if not target_user_id:
        logger.error("❌ No Receiver ID found for reporting violation")
        return

    try:
        strikes = 1
        async with httpx.AsyncClient() as client:
            payload = {
                "event": "VIOLATION_DETECTED",
                "appId": app_id,
                "userId": target_user_id, # Report against Receiver
                "senderId": sender_id, # Include Sender info
                "reason": reason,
                "proof": proof # Include Transcript Snippet
            }
            logger.info(f"🚨 Reporting VIOLATION to Webhook: {webhook_url} for Receiver {target_user_id}")
            try:
                response = await client.post(webhook_url, json=payload, timeout=10.0)
                
                if response.status_code == 200:
                    data = response.json()
                    strikes = data.get("strikes", 1)
                    logger.info(f"✅ Webhook Response: Strikes={strikes}, Banned={data.get('isBanned')}")
                else:
                    logger.error(f"❌ Webhook Failed: {response.status_code}")
            except Exception as e:
                logger.error(f"❌ Connection Error to Webhook: {e}") 
                strikes = 1

        # Broadcast signal to ALL participants (Use loop to ensure delivery)
        signal_data = {
            "type": "SUGUNA_SIGNAL",
            "action": "VIOLATION_SIGNAL",
            "target_id": target_user_id, # App can use this to know who got the strike
            "strike_count": strikes,
            "reason": reason,
            "message": f"Security Warning: {reason}. Strike {strikes}/3."
        }
        signal_packet = json.dumps(signal_data).encode('utf-8')
        
        # Get all participant identities
        participant_ids = list(ctx.room.remote_participants.keys())
        
        if participant_ids:
            await ctx.room.local_participant.publish_data(
                payload=signal_packet, 
                reliable=True, 
                destination_identities=participant_ids
            )
            logger.warning(f"⚠️ Signal Broadcasted to: {participant_ids}")
        else:
            logger.warning("⚠️ No participants found to broadcast signal")

    except Exception as e:
        logger.error(f"Violation Reporting Failed: {e}")

async def process_audio_track(ctx: agents.JobContext, track: rtc.AudioTrack, participant: rtc.RemoteParticipant, stt_provider):
    logger.info(f"🎤 Starting STT for track {track.sid} from {participant.identity}")
    
    audio_stream = rtc.AudioStream(track)
    stt_stream = stt_provider.stream()

    # Handle STT results
    async def stt_worker():
        async for event in stt_stream:
            if hasattr(event, 'alternatives') and len(event.alternatives) > 0:
                text = event.alternatives[0].text
                if text and text.strip():
                    # Parse Metadata to identify Role
                    meta_data = {}
                    try:
                        if participant.metadata:
                            meta_data = json.loads(participant.metadata)
                    except: pass

                    role = meta_data.get("role", "unknown")
                    u_db_id = meta_data.get("userId", participant.identity)
                    
                    # Store Room Context
                    # If this is the receiver, ensure we capture their ID and Webhook
                    if role == "receiver":
                        if ctx.room.name not in room_context:
                            room_context[ctx.room.name] = { "transcript": [], "receiver_id": None, "sender_id": None, "webhook": None, "app_id": None, "last_analysis_time": 0 }
                        
                        room_context[ctx.room.name]["receiver_id"] = u_db_id
                        room_context[ctx.room.name]["webhook"] = meta_data.get("webhook")
                        room_context[ctx.room.name]["app_id"] = meta_data.get("appId", "friendzone_001")
                    elif role == "sender":
                         if ctx.room.name not in room_context:
                            room_context[ctx.room.name] = { "transcript": [], "receiver_id": None, "sender_id": None, "webhook": None, "app_id": None, "last_analysis_time": 0 }
                         room_context[ctx.room.name]["sender_id"] = u_db_id
                    
                    # Just in case receiver is determined later, or if we need to capture webhook from sender
                    if ctx.room.name in room_context:
                        if not room_context[ctx.room.name]["webhook"]:
                             room_context[ctx.room.name]["webhook"] = meta_data.get("webhook")

                    logger.info(f"📝 TRANSCRIPT [{role}]: {text}")
                    
                    # Append to transcript
                    if ctx.room.name not in room_context:
                         room_context[ctx.room.name] = { "transcript": [], "receiver_id": None, "webhook": None, "app_id": None, "last_analysis_time": 0 }

                    room_context[ctx.room.name]["transcript"].append({
                        "role": role,
                        "text": text,
                        "time": time.time(),
                        "userId": u_db_id
                    })
                    
                    # Keep only last 20 messages
                    if len(room_context[ctx.room.name]["transcript"]) > 20:
                         room_context[ctx.room.name]["transcript"].pop(0)

    asyncio.create_task(stt_worker())

    async for audio_frame in audio_stream:
        frame_to_push = audio_frame.frame if hasattr(audio_frame, 'frame') else audio_frame
        stt_stream.push_frame(frame_to_push)

async def analyze_dialogue(ctx, room_name):
    if room_name not in room_context: return

    data = room_context[room_name]
    transcript = data["transcript"]
    receiver_id = data["receiver_id"]
    sender_id = data.get("sender_id") # Get Sender ID
    webhook_url = data["webhook"]
    app_id = data["app_id"]

    if not transcript or not receiver_id or not webhook_url:
        return

    # Check if we have recent messages (within last 15 seconds)
    recent_msgs = [m for m in transcript if time.time() - m["time"] < 15]
    if not recent_msgs: return

    # Format Dialogue for GPT
    dialogue_text = ""
    for msg in transcript[-10:]: # Analyze last 10 messages context
        try:
            role_label = msg.get('role', 'unknown').upper()
            text = msg.get('text', '')
            dialogue_text += f"{role_label}: {text}\n"
        except: pass

    try:
        # Check for recent violation to prevent double strikes (debounce 30s)
        last_violation = room_context[room_name].get("last_violation_time", 0)
        if time.time() - last_violation < 30:
            logger.info(f"Skipping violation report for {room_name} (Deboucing)")
            return

        response = await openai_client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {
                    "role": "system", 
                    "content": (
                        "You are a security moderator. Your ONLY job is to prevent off-platform contact sharing."
                        "\n\n🚨 CRITICAL RULE: VIOLATION ONLY IF CONTACT DETAILS ARE ACTUALLY SHARED."
                        "\nIf users talk about 'Phone', 'Number', 'Insta', 'WhatsApp' but DO NOT share digits or IDs -> NO VIOLATION."
                        
                        "\n\n🚨 WHAT IS ALLOWED (DO NOT FLAG):"
                        "\n- You MUST ALLOW all conversations about relationships ('Married', 'Single', 'Dating')."
                        "\n- You MUST ALLOW intimacy, sex talk, bad words, swearing, fighting, or casual talk."
                        "\n- You MUST ALLOW small numbers (e.g., '700 rupees', '2 times', '100%')."
                        "\n- You MUST ALLOW casual mentions/questions like 'Do you use WhatsApp?', 'Give me number', 'I will share insta' IF NO actual info is exchanged."
                        
                        "\n\n🚫 WHAT IS A VIOLATION (FLAG THESE):"
                        "\n1. PHONE NUMBERS (Action vs Intent):"
                        "\n   - IGNORE intents/requests like 'Number ivvu', 'I will give number', 'Phone number hai kya?', 'Give me contact'."
                        "\n   - ONLY FLAG AS VIOLATION IF ACTUAL DIGITS ARE SHARED."
                        "\n   - COUNT total digits in dialogue. IF TOTAL >= 8 -> VIOLATION. IF < 8 -> IGNORE."
                        "\n   - Example: 'My number is...' (No digits) -> IGNORE."
                        "\n   - Example: 'My number is 9 8 4 8...' (Digits present) -> VIOLATION."
                        
                        "\n2. SOCIAL MEDIA IDs:"
                        "\n   - Sharing specific Usernames, Handles, or IDs on 'Insta', 'Snapchat', 'WhatsApp', 'Telegram', 'Facebook'."
                        "\n   - VIOLATION: 'My insta is @ram123', 'Add me on snap: user_xyz', 'Msg me on wa: 9988...'."
                        "\n   - IGNORE: 'Do you have WhatsApp?', 'I use Instagram a lot', 'What is your snap?' (No ID shared)."

                        "\n\nINDIAN NUMBER DETECTION (Telugu/Hindi/Tamil/Kannada/Malayalam):"
                        "\n- Detect phonetics: 'Sunna'(0), 'Okati/Ek'(1), 'Rendu/Do'(2), 'Moodu/Teen'(3), 'Nala/Chaar'(4), 'Aidu/Paanch'(5), 'Aaru/Che'(6), 'Edu/Saat'(7), 'Enimidi/Aath'(8), 'Tommidi/Nau'(9)."
                        "\n- Detect Tens: 'Iravai/Bees'(20), 'Muppai/Tees'(30), 'Nalabhai/Chalees'(40), 'Yabhai/Pachaas'(50), 'Aravai/Saath'(60), 'Debbhi/Sattar'(70), 'Enabhai/Assi'(80), 'Thombai/Nabbez'(90)."
                        
                        "\n\nLOGIC (STRICT):"
                        "\n1. 'Receiver' shares contact -> ALWAYS VIOLATION."
                        "\n2. 'Sender' shares contact ->"
                        "\n   - VIOLATION ONLY IF Receiver EXPLICITLY ACCEPTS (e.g., 'Ok', 'Cheppu', 'Haan', 'Note cheskunna', 'Repeat', 'Got it')."
                        "\n   - NO VIOLATION IF Receiver is SILENT, ignore it, or just says 'Hello'/'Ha' (Ambiguous)."
                        "\n   - NO VIOLATION IF Receiver REJECTS or SAYS NO."
                        
                        "\n\nJSON OUTPUT: { \"violation\": true/false, \"reason\": \"Ex: Shared 10 digits\" }"
                    )
                },
                {"role": "user", "content": f"Dialogue:\n{dialogue_text}"}
            ],
            response_format={ "type": "json_object" }
        )
        
        result = json.loads(response.choices[0].message.content)
        if result.get("violation"):
            reason = result.get("reason", "Violation detected")
            logger.warning(f"🚨 VIOLATION DETECTED in {room_name}: {reason}")
            
            # Clear transcript buffer to avoid repeated flagging of same lines
            room_context[room_name]["transcript"] = []
            
            # Set Debounce Timer
            room_context[room_name]["last_violation_time"] = time.time()
            
            await handle_violation_report(ctx, receiver_id, sender_id, app_id, webhook_url, reason, dialogue_text)

    except Exception as e:
        logger.error(f"AI Analysis Failed: {e}")

async def run_smart_moderation_loop(ctx):
    logger.info("🧠 AI Moderation Loop Active")
    while True:
        await asyncio.sleep(5) # Check every 5 seconds for faster response
        if ctx.room.name in room_context:
            await analyze_dialogue(ctx, ctx.room.name)

async def entrypoint(ctx: agents.JobContext):
    # Connect and subscribe to audio
    await ctx.connect(auto_subscribe=agents.AutoSubscribe.AUDIO_ONLY)
    logger.info(f"🚀 Agent Joined Room: {ctx.room.name}")
    
    # Use Nova-2 with Hindi Language (Better for capturing Indian sounds/numbers like 'Tommidi')
    # It also supports English numbers (9, 8, 7...) effectively due to Code Switching.
    stt_provider = deepgram.STT(model="nova-2", language="hi", smart_format=True)

    # Start the analysis loop
    asyncio.create_task(run_smart_moderation_loop(ctx))

    @ctx.room.on("track_subscribed")
    def on_track_subscribed(track: rtc.Track, publication: rtc.TrackPublication, participant: rtc.RemoteParticipant):
        logger.info(f"📡 Track Subscribed: {track.kind} ({track.sid}) from {participant.identity}")
        
        # Log Metadata for Debugging
        try:
            logger.info(f"🔍 Raw Metadata for {participant.identity}: {participant.metadata}")
        except: pass

        if track.kind == rtc.TrackKind.KIND_AUDIO:
            asyncio.create_task(process_audio_track(ctx, track, participant, stt_provider))

if __name__ == "__main__":
    agents.cli.run_app(agents.WorkerOptions(entrypoint_fnc=entrypoint))
