import logging
import os
from dotenv import load_dotenv
from livekit.agents import AutoSubscribe, JobContext, WorkerOptions, cli, JobRequest
from livekit.agents.llm import ChatContext, ChatMessage
from livekit.plugins import deepgram, openai
from livekit.agents.pipeline import VoicePipelineAgent

load_dotenv()

# Configure Logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("suguna-moderator")

async def entrypoint(ctx: JobContext):
    await ctx.connect(auto_subscribe=AutoSubscribe.AUDIO_ONLY)
    
    # 1. Setup Speech-to-Text (Deepgram)
    # We enable multiple languages: Hindi, Tamil, Telugu, Kannada, Malayalam, English
    stt = deepgram.STT(
        model="nova-2-general",
        language="multi", # Auto-detects between major languages
    )

    # 2. Setup LLM (OpenAI) for Moderation Logic
    llm = openai.LLM(model="gpt-4o-mini")

    # 3. Define the Privacy & Safety Guardrails (Prompt Engineering)
    initial_ctx = ChatContext(
        messages=[
            ChatMessage(
                role="system",
                content=(
                    "You are a strict Call Moderator AI for the Suguna App."
                    "Your job is to monitor the conversation for:"
                    "1. Sharing of Personal Identifiable Information (PII) like Phone Numbers (10 digits), Home Addresses, or Email IDs."
                    "2. Use of Abusive, Vulgar, or Bad words in Hindi, Telugu, Tamil, Kannada, Malayalam, or English."
                    "3. Sexual harassment or bullying."
                    "\n"
                    "If you detect ANY of these:"
                    "- Reply EXACTLY with the word 'VIOLATION_DETECTED' followed by the reason."
                    "- Example: 'VIOLATION_DETECTED: Phone Number Shared'"
                    "- Do NOT reply to casual conversation. Stay silent if everything is safe."
                ),
            )
        ]
    )

    # 4. Create the Agent
    agent = VoicePipelineAgent(
        vad=None, # Voice Activity Detection handled by STT usually, setting None for continuous listen flow setup or default
        stt=stt,
        llm=llm,
        chat_ctx=initial_ctx,
    )

    # 5. Start the Agent
    agent.start(ctx.room)

    # 6. Listen to Agent's Responses (If it specific detects violation)
    @agent.on("agent_speech_committed") # Or checking the LLM stream directly
    def on_analysis(msg):
        text = msg.content
        if "VIOLATION_DETECTED" in text:
            logger.warning(f"BANNING USER due to: {text}")
            # Logic to kick user (Needs Room Admin permissions)
            # await ctx.room.disconnect() # This disconnects the agent, we need to find the participant
            # In a real scenario, we would call an API to ban the user.
            
            # For POC: Just announce it into the room
            # agent.say("Warning: Personal Information sharing is not allowed. Call is being monitored.", allow_interruptions=True)

if __name__ == "__main__":
    cli.run_app(WorkerOptions(entrypoint_fnc=entrypoint))
