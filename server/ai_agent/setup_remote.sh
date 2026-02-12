#!/bin/bash

# Update System
sudo apt update && sudo apt upgrade -y
sudo apt install python3-pip python3-venv screen -y

# Create Directory
mkdir -p ~/ai-agent
cd ~/ai-agent

# Create Virtual Environment
python3 -m venv venv
source venv/bin/activate

# Install Dependencies
pip install --upgrade pip
if [ -f "requirements.txt" ]; then
    pip install -r requirements.txt
else
    pip install livekit-agents livekit-server-sdk python-dotenv openai deepgram-sdk httpx
fi

# Create .env file with provided values
cat <<EOF > .env
DEEPGRAM_API_KEY=YOUR_DEEPGRAM_API_KEY
OPENAI_API_KEY=YOUR_OPENAI_API_KEY
LIVEKIT_WS_URL=wss://friendzone-ucdl197v.livekit.cloud
LIVEKIT_URL=wss://friendzone-ucdl197v.livekit.cloud
LIVEKIT_API_KEY=YOUR_LIVEKIT_API_KEY
LIVEKIT_API_SECRET=YOUR_LIVEKIT_API_SECRET
EOF

echo "✅ Environment Setup Complete!"
echo "To run the agent manually: source venv/bin/activate && python agent.py"
echo "To run in background: screen -S agent python agent.py"
