#!/usr/bin/env bash
# Start ReliefSync (agent API + web Ops Room) on http://localhost:8000
set -e
cd "$(dirname "$0")/backend"
[ -d .venv ] || python3 -m venv .venv
. .venv/bin/activate
pip install -q -r requirements.txt
[ -f .env ] && set -a && . ./.env && set +a
echo "ReliefSync → http://localhost:8000   (phones on same Wi-Fi: http://$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}'):8000)"
exec uvicorn app.main:app --host 0.0.0.0 --port 8000
