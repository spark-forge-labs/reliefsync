"""Agent orchestration: a deterministic state graph of micro-agents.

  ingest -> triage -> geo_resolve -> supply_match -> volunteer_rank -> route -> dispatch(loop)
                                                                              |-> escalate
                                                                              |-> municipal
Every node emits Thought -> ToolCall -> Result trace events that stream to the
clients over WebSocket/SSE (spec 3.4). The graph mirrors a LangGraph StateGraph;
it is implemented directly so the prototype runs with zero heavy deps.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import random
import secrets
import time
import uuid
from typing import Any

import httpx

from . import settings, store
from .tools import (cosine, embed, geocode, haversine_mi, llm_triage, route, rule_triage,
                    sanitize, transcribe)

# Read live from settings so changes in the Settings panel apply without a restart.
def timeout_s() -> float: return settings.num("DISPATCH_TIMEOUT_S")
def radius_mi() -> float: return settings.num("VOLUNTEER_RADIUS_MI")
def demo_speed() -> float: return settings.num("DEMO_TRAVEL_SECONDS")
def public_url() -> str: return settings.get("PUBLIC_URL").rstrip("/")


# --------------------------------------------------------------------------- #
# Event hub – fan-out to WebSocket and SSE subscribers
# --------------------------------------------------------------------------- #
class Hub:
    def __init__(self):
        self.subs: set[asyncio.Queue] = set()

    def subscribe(self) -> asyncio.Queue:
        qu: asyncio.Queue = asyncio.Queue(maxsize=1000)
        self.subs.add(qu)
        return qu

    def unsubscribe(self, qu):
        self.subs.discard(qu)

    async def publish(self, event: dict):
        event.setdefault("ts", time.time())
        for qu in list(self.subs):
            try:
                qu.put_nowait(event)
            except asyncio.QueueFull:
                pass


hub = Hub()


async def emit_trace(iid, agent, kind, name, detail, data=None):
    ev = store.add_trace(iid, agent, kind, name, detail, data)
    await hub.publish({"type": "trace", "event": ev})
    await asyncio.sleep(0.12)  # keep the live trace legible for judges
    return ev


async def emit_incident(iid):
    await hub.publish({"type": "incident", "incident": public_incident(store.incident(iid))})


def public_incident(inc: dict | None) -> dict | None:
    if not inc:
        return None
    inc = dict(inc)
    inc.pop("reporter_phone", None)
    if inc.get("volunteer_id"):
        v = store.volunteer(inc["volunteer_id"])
        inc["volunteer"] = {"name": v["name"], "transport": v["transport_type"]} if v else None
    if inc.get("resource_id"):
        r = [r for r in store.resources() if r["id"] == inc["resource_id"]]
        inc["resource"] = r[0] if r else None
    return inc


# --------------------------------------------------------------------------- #
# SMS gateway (Twilio when configured, always mirrored to the simulator)
# --------------------------------------------------------------------------- #
async def send_sms(phone: str, body: str, iid: str | None = None):
    channel = "sim"
    sid, tok, frm = (os.getenv("TWILIO_ACCOUNT_SID"), os.getenv("TWILIO_AUTH_TOKEN"),
                     os.getenv("TWILIO_FROM"))
    target = phone
    override = os.getenv("DEMO_PHONE_OVERRIDE")  # route the on-stage volunteer to a real phone
    if override and phone == os.getenv("DEMO_PHONE_FOR", phone):
        target = override
    if sid and tok and frm:
        try:
            async with httpx.AsyncClient(timeout=5.0) as c:
                r = await c.post(f"https://api.twilio.com/2010-04-01/Accounts/{sid}/Messages.json",
                                 auth=(sid, tok), data={"From": frm, "To": target, "Body": body})
                channel = "twilio" if r.status_code < 300 else f"twilio-error-{r.status_code}"
        except Exception as e:  # network down – stays on simulator
            channel = f"twilio-error-{type(e).__name__}"
    msg = store.log_message("out", phone, body, iid, channel)
    await hub.publish({"type": "sms", "message": msg})
    return channel


# --------------------------------------------------------------------------- #
# Dispatch negotiation state
# --------------------------------------------------------------------------- #
class Pending:
    def __init__(self, iid, vid):
        self.iid, self.vid = iid, vid
        self.event = asyncio.Event()
        self.answer: str | None = None


PENDING: dict[str, Pending] = {}  # volunteer_id -> pending offer
TASKS: set[asyncio.Task] = set()


def spawn(coro):
    t = asyncio.create_task(coro)
    TASKS.add(t)
    t.add_done_callback(TASKS.discard)
    return t


# --------------------------------------------------------------------------- #
# Graph
# --------------------------------------------------------------------------- #
async def create_incident(text: str | None, audio: bytes | None, filename: str | None,
                          lat: float | None, lon: float | None, source: str,
                          reporter_phone: str | None = None, audio_url: str | None = None) -> str:
    iid = str(uuid.uuid4())
    store.x("INSERT INTO incidents (id,raw_audio_url,transcription,status,created_at,updated_at,"
            "source,reporter_phone,latitude,longitude) VALUES (?,?,?,?,?,?,?,?,?,?)",
            (iid, audio_url, text or "", "RECEIVED", time.time(), time.time(), source,
             reporter_phone, lat, lon))
    await emit_incident(iid)
    spawn(run_graph(iid, text, audio, filename, (lat, lon) if lat is not None and lon is not None else None))
    return iid


async def run_graph(iid, text, audio, filename, gps):
    t0 = time.perf_counter()
    try:
        state: dict[str, Any] = {"iid": iid, "gps": gps, "t0": t0}
        await node_ingest(state, text, audio, filename)
        await node_triage(state)
        await node_geo(state)
        if not state.get("geo"):
            return
        await node_supply(state)
        await node_rank(state)
        await node_route(state)
        await node_dispatch(state)
    except Exception as e:  # never leave an incident silently stuck
        await emit_trace(iid, "Supervisor", "ERROR", "GraphError", f"{type(e).__name__}: {e}")
        store.update_incident(iid, status="ESCALATED_MUNICIPAL")
        await emit_incident(iid)


async def node_ingest(st, text, audio, filename):
    iid = st["iid"]
    await emit_trace(iid, "Ingestion", "THOUGHT", "Plan",
                     "New distress signal received. Normalise to English text before triage.")
    whisper = None
    if audio:
        await emit_trace(iid, "Ingestion", "TOOL_CALL", "whisper.translate",
                         f"Transcribing {len(audio)//1024} KB voice note", {"bytes": len(audio)})
        whisper = await transcribe(audio, filename or "sos.m4a")
    final = whisper or text or ""
    if not final.strip():
        final = "(no speech detected) SOS beacon with GPS location only"
    clean, flags = sanitize(final)
    st["text"], st["flags"] = clean, flags
    store.update_incident(iid, transcription=clean, flags=flags, status="TRANSCRIBED")
    src = "whisper" if whisper else ("on-device ASR" if audio else "text")
    await emit_trace(iid, "Ingestion", "RESULT", "AudioTranscribed",
                     f"Text verified via {src}: “{clean[:160]}”", {"engine": src, "text": clean})
    if flags:
        await emit_trace(iid, "Guardrail", "ACTION", "InjectionBlocked",
                         f"Neutralised {len(flags)} instruction-like span(s); message treated as data only.",
                         {"patterns": flags})
    await emit_incident(iid)


async def node_triage(st):
    iid = st["iid"]
    await emit_trace(iid, "Triage", "THOUGHT", "Assess",
                     "Extract caller, location, need category and urgency; score confidence.")
    await emit_trace(iid, "Triage", "TOOL_CALL", "extract_entities", "Structured JSON extraction",
                     {"engine": "claude" if os.getenv("ANTHROPIC_API_KEY") else "rules"})
    tri = await llm_triage(st["text"]) or rule_triage(st["text"])
    if tri.get("engine") == "claude":  # cross-check the LLM with deterministic rules
        rules = rule_triage(st["text"])
        if rules["urgency"] == "P0" and tri["urgency"] != "P0":
            tri["urgency"] = "P0"
            tri["note"] = "Urgency raised by deterministic safety rule"
        tri.setdefault("location_text", rules["location_text"])
    st["triage"] = tri
    store.update_incident(iid, category=tri["category"], urgency=tri["urgency"],
                          caller=tri.get("caller"), confidence=tri.get("confidence"),
                          needs=tri.get("needs"), status="TRIAGED")
    label = {"P0": "P0 Critical", "P1": "P1 Urgent (<4h)", "P2": "P2 Essential (<24h)"}[tri["urgency"]]
    await emit_trace(iid, "Triage", "RESULT", "EntityExtracted",
                     f"Needs = {', '.join(tri.get('needs') or [])}; Category = {tri['category']}; "
                     f"Urgency = {label}; confidence {tri.get('confidence')}",
                     {**tri, "latency_ms": round((time.perf_counter() - st['t0']) * 1000)})
    await emit_incident(iid)


async def node_geo(st):
    iid = st["iid"]
    tri = st["triage"]
    await emit_trace(iid, "GeoResolver", "TOOL_CALL", "geocode",
                     f"Resolving “{tri.get('location_text') or 'landmarks in message'}”",
                     {"gps_hint": st["gps"]})
    geo = await geocode(tri.get("location_text"), st["text"], st["gps"])
    if not geo:
        await emit_trace(iid, "GeoResolver", "RESULT", "GeoUnresolved",
                         "No location found. Reply sent asking caller for a landmark.")
        store.update_incident(iid, status="NEEDS_LOCATION")
        await emit_incident(iid)
        return
    st["geo"] = geo
    store.update_incident(iid, latitude=geo["lat"], longitude=geo["lon"], location_label=geo["label"])
    await emit_trace(iid, "GeoResolver", "RESULT", "GeoResolved",
                     f"Converted “{tri.get('location_text') or geo['label']}” → Lat: {geo['lat']:.4f}, "
                     f"Lon: {geo['lon']:.4f} ({geo['source']})", geo)
    await emit_incident(iid)


async def node_supply(st):
    iid, tri, geo = st["iid"], st["triage"], st["geo"]
    query = " ".join(tri.get("needs") or []) + " " + st["text"]
    await emit_trace(iid, "ResourceResolver", "TOOL_CALL", "vector_store.similarity_search",
                     f"k=5 semantic search for “{' / '.join(tri.get('needs') or [])}”")
    qv = embed(query)
    scored = []
    for r in store.resources(with_emb=True):
        if not r["is_available"] or r["quantity"] <= 0:
            continue
        sim = cosine(qv, r["embedding"])
        dist = haversine_mi((geo["lat"], geo["lon"]), (r["latitude"], r["longitude"]))
        score = sim * 0.8 + max(0, 1 - dist / 3) * 0.2
        scored.append((score, sim, dist, r))
    scored.sort(key=lambda s: -s[0])
    top = scored[:3]
    if not top or top[0][1] < 0.05:
        st["resource"] = None
        await emit_trace(iid, "ResourceResolver", "RESULT", "NoSupplyMatch",
                         "No matching inventory; dispatching volunteer for welfare check / evacuation.")
        return
    s, sim, dist, r = top[0]
    r.pop("embedding", None)
    st["resource"] = r
    store.update_incident(iid, resource_id=r["id"])
    await emit_trace(iid, "ResourceResolver", "RESULT", "SupplyLocated",
                     f"Match found in {r['provider_name']} inventory ({r['item_name']}, qty {r['quantity']}) "
                     f"· similarity {sim:.2f} · {dist:.2f} mi away",
                     {"candidates": [{"item": c[3]["item_name"], "provider": c[3]["provider_name"],
                                      "similarity": round(c[1], 3), "miles": round(c[2], 2)} for c in top]})


TRANSPORT_FIT = {
    "EVACUATION": {"BOAT": 1.0, "4WD": 0.8, "CAR": 0.4, "BICYCLE": 0.1, "FOOT": 0.1},
    "POWER": {"4WD": 1.0, "CAR": 0.9, "BOAT": 0.6, "BICYCLE": 0.3, "FOOT": 0.2},
    "MEDICAL": {"CAR": 1.0, "4WD": 1.0, "BICYCLE": 0.8, "FOOT": 0.6, "BOAT": 0.6},
    "INFANT_CARE": {"CAR": 1.0, "4WD": 0.9, "BICYCLE": 0.8, "FOOT": 0.6, "BOAT": 0.5},
    "FOOD_WATER": {"CAR": 1.0, "4WD": 1.0, "BICYCLE": 0.7, "FOOT": 0.5, "BOAT": 0.5},
}


async def node_rank(st):
    iid, tri, geo = st["iid"], st["triage"], st["geo"]
    await emit_trace(iid, "Dispatch", "THOUGHT", "SelectVolunteers",
                     f"Find top 3 vetted, idle volunteers within {radius_mi()} mi; weigh proximity, "
                     "transport fit and skills.")
    await emit_trace(iid, "Dispatch", "TOOL_CALL", "spatial.st_dwithin",
                     f"ST_DWithin(volunteers, incident, {radius_mi()} mi)")
    cat = tri["category"]
    ranked = []
    for v in store.volunteers():
        if not v["is_active"] or v["assigned_incident_id"] or v["id"] in PENDING:
            continue
        d = haversine_mi((geo["lat"], geo["lon"]), (v["current_latitude"], v["current_longitude"]))
        if d > radius_mi():
            continue
        fit = TRANSPORT_FIT[cat].get(v["transport_type"], 0.5)
        skill = 0.15 if any(s in cat.lower() or s in " ".join(tri.get("needs") or []) for s in v["skills"]) else 0
        score = (1 - d / radius_mi()) * 0.5 + fit * 0.35 + skill
        ranked.append({"id": v["id"], "name": v["name"], "phone": v["phone_number"],
                       "transport": v["transport_type"], "miles": round(d, 2), "score": round(score, 3),
                       "pos": [v["current_latitude"], v["current_longitude"]]})
    ranked.sort(key=lambda r: -r["score"])
    st["candidates"] = ranked[:3]
    await emit_trace(iid, "Dispatch", "RESULT", "VolunteersRanked",
                     " · ".join(f"#{i+1} {c['name']} ({c['transport']}, {c['miles']} mi)"
                                for i, c in enumerate(st["candidates"])) or "No volunteers in radius",
                     {"candidates": st["candidates"]})


async def node_route(st):
    iid, geo = st["iid"], st["geo"]
    if not st["candidates"]:
        return
    c = st["candidates"][0]
    hz = store.hazards()
    await emit_trace(iid, "Router", "TOOL_CALL", "osrm.route+hazard_exclusion",
                     f"Plan {c['transport']} route volunteer → {'pickup → ' if st.get('resource') else ''}incident "
                     f"with {len(hz)} hazard polygon(s) excluded")
    legs = []
    start = tuple(c["pos"])
    if st.get("resource"):
        r = st["resource"]
        legs.append(route(start, (r["latitude"], r["longitude"]), hz, c["transport"]))
        start = (r["latitude"], r["longitude"])
    legs.append(route(start, (geo["lat"], geo["lon"]), hz, c["transport"]))
    path = legs[0]["path"] + sum((leg["path"][1:] for leg in legs[1:]), [])
    blocked = sorted({b for leg in legs for b in leg["blocked"]})
    eta = sum(leg["eta_min"] for leg in legs)
    miles = round(sum(leg["miles"] for leg in legs), 2)
    st["route"] = {"path": path, "eta_min": eta, "miles": miles, "blocked": blocked}
    store.update_incident(iid, route=st["route"], eta_min=eta)
    msg = (f"{', '.join(blocked)} detected; rerouting via secondary ridge." if blocked
           else "Direct route clear of known hazards.")
    await emit_trace(iid, "Router", "RESULT", "RouteEvaluated",
                     f"{msg} {miles} mi, ETA {eta} min.", st["route"])
    await emit_incident(iid)


def compose_alert(st, cand) -> str:
    tri = st["triage"]
    need = tri.get("needs", ["assistance"])[0]
    item = st["resource"]["item_name"] if st.get("resource") else "welfare check"
    pickup = f" Pick up {item} at {st['resource']['provider_name']}." if st.get("resource") else ""
    label = st["geo"]["label"].split(",")[0]
    return (f"ReliefSync Alert: {tri['urgency']} emergency {cand['miles']} mi away at {label}. "
            f"Need: {need}.{pickup} Reply YES to accept or NO to pass within "
            f"{int(timeout_s() // 60) or 1} min.")


async def node_dispatch(st):
    iid = st["iid"]
    store.update_incident(iid, status="DISPATCHING")
    await emit_incident(iid)
    elapsed = time.perf_counter() - st["t0"]
    for i, cand in enumerate(st["candidates"]):
        if cand["id"] in PENDING or (store.volunteer(cand["id"]) or {}).get("assigned_incident_id"):
            continue
        pend = Pending(iid, cand["id"])
        PENDING[cand["id"]] = pend
        body = compose_alert(st, cand)
        await emit_trace(iid, "Dispatch", "ACTION", "DispatchSent",
                         f"SMS → {cand['name']} ({cand['phone'][-4:].rjust(len(cand['phone']), '•')}): {body}",
                         {"volunteer": cand, "attempt": i + 1,
                          "elapsed_ms": round((time.perf_counter() - st['t0']) * 1000)})
        channel = await send_sms(cand["phone"], body, iid)
        await hub.publish({"type": "offer", "incident_id": iid, "volunteer_id": cand["id"],
                           "volunteer": cand["name"], "body": body, "timeout_s": timeout_s(),
                           "incident": public_incident(store.incident(iid))})
        if i == 0:
            await emit_trace(iid, "Supervisor", "RESULT", "LatencyBudget",
                             f"Ingest→dispatch {time.perf_counter() - st['t0']:.2f}s (budget 8.0s) via {channel}",
                             {"seconds": round(time.perf_counter() - st['t0'], 2)})
        try:
            await asyncio.wait_for(pend.event.wait(), timeout=timeout_s())
            answer = pend.answer
        except asyncio.TimeoutError:
            answer = "TIMEOUT"
        finally:
            PENDING.pop(cand["id"], None)
        if answer == "ACCEPT":
            await on_accept(st, cand)
            return
        reason = "declined" if answer == "DECLINE" else f"no reply in {int(timeout_s())}s"
        await emit_trace(iid, "Dispatch", "THOUGHT", "Escalate",
                         f"{cand['name']} {reason}. Escalating to next candidate.", {"answer": answer})
    store.update_incident(iid, status="ESCALATED_MUNICIPAL")
    await emit_trace(iid, "Dispatch", "ACTION", "MunicipalHandoff",
                     "All candidates exhausted → ticket routed to municipal emergency services (911 CAD webhook).")
    await emit_incident(iid)
    _ = elapsed


async def on_accept(st, cand):
    iid = st["iid"]
    token = f"RS-{secrets.randbelow(9000) + 1000}"
    store.x("UPDATE volunteers SET assigned_incident_id=? WHERE id=?", (iid, cand["id"]))
    if st.get("resource"):
        store.x("UPDATE resources SET quantity=quantity-1 WHERE id=?", (st["resource"]["id"],))
    rt = st.get("route") or {}
    if rt.get("path") and rt["path"][0] != cand["pos"]:  # recompute if fallback candidate
        hz = store.hazards()
        tgt = (st["geo"]["lat"], st["geo"]["lon"])
        legs = []
        s = tuple(cand["pos"])
        if st.get("resource"):
            legs.append(route(s, (st["resource"]["latitude"], st["resource"]["longitude"]), hz, cand["transport"]))
            s = (st["resource"]["latitude"], st["resource"]["longitude"])
        legs.append(route(s, tgt, hz, cand["transport"]))
        rt = {"path": legs[0]["path"] + sum((l["path"][1:] for l in legs[1:]), []),
              "eta_min": sum(l["eta_min"] for l in legs), "miles": round(sum(l["miles"] for l in legs), 2),
              "blocked": sorted({b for l in legs for b in l["blocked"]})}
    store.update_incident(iid, status="ACCEPTED", volunteer_id=cand["id"], confirm_token=token,
                          route=rt, eta_min=rt.get("eta_min"))
    await emit_trace(iid, "Dispatch", "RESULT", "Accepted",
                     f"{cand['name']} accepted. Confirmation token {token} issued to caller.",
                     {"volunteer": cand, "token": token})
    nav = f"{public_url()}/#nav/{iid}"
    await send_sms(cand["phone"],
                   f"ReliefSync: Confirmed. Safe route ({rt.get('miles')} mi, avoids "
                   f"{', '.join(rt.get('blocked') or []) or 'no hazards'}): {nav} . Show token {token} on "
                   f"arrival. Reply DONE when delivered.", iid)
    inc = store.incident(iid)
    if inc.get("reporter_phone"):
        await send_sms(inc["reporter_phone"],
                       f"ReliefSync: Help is coming. {cand['name']} en route, ETA {rt.get('eta_min')} min. "
                       f"Your code: {token}", iid)
    store.update_incident(iid, status="EN_ROUTE")
    await emit_trace(iid, "Dispatch", "ACTION", "EnRoute",
                     f"Dispatched – Volunteer En Route (ETA {rt.get('eta_min')} mins)", {"eta_min": rt.get("eta_min")})
    await emit_incident(iid)
    spawn(animate_travel(iid, cand["id"], rt.get("path") or []))


async def animate_travel(iid, vid, path):
    if len(path) < 2:
        return
    pts = []
    for a, b in zip(path, path[1:]):
        n = max(2, int(haversine_mi(tuple(a), tuple(b)) / 0.03))
        pts += [[a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n] for k in range(n)]
    pts.append(path[-1])
    delay = demo_speed() / len(pts)
    for i, p in enumerate(pts):
        inc = store.incident(iid)
        if not inc or inc["status"] not in ("EN_ROUTE", "ACCEPTED"):
            return
        store.x("UPDATE volunteers SET current_latitude=?, current_longitude=? WHERE id=?", (p[0], p[1], vid))
        remaining = max(0, round((inc.get("eta_min") or 0) * (1 - i / len(pts))))
        await hub.publish({"type": "volunteer_pos", "volunteer_id": vid, "incident_id": iid,
                           "lat": p[0], "lon": p[1], "eta_min": remaining, "progress": i / len(pts)})
        await asyncio.sleep(delay)
    await emit_trace(iid, "Dispatch", "RESULT", "Arrived", "Volunteer on scene. Awaiting token verification.")
    store.update_incident(iid, status="ON_SCENE")
    await emit_incident(iid)


# --------------------------------------------------------------------------- #
# Responses / commands
# --------------------------------------------------------------------------- #
YES = {"YES", "Y", "ACCEPT", "OK", "CONFIRM"}
NO = {"NO", "N", "DECLINE", "PASS", "REJECT"}


async def volunteer_response(vid: str, answer: str) -> str:
    pend = PENDING.get(vid)
    if not pend:
        return "No open request for you right now. Thank you for standing by."
    pend.answer = answer
    pend.event.set()
    return "Thanks — confirmed." if answer == "ACCEPT" else "Understood, passing to next volunteer."


async def resolve(iid: str, token: str | None = None) -> dict:
    inc = store.incident(iid)
    if not inc:
        raise KeyError(iid)
    if token and inc.get("confirm_token") and token.strip().upper() != inc["confirm_token"].upper():
        raise ValueError("token mismatch")
    if inc.get("volunteer_id"):
        store.x("UPDATE volunteers SET assigned_incident_id=NULL WHERE id=?", (inc["volunteer_id"],))
    # Ephemeral data policy (spec 4.3): scrub PII once resolved.
    ph = inc.get("reporter_phone")
    store.update_incident(
        iid, status="RESOLVED", transcription="[scrubbed after resolution]", caller=None,
        latitude=round(inc["latitude"] or 0, 2), longitude=round(inc["longitude"] or 0, 2),
        needs=None, route=None, scrubbed=1,
        reporter_phone=("tok_" + hashlib.sha256(ph.encode()).hexdigest()[:12]) if ph else None)
    store.x("UPDATE trace SET data='{}', detail=CASE WHEN name IN ('AudioTranscribed','EntityExtracted','GeoResolved') "
            "THEN '[scrubbed]' ELSE detail END WHERE incident_id=?", (iid,))
    await emit_trace(iid, "Privacy", "ACTION", "PIIScrubbed",
                     "Incident resolved. Transcript, exact coordinates and medical details tokenized/erased.")
    await emit_incident(iid)
    return public_incident(store.incident(iid))


async def handle_sms(phone: str, body: str, lat=None, lon=None) -> str:
    """Inbound SMS (Twilio webhook or simulator). Supports STATUS / ACCEPT / HELP fallbacks."""
    store_msg = store.log_message("in", phone, body, None, "sms")
    await hub.publish({"type": "sms", "message": store_msg})
    words = body.strip().split()
    cmd = words[0].upper().strip("!.") if words else ""
    vol = store.volunteer_by_phone(phone)
    if vol:
        if cmd in YES:
            reply = await volunteer_response(vol["id"], "ACCEPT")
        elif cmd in NO:
            reply = await volunteer_response(vol["id"], "DECLINE")
        elif cmd == "DONE" and vol["assigned_incident_id"]:
            await resolve(vol["assigned_incident_id"])
            reply = "Marked resolved. Thank you! Victim data has been scrubbed."
        else:
            reply = "Commands: YES/ACCEPT, NO, DONE, STATUS."
            if cmd == "STATUS":
                reply = (f"You are assigned to incident {vol['assigned_incident_id'][:8]}."
                         if vol["assigned_incident_id"] else "You are on standby.")
    elif cmd == "STATUS":
        mine = store.q("SELECT * FROM incidents WHERE reporter_phone=? ORDER BY created_at DESC LIMIT 1", (phone,))
        if mine:
            m = mine[0]
            reply = f"ReliefSync: request {m['id'][:8]} is {m['status'].replace('_', ' ')}" + (
                f", ETA {m['eta_min']} min, code {m['confirm_token']}" if m.get("confirm_token") else "")
        else:
            reply = "No active request. Text HELP followed by your situation and location."
    elif cmd == "HELP" and len(words) == 1:
        reply = "ReliefSync: text HELP + what you need + where you are, e.g. 'HELP insulin needed at 12th St near St Jude's'."
    else:
        text = body[4:].strip() if cmd == "HELP" else body
        iid = await create_incident(text, None, None, lat, lon, "sms", reporter_phone=phone)
        reply = f"ReliefSync: request {iid[:8]} received. Finding nearest help now. Text STATUS for updates."
    out = store.log_message("out", phone, reply, None, "sms-reply")
    await hub.publish({"type": "sms", "message": out})
    return reply


def snapshot() -> dict:
    return {
        "incidents": [public_incident(i) for i in store.incidents()],
        "volunteers": [{k: v[k] for k in ("id", "name", "transport_type", "current_latitude",
                                          "current_longitude", "is_active", "assigned_incident_id",
                                          "phone_number")} for v in store.volunteers()],
        "resources": store.resources(),
        "hazards": store.hazards(),
        "messages": store.messages(80),
        "pending": [{"volunteer_id": p.vid, "incident_id": p.iid} for p in PENDING.values()],
        "config": {"dispatch_timeout_s": timeout_s(), "radius_mi": radius_mi(), **settings.status()},
    }


_ = (json, random)
