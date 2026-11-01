"""Vercel entry point (Python runtime). Exposes the FastAPI app from backend/app/main.py.
Set the Vercel project's Root Directory to the repository root (leave it blank)."""
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(ROOT, "backend"))
os.environ.setdefault("RELIEFSYNC_WEB", os.path.join(ROOT, "web"))

from app.main import app  # noqa: E402,F401
