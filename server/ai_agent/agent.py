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
import re

load_dotenv()
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("suguna-moderator")

# OpenAI Client
openai_client = AsyncOpenAI(api_key=os.getenv("OPENAI_API_KEY"))

# Room-level Context Store
room_context = {}

async def handle_violation_report(ctx: agents.JobContext, receiver_id, sender_id, app_id, webhook_url, reason, proof):
    if not webhook_url: 
        logger.error("❌ No Webhook URL found for reporting violation")
        return
    
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
                "userId": target_user_id,
                "senderId": sender_id,
                "reason": reason,
                "proof": proof
            }
            logger.info(f"🚨 Reporting VIOLATION to Webhook: {webhook_url} for Receiver {target_user_id}")
            try:
                response = await client.post(webhook_url, json=payload, timeout=10.0)
                if response.status_code == 200:
                    data = response.json()
                    strikes = data.get("strikes", 1)
                    logger.info(f"✅ Webhook Response: Strikes={strikes}")
                else:
                    logger.error(f"❌ Webhook Failed: {response.status_code}")
            except Exception as e:
                logger.error(f"❌ Connection Error to Webhook: {e}") 

        signal_data = {
            "type": "SUGUNA_SIGNAL",
            "action": "VIOLATION_SIGNAL",
            "target_id": target_user_id,
            "strike_count": strikes,
            "reason": reason,
            "message": f"Security Warning: {reason}. Strike {strikes}/3."
        }
        signal_packet = json.dumps(signal_data).encode('utf-8')
        participant_ids = list(ctx.room.remote_participants.keys())
        if participant_ids:
            await ctx.room.local_participant.publish_data(
                payload=signal_packet, 
                reliable=True, 
                destination_identities=participant_ids
            )
            logger.warning(f"⚠️ Signal Broadcasted to: {participant_ids}")
    except Exception as e:
        logger.error(f"Violation Reporting Failed: {e}")

async def process_audio_track(ctx: agents.JobContext, track: rtc.AudioTrack, participant: rtc.RemoteParticipant, stt_provider):
    logger.info(f"🎤 Starting STT for track {track.sid} from {participant.identity}")
    audio_stream = rtc.AudioStream(track)
    stt_stream = stt_provider.stream()

    async def stt_worker():
        async for event in stt_stream:
            if hasattr(event, 'alternatives') and len(event.alternatives) > 0:
                text = event.alternatives[0].text
                if text and text.strip():
                    meta_data = {}
                    try:
                        if participant.metadata: meta_data = json.loads(participant.metadata)
                    except: pass

                    role = meta_data.get("role", "unknown")
                    u_db_id = meta_data.get("userId", participant.identity)
                    
                    if role == "receiver":
                        if ctx.room.name not in room_context:
                            room_context[ctx.room.name] = { "transcript": [], "receiver_id": None, "sender_id": None, "webhook": None, "app_id": None, "last_violation_time": 0 }
                        room_context[ctx.room.name]["receiver_id"] = u_db_id
                        room_context[ctx.room.name]["webhook"] = meta_data.get("webhook")
                        room_context[ctx.room.name]["app_id"] = meta_data.get("appId", "friendzone_001")
                    elif role == "sender":
                         if ctx.room.name not in room_context:
                            room_context[ctx.room.name] = { "transcript": [], "receiver_id": None, "sender_id": None, "webhook": None, "app_id": None, "last_violation_time": 0 }
                         room_context[ctx.room.name]["sender_id"] = u_db_id
                    
                    if ctx.room.name in room_context:
                        logger.info(f"📝 RAW STT [{role}]: {text}")
                        room_context[ctx.room.name]["transcript"].append({
                            "role": role,
                            "text": text,
                            "time": time.time(),
                            "userId": u_db_id
                        })
                        if len(room_context[ctx.room.name]["transcript"]) > 30:
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
    sender_id = data.get("sender_id")
    webhook_url = data["webhook"]
    app_id = data["app_id"]
    if not transcript or not receiver_id or not webhook_url: return

    recent_msgs = [m for m in transcript if time.time() - m["time"] < 15]
    if not recent_msgs: return

    dialogue_text = ""
    for msg in transcript[-30:]:
        role_label = msg.get('role', 'unknown').upper()
        text = msg.get('text', '')
        dialogue_text += f"{role_label}: {text}\n"

    logger.info(f"🔍 [AI Check] Room: {room_name}")

    try:
        last_v = room_context[room_name].get("last_violation_time", 0)
        if time.time() - last_v < 20: return # Debounce 20s

        response = await openai_client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {
                    "role": "system", 
                    "content": (
                        "You are an expert security moderator for an Indian dating app."
                        "\n\n🚨 CRITICAL CONTEXT:"
                        "\n- Users speak TELUGU, KANNADA, HINDI, TAMIL, or MALAYALAM."
                        "\n- STT usually transcribes these as HINDI or ENGLISH sounding words."
                        
                        "\n\n🚫 VIOLATION RULES (STRICT CONSENT):"
                        "\n1. PHONE NUMBERS: Flag ONLY if a user shares a contact number AND the receiver ACCEPTS it."
                        "\n   - A valid number must have 10 digits and start with 6-9."
                        "\n   - IGNORE if the receiver is silent, ignores the number, or says 'No/Vodu'."
                        
                        "\n\n🚨 MULTILINGUAL ACCEPTANCE (Set 'acceptance': true if heard):"
                        "\n- TELUGU: 'Sare', 'Ha', 'Cheppu', 'Ivvu', 'Haa', 'Pampu'."
                        "\n- HINDI: 'Haan', 'Bolo', 'Do', 'Ok', 'Thike', 'Sahi hai'."
                        "\n- KANNADA: 'Haudu', 'Heli', 'Kodi', 'Haa'."
                        "\n- TAMIL: 'Aama', 'Sollu', 'Kodu', 'Ok'."
                        "\n- MALAYALAM: 'Para', 'Tharu', 'Athe', 'Ok'."
                        
                        "\n\n✅ ALLOWED (NO REPORT):"
                        "\n- If receiver doesn't respond or says 'No'."
                        "\n- Duration/Time: '15 days', '10 minutes'."
                        "\n- Currency/Generic: '500 rupees', 'Number', 'Payment'."
                        
                        "\n\nJSON OUTPUT: { \"violation\": true/false, \"acceptance\": true/false, \"reason\": \"...\", \"type\": \"PHONE/SOCIAL\", \"extracted_numbers\": \"10-digit number\" }"
                    )
                },
                {"role": "user", "content": f"Dialogue:\n{dialogue_text}"}
            ],
            response_format={ "type": "json_object" }
        )
        
        result = json.loads(response.choices[0].message.content)
        logger.info(f"🤖 AI Result for {room_name}: {result}")
        
        if result.get("violation"):
            v_type = str(result.get("type", "UNKNOWN")).upper()
            ai_reason = str(result.get("reason", "Violation detected"))
            extracted = str(result.get("extracted_numbers", ""))
            digits = "".join(re.findall(r'\d', extracted))
            receiver_accepted = result.get("acceptance", False)
            
            # 🔥 STRICT RULE: Only proceed if receiver explicitly accepted
            if not receiver_accepted:
                logger.info(f"✅ Filtered: Number shared but Receiver did not accept or was silent.")
                return

            is_valid = True
            
            if "PHONE" in v_type:
                # 1. Must be at least 10 digits for Indian Mobiles
                if len(digits) < 10:
                    is_valid = False
                # 2. Must start with mobile prefix
                elif not digits.startswith(('6', '7', '8', '9')):
                    is_valid = False
                
                reason = f"Shared Phone Numbers: {digits}"
            else:
                reason = ai_reason

            if is_valid:
                logger.warning(f"🚨 VIOLATION CONFIRMED: {reason}")
                room_context[room_name]["transcript"] = []
                room_context[room_name]["last_violation_time"] = time.time()
                await handle_violation_report(ctx, receiver_id, sender_id, app_id, webhook_url, reason, dialogue_text)
            else:
                 logger.info(f"✅ AI Report Filtered (Invalid Number/Format): {ai_reason}")

    except Exception as e:
        logger.error(f"AI Error: {e}")

async def run_smart_moderation_loop(ctx):
    while True:
        await asyncio.sleep(5)
        if ctx.room.name in room_context:
            await analyze_dialogue(ctx, ctx.room.name)

async def entrypoint(ctx: agents.JobContext):
    try:
        await ctx.connect(auto_subscribe=agents.AutoSubscribe.AUDIO_ONLY)
        stt_provider = deepgram.STT(model="nova-2", language="hi", smart_format=True, punctuate=True)
        asyncio.create_task(run_smart_moderation_loop(ctx))
        @ctx.room.on("track_subscribed")
        def on_track_subscribed(track, publication, participant):
            if track.kind == rtc.TrackKind.KIND_AUDIO:
                asyncio.create_task(process_audio_track(ctx, track, participant, stt_provider))
    except Exception as e:
        logger.error(f"❌ CRASH: {e}")

if __name__ == "__main__":
    agents.cli.run_app(agents.WorkerOptions(entrypoint_fnc=entrypoint))
