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
PORT="${PORT:-8077}"
if curl -s -m 2 "http://localhost:$PORT/api/health" | grep -q reliefsync; then
  echo "ReliefSync is already running on :$PORT — restarting it with the latest code…"
  pkill -f "uvicorn app.main:app" 2>/dev/null; sleep 1
  for i in 1 2 3 4 5; do lsof -ti tcp:$PORT >/dev/null 2>&1 || break; sleep 1; done
elif lsof -ti tcp:$PORT >/dev/null 2>&1; then
  echo "Port $PORT is used by another app ($(lsof -ti tcp:$PORT | head -1 | xargs ps -o comm= -p 2>/dev/null)). Try: PORT=8078 bash run.sh"; exit 1
fi
echo "ReliefSync → http://localhost:${PORT:-8077}   (phones on same Wi-Fi: http://$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}'):${PORT:-8077})"
exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8077} --reload --reload-dir app
