import logging
import os
import asyncio
import json
from dotenv import load_dotenv
from livekit import agents, rtc
from livekit.plugins import deepgram
# Import base STT definitions
from livekit.agents import stt as stt_agent
from openai import AsyncOpenAI

load_dotenv()
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("suguna-moderator")

# OpenAI Client
openai_client = AsyncOpenAI(api_key=os.getenv("OPENAI_API_KEY"))

# Store Conversation History
conversation_history = {}

async def entrypoint(ctx: agents.JobContext):
    await ctx.connect(auto_subscribe=agents.AutoSubscribe.AUDIO_ONLY)
    logger.info(f"Agent connected to room: {ctx.room.name}")
    
    stt_provider = deepgram.STT(language="multi", model="nova-2-general")

    # Start loop
    asyncio.create_task(run_smart_moderation_loop(ctx))

    @ctx.room.on("track_published")
    def on_track_published(publication: rtc.RemoteTrackPublication, participant: rtc.RemoteParticipant):
        if publication.kind == rtc.TrackKind.KIND_AUDIO:
            asyncio.create_task(process_audio_track(publication, participant, stt_provider))

    for participant in ctx.room.remote_participants.values():
        for publication in participant.track_publications.values():
            if publication.kind == rtc.TrackKind.KIND_AUDIO:
                 asyncio.create_task(process_audio_track(publication, participant, stt_provider))

async def process_audio_track(publication, participant, stt_provider):
    try:
        if not publication.subscribed:
            publication.set_subscribed(True)
        
        stream = None
        while stream is None:
            if publication.track:
               stream = rtc.AudioStream(publication.track)
            else:
               await asyncio.sleep(0.5)

        stt_stream = stt_provider.stream()
        
        async def forward_audio():
            try:
                async for event in stream:
                    # Logic to push frame
                    if hasattr(event, 'frame'):
                         stt_stream.push_frame(event.frame)
                    elif isinstance(event, rtc.AudioFrame):
                         stt_stream.push_frame(event)
            except Exception:
                pass
            finally:
                stt_stream.end_input()
        
        asyncio.create_task(forward_audio())

        async for event in stt_stream:
            if event.type == stt_agent.SpeechEventType.FINAL_TRANSCRIPT:
                text = event.alternatives[0].text
                if text:
                    logger.info(f"TRANSCRIPT ({participant.identity}): {text}")
                    # Keep history
                    if participant.identity not in conversation_history:
                        conversation_history[participant.identity] = []
                    conversation_history[participant.identity].append(text)

    except Exception as e:
        logger.warning(f"Metadata: Track ended for {participant.identity} ({e})")

# --- SMART AI MODERATOR LOOP ---
async def run_smart_moderation_loop(ctx):
    logger.info("🧠 Smart AI Moderator Started...")
    while True:
        await asyncio.sleep(10) # CHECK EVERY 10 SECONDS
        
        # Snapshot of history
        current_users = list(conversation_history.keys())
        
        for user_id in current_users:
            messages = conversation_history.get(user_id, [])
            if not messages:
                continue
            
            # Analyze last 10 messages for deeper context
            recent_text = " ".join(messages[-10:]) 
            
            # Clear older messages to save memory
            conversation_history[user_id] = messages[-5:]
            
            asyncio.create_task(analyze_with_gpt(user_id, recent_text))

async def analyze_with_gpt(user_id, text):
    if not text.strip(): return
    try:
        response = await openai_client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {
                    "role": "system", 
                    "content": (
                        "You are a strict call moderator for 'Suguna' app. "
                        "Check for violations in the user's speech. "
                        "Output JSON: { \"violation\": true/false, \"reason\": \"string\" }\n"
                        "Rules:\n"
                        "1. PII: Sharing Phone (even fast/slow), Address, Email.\n"
                        "2. SOCIAL: Sharing Insta/Snap/Telegram IDs.\n"
                        "3. COMPETITORS: Mentioning Tinder/Bumble/Whatsapp.\n"
                        "4. OFF-PLATFORM: Asking to move to other app.\n"
                        "5. ABUSE: Bad words/Sexual content.\n"
                        "Context matters. 'Is this your number?' is OK. 'My number is 9...' is Violation."
                    )
                },
                {"role": "user", "content": f"User said: \"{text}\""}
            ],
            response_format={ "type": "json_object" }
        )
        
        result = json.loads(response.choices[0].message.content)
        if result.get("violation"):
            logger.error(f"🚨 COMPLEX VIOLATION ({user_id}): {result.get('reason')}")

    except Exception as e:
        logger.error(f"AI Check Failed: {e}")

if __name__ == "__main__":
    agents.cli.run_app(agents.WorkerOptions(entrypoint_fnc=entrypoint))
