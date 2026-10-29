#!/usr/bin/env bash
# Start ReliefSync (agent API + web Ops Room) on http://localhost:${PORT:-8077}
set -e
cd "$(dirname "$0")/backend"
PY=python3
for c in python3.13 python3.12 python3.11 python3.10 /opt/homebrew/bin/python3; do command -v "$c" >/dev/null 2>&1 && PY="$c" && break; done
[ -d .venv ] || "$PY" -m venv .venv
. .venv/bin/activate
pip install -q --disable-pip-version-check -r requirements.txt
[ -f .env ] && set -a && . ./.env && set +a
echo "ReliefSync → http://localhost:${PORT:-8077}   (phones on same Wi-Fi: http://$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}'):${PORT:-8077})"
exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8077}
