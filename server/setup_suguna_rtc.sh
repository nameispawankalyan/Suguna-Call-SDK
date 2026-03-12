#!/bin/bash
set -e

# 1. Install Docker
if ! command -v docker &> /dev/null; then
  echo "Installing Docker..."
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker ubuntu
fi

# 2. Setup Directories
sudo mkdir -p /opt/livekit
sudo chown -R ubuntu:ubuntu /opt/livekit
cd /opt/livekit

# 3. Generate Keys
echo "Generating Keys..."
API_KEY=$(openssl rand -hex 16)
API_SECRET=$(openssl rand -hex 32)
echo "API Key: $API_KEY" > ~/livekit_keys.txt
echo "Secret Key: $API_SECRET" >> ~/livekit_keys.txt
echo "URL: wss://rtc.suguna.co" >> ~/livekit_keys.txt

# 4. Create Config Files

# LiveKit Config
cat <<EOF > livekit.yaml
port: 7880
rtc:
  tcp_port: 7881
  port_range_start: 50000
  port_range_end: 60000
  use_external_ip: true
keys:
  $API_KEY: $API_SECRET
logging:
  json: false
  level: info
EOF

# Caddy Config (SSL)
cat <<EOF > Caddyfile
rtc.suguna.co {
  reverse_proxy localhost:7880
}
EOF

# Docker Compose
cat <<EOF > docker-compose.yaml
version: "3.9"
services:
  caddy:
    image: caddy:alpine
    restart: unless-stopped
    network_mode: "host"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
      - caddy_config:/config

  livekit:
    image: livekit/livekit-server:latest
    command: --config /etc/livekit.yaml
    restart: unless-stopped
    network_mode: "host"
    volumes:
      - ./livekit.yaml:/etc/livekit.yaml
    depends_on:
      - caddy

volumes:
  caddy_data:
  caddy_config:
EOF

# 5. Start Services
echo "Starting LiveKit..."
sudo docker compose up -d

echo "Setup Complete!"
