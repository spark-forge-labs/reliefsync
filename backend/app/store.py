"""SQLite persistence (schema mirrors spec section 6; PostGIS/pgvector-ready).

Spatial queries use haversine in Python; embeddings are stored as JSON text.
Swap DATABASE for Postgres+PostGIS in production (see docs/ARCHITECTURE.md)."""
from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
import uuid

from . import seed
from .tools import embed

DB_PATH = os.getenv("RELIEFSYNC_DB", os.path.join(os.path.dirname(__file__), "..", "reliefsync.db"))
_lock = threading.RLock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS incidents (
  id TEXT PRIMARY KEY, raw_audio_url TEXT, transcription TEXT, category TEXT, urgency TEXT,
  latitude REAL, longitude REAL, status TEXT, created_at REAL, updated_at REAL,
  caller TEXT, location_label TEXT, confidence REAL, needs TEXT, resource_id TEXT,
  volunteer_id TEXT, route TEXT, eta_min INTEGER, confirm_token TEXT, source TEXT,
  reporter_phone TEXT, flags TEXT, scrubbed INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS resources (
  id TEXT PRIMARY KEY, provider_name TEXT, item_name TEXT, quantity INTEGER,
  latitude REAL, longitude REAL, is_available INTEGER, tags TEXT, embedding TEXT
);
CREATE TABLE IF NOT EXISTS volunteers (
  id TEXT PRIMARY KEY, name TEXT, phone_number TEXT, current_latitude REAL,
  current_longitude REAL, transport_type TEXT, is_active INTEGER, assigned_incident_id TEXT,
  skills TEXT
);
CREATE TABLE IF NOT EXISTS hazards (id TEXT PRIMARY KEY, name TEXT, kind TEXT, polygon TEXT);
CREATE TABLE IF NOT EXISTS trace (
  seq INTEGER PRIMARY KEY AUTOINCREMENT, incident_id TEXT, ts REAL, agent TEXT, kind TEXT,
  name TEXT, detail TEXT, data TEXT
);
CREATE TABLE IF NOT EXISTS messages (
  seq INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL, direction TEXT, phone TEXT, body TEXT,
  incident_id TEXT, channel TEXT
);
"""


def conn() -> sqlite3.Connection:
    c = sqlite3.connect(DB_PATH, check_same_thread=False)
    c.row_factory = sqlite3.Row
    return c


_c: sqlite3.Connection | None = None


def db() -> sqlite3.Connection:
    global _c
    if _c is None:
        _c = conn()
        _c.executescript(SCHEMA)
    return _c


def q(sql: str, args=()) -> list[dict]:
    with _lock:
        return [dict(r) for r in db().execute(sql, args).fetchall()]


def x(sql: str, args=()):
    with _lock:
        cur = db().execute(sql, args)
        db().commit()
        return cur


def reset():
    with _lock:
        for t in ("incidents", "resources", "volunteers", "hazards", "trace", "messages"):
            db().execute(f"DELETE FROM {t}")
        for name, phone, lat, lon, tr, skills in seed.VOLUNTEERS:
            db().execute("INSERT INTO volunteers VALUES (?,?,?,?,?,?,1,NULL,?)",
                         (str(uuid.uuid4()), name, phone, lat, lon, tr, json.dumps(skills)))
        for prov, item, qty, lat, lon, tags in seed.RESOURCES:
            db().execute("INSERT INTO resources VALUES (?,?,?,?,?,?,1,?,?)",
                         (str(uuid.uuid4()), prov, item, qty, lat, lon, tags,
                          json.dumps(embed(f"{item} {tags}"))))
        for hz in seed.HAZARDS:
            db().execute("INSERT INTO hazards VALUES (?,?,?,?)",
                         (hz["id"], hz["name"], hz["kind"], json.dumps(hz["polygon"])))
        db().commit()


def ensure_seeded():
    if not q("SELECT id FROM volunteers LIMIT 1"):
        reset()


# ---- typed getters --------------------------------------------------------- #
def incident(iid: str) -> dict | None:
    r = q("SELECT * FROM incidents WHERE id=?", (iid,))
    return _inc(r[0]) if r else None


def _inc(r: dict) -> dict:
    for k in ("needs", "route", "flags"):
        if r.get(k):
            r[k] = json.loads(r[k])
    return r


def incidents() -> list[dict]:
    return [_inc(r) for r in q("SELECT * FROM incidents ORDER BY created_at DESC LIMIT 100")]


def volunteers() -> list[dict]:
    rows = q("SELECT * FROM volunteers")
    for r in rows:
        r["skills"] = json.loads(r["skills"] or "[]")
    return rows


def volunteer(vid: str) -> dict | None:
    v = [r for r in volunteers() if r["id"] == vid]
    return v[0] if v else None


def volunteer_by_phone(phone: str) -> dict | None:
    norm = "".join(ch for ch in phone if ch.isdigit())[-10:]
    for v in volunteers():
        if "".join(ch for ch in v["phone_number"] if ch.isdigit())[-10:] == norm:
            return v
    return None


def resources(with_emb=False) -> list[dict]:
    rows = q("SELECT * FROM resources")
    for r in rows:
        if with_emb:
            r["embedding"] = json.loads(r["embedding"])
        else:
            r.pop("embedding", None)
    return rows


def hazards() -> list[dict]:
    rows = q("SELECT * FROM hazards")
    for r in rows:
        r["polygon"] = json.loads(r["polygon"])
    return rows


def update_incident(iid: str, **fields):
    fields["updated_at"] = time.time()
    for k in ("needs", "route", "flags"):
        if k in fields and not isinstance(fields[k], str) and fields[k] is not None:
            fields[k] = json.dumps(fields[k])
    sets = ",".join(f"{k}=?" for k in fields)
    x(f"UPDATE incidents SET {sets} WHERE id=?", (*fields.values(), iid))


def add_trace(iid: str, agent: str, kind: str, name: str, detail: str, data: dict | None) -> dict:
    ts = time.time()
    cur = x("INSERT INTO trace (incident_id,ts,agent,kind,name,detail,data) VALUES (?,?,?,?,?,?,?)",
            (iid, ts, agent, kind, name, detail, json.dumps(data or {})))
    return {"seq": cur.lastrowid, "incident_id": iid, "ts": ts, "agent": agent, "kind": kind,
            "name": name, "detail": detail, "data": data or {}}


def traces(iid: str | None = None, limit=400) -> list[dict]:
    if iid:
        rows = q("SELECT * FROM trace WHERE incident_id=? ORDER BY seq", (iid,))
    else:
        rows = q("SELECT * FROM trace ORDER BY seq DESC LIMIT ?", (limit,))[::-1]
    for r in rows:
        r["data"] = json.loads(r["data"] or "{}")
    return rows


def log_message(direction, phone, body, iid=None, channel="sim") -> dict:
    ts = time.time()
    cur = x("INSERT INTO messages (ts,direction,phone,body,incident_id,channel) VALUES (?,?,?,?,?,?)",
            (ts, direction, phone, body, iid, channel))
    return {"seq": cur.lastrowid, "ts": ts, "direction": direction, "phone": phone, "body": body,
            "incident_id": iid, "channel": channel}


def messages(limit=200) -> list[dict]:
    return q("SELECT * FROM messages ORDER BY seq DESC LIMIT ?", (limit,))[::-1]
