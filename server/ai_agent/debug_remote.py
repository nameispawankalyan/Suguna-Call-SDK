import os
import sys
from dotenv import load_dotenv

print("--- DEBUG START ---")
print(f"Current Directory: {os.getcwd()}")
print(f"Directory Contents: {os.listdir('.')}")

# Check .env
if os.path.exists('.env'):
    print("✅ .env file found.")
    load_dotenv()
else:
    print("❌ .env file NOT found!")

# Check Keys
keys = ["LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET", "DEEPGRAM_API_KEY", "OPENAI_API_KEY"]
missing = []
for k in keys:
    val = os.getenv(k)
    if val:
        masked = val[:4] + "*" * (len(val)-4) if len(val) > 4 else "****"
        print(f"✅ {k}: Found ({masked})")
    else:
        print(f"❌ {k}: MISSING")
        missing.append(k)

if missing:
    print("CRITICAL: Missing keys!")
    sys.exit(1)

# Check Imports
print("Checking imports...")
try:
    import livekit
    print("✅ livekit imported")
    from livekit import agents
    print("✅ livekit.agents imported")
    from livekit.plugins import deepgram
    print("✅ livekit.plugins.deepgram imported")
    from livekit.plugins import openai
    print("✅ livekit.plugins.openai imported")
except ImportError as e:
    print(f"❌ Import Error: {e}")
    sys.exit(1)
except Exception as e:
    print(f"❌ Unexpected Error during import: {e}")
    sys.exit(1)

print("--- DEBUG END: Ready to Run ---")
