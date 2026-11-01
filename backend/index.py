"""Vercel entry point when the project's Root Directory is `backend/`."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
for cand in (os.path.join(HERE, "web"), os.path.join(os.path.dirname(HERE), "web")):
    if os.path.isdir(cand):
        os.environ.setdefault("RELIEFSYNC_WEB", cand)
        break

from app.main import app  # noqa: E402,F401
