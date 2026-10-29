"""ReliefSync API – FastAPI app: REST + WebSocket + SSE, serves the web Ops Room."""
from __future__ import annotations

import asyncio
import json
import os
import uuid
from contextlib import asynccontextmanager
from xml.sax.saxutils import escape

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import engine, store

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB_DIR = os.getenv("RELIEFSYNC_WEB", os.path.join(os.path.dirname(ROOT), "web"))
AUDIO_DIR = os.path.join(ROOT, "audio")
os.makedirs(AUDIO_DIR, exist_ok=True)


@asynccontextmanager
async def lifespan(app):
    store.ensure_seeded()
    yield


app = FastAPI(title="ReliefSync Agent API", version="1.0.0", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


@app.get("/api/health")
def health():
    return {"ok": True, "service": "reliefsync"}


@app.get("/api/state")
def state():
    return engine.snapshot()


@app.get("/api/incidents")
def list_incidents():
    return [engine.public_incident(i) for i in store.incidents()]


@app.get("/api/incidents/{iid}")
def get_incident(iid: str):
    inc = engine.public_incident(store.incident(iid))
    if not inc:
        raise HTTPException(404, "not found")
    return {"incident": inc, "trace": store.traces(iid)}


@app.get("/api/trace")
def all_trace(limit: int = 300):
    return store.traces(None, limit)


@app.post("/api/sos")
async def sos(
    text: str | None = Form(None),
    transcript: str | None = Form(None),
    lat: float | None = Form(None),
    lon: float | None = Form(None),
    phone: str | None = Form(None),
    client_id: str | None = Form(None),
    audio: UploadFile | None = File(None),
):
    """Voice or text SOS. `transcript` = on-device ASR from the client (used when no Whisper key)."""
    data, fname, url = None, None, None
    if audio is not None:
        data = await audio.read()
        if len(data) > 6 * 1024 * 1024:
            raise HTTPException(413, "voice note too large (30s max)")
        ext = os.path.splitext(audio.filename or "")[1] or ".m4a"
        fname = f"{uuid.uuid4()}{ext}"
        with open(os.path.join(AUDIO_DIR, fname), "wb") as f:
            f.write(data)
        url = f"/audio/{fname}"
    body = transcript or text
    if not body and not data:
        raise HTTPException(400, "provide audio, transcript or text")
    iid = await engine.create_incident(body, data, fname, lat, lon, "app-voice" if data else "app-text",
                                       reporter_phone=phone, audio_url=url)
    return {"incident_id": iid, "client_id": client_id}


class Respond(BaseModel):
    volunteer_id: str
    answer: str  # ACCEPT | DECLINE


@app.post("/api/dispatch/respond")
async def respond(r: Respond):
    ans = "ACCEPT" if r.answer.upper() in engine.YES else "DECLINE"
    v = store.volunteer(r.volunteer_id)
    if not v:
        raise HTTPException(404, "volunteer")
    store.log_message("in", v["phone_number"], "YES" if ans == "ACCEPT" else "NO", None, "app")
    msg = await engine.volunteer_response(r.volunteer_id, ans)
    return {"message": msg}


class Resolve(BaseModel):
    token: str | None = None


@app.post("/api/incidents/{iid}/resolve")
async def resolve(iid: str, r: Resolve | None = None):
    try:
        return await engine.resolve(iid, r.token if r else None)
    except KeyError:
        raise HTTPException(404, "not found")
    except ValueError:
        raise HTTPException(403, "token mismatch")


class SimSms(BaseModel):
    phone: str
    body: str
    lat: float | None = None
    lon: float | None = None


@app.post("/api/sms/simulate")
async def sms_sim(s: SimSms):
    return {"reply": await engine.handle_sms(s.phone, s.body, s.lat, s.lon)}


@app.post("/api/sms/twilio")
async def sms_twilio(request: Request):
    """Twilio inbound webhook (configure in the Twilio console). Replies with TwiML."""
    form = await request.form()
    reply = await engine.handle_sms(str(form.get("From", "")), str(form.get("Body", "")))
    xml = f'<?xml version="1.0" encoding="UTF-8"?><Response><Message>{escape(reply)}</Message></Response>'
    return Response(xml, media_type="application/xml")


class HazardIn(BaseModel):
    name: str
    kind: str = "FLOOD"
    polygon: list[list[float]]


@app.post("/api/hazards")
async def add_hazard(h: HazardIn):
    hid = f"hz-{uuid.uuid4().hex[:6]}"
    store.x("INSERT INTO hazards VALUES (?,?,?,?)", (hid, h.name, h.kind, json.dumps(h.polygon)))
    await engine.hub.publish({"type": "state"})
    return {"id": hid}


@app.delete("/api/hazards/{hid}")
async def del_hazard(hid: str):
    store.x("DELETE FROM hazards WHERE id=?", (hid,))
    await engine.hub.publish({"type": "state"})
    return {"ok": True}


@app.post("/api/demo/reset")
async def demo_reset():
    for p in list(engine.PENDING.values()):
        p.answer = "DECLINE"
        p.event.set()
    for t in list(engine.TASKS):
        t.cancel()
    engine.PENDING.clear()
    store.reset()
    await engine.hub.publish({"type": "state"})
    return {"ok": True}


DEMO_TEXT = ("Water is coming into our living room on 12th Street near St. Jude's church! My mother's "
             "oxygen concentrator battery is down to 10% and we have no power!")


@app.post("/api/demo/scenario")
async def demo_scenario():
    iid = await engine.create_incident(DEMO_TEXT, None, None, None, None, "demo")
    return {"incident_id": iid}


@app.websocket("/ws")
async def ws(sock: WebSocket):
    await sock.accept()
    qu = engine.hub.subscribe()
    await sock.send_json({"type": "snapshot", "state": engine.snapshot(), "trace": store.traces(None, 200)})

    async def pump():
        while True:
            ev = await qu.get()
            if ev.get("type") == "state":
                ev = {"type": "snapshot", "state": engine.snapshot()}
            await sock.send_json(ev)

    task = asyncio.create_task(pump())
    try:
        while True:
            msg = await sock.receive_text()
            if msg == "ping":
                await sock.send_json({"type": "pong"})
    except WebSocketDisconnect:
        pass
    finally:
        task.cancel()
        engine.hub.unsubscribe(qu)


@app.get("/api/events")
async def sse(request: Request):
    qu = engine.hub.subscribe()

    async def gen():
        try:
            yield f"data: {json.dumps({'type': 'snapshot', 'state': engine.snapshot()})}\n\n"
            while not await request.is_disconnected():
                try:
                    ev = await asyncio.wait_for(qu.get(), 15)
                    yield f"data: {json.dumps(ev)}\n\n"
                except asyncio.TimeoutError:
                    yield ": keepalive\n\n"
        finally:
            engine.hub.unsubscribe(qu)

    return StreamingResponse(gen(), media_type="text/event-stream")


app.mount("/audio", StaticFiles(directory=AUDIO_DIR), name="audio")

if os.path.isdir(WEB_DIR):
    app.mount("/static", StaticFiles(directory=WEB_DIR), name="web")

    @app.get("/")
    def index():
        return FileResponse(os.path.join(WEB_DIR, "index.html"))

    @app.get("/manifest.webmanifest")
    def manifest():
        return FileResponse(os.path.join(WEB_DIR, "manifest.webmanifest"))

    @app.get("/sw.js")
    def sw():
        return FileResponse(os.path.join(WEB_DIR, "sw.js"), media_type="application/javascript")
else:
    @app.get("/")
    def index_missing():
        return JSONResponse({"error": "web dir not found", "path": WEB_DIR})
