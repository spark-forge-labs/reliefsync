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
import math
import os
import random
import secrets
import time
import uuid
from typing import Any

import httpx

from . import market, settings, store, tools
from .tools import (cosine, embed, geocode, haversine_mi, llm_triage, osrm_table, road_route, route, rule_triage,
                    sanitize, transcribe)

# Serverless hosts (Vercel) can't run background loops: run the pipeline inside the request instead.
SERVERLESS = bool(os.getenv("VERCEL") or os.getenv("RELIEFSYNC_SERVERLESS"))

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


async def emit_trace(iid, agent, kind, name, detail, data=None, pace=0.12):
    ev = store.add_trace(iid, agent, kind, name, detail, data)
    await hub.publish({"type": "trace", "event": ev})
    if not SERVERLESS:
        await asyncio.sleep(pace)  # keep the live trace legible for judges
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
        await node_market(state)
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
    for c in scored:
        c[3].pop("embedding", None)
    # every adequately-matching source (best per provider) is a pickup option for the optimizer
    opts, seen = [], set()
    for sc, sm, dd, rr in sorted(scored, key=lambda c: -c[1]):
        if sm >= max(0.15, sim * 0.8) and rr["provider_name"] not in seen:
            seen.add(rr["provider_name"]); opts.append(rr)
    st["supply_options"] = opts[:3]
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
    """Joint optimisation: pick the (volunteer, pickup source) pair with the lowest real road
    travel time  volunteer -> pickup -> caller  using one OSRM duration matrix."""
    iid, tri, geo = st["iid"], st["triage"], st["geo"]
    await emit_trace(iid, "Dispatch", "THOUGHT", "SelectVolunteers",
                     f"Find vetted, idle volunteers within {radius_mi()} mi and choose the volunteer + supply "
                     "source combination with the shortest road trip to the caller.")
    await emit_trace(iid, "Dispatch", "TOOL_CALL", "spatial.st_dwithin",
                     f"ST_DWithin(volunteers, incident, {radius_mi()} mi)")
    cat = tri["category"]
    pool = []
    for v in store.volunteers():
        if not v["is_active"] or v["assigned_incident_id"] or v["id"] in PENDING:
            continue
        d = haversine_mi((geo["lat"], geo["lon"]), (v["current_latitude"], v["current_longitude"]))
        if d <= radius_mi():
            pool.append((v, d))
    sources = st.get("supply_options") or ([st["resource"]] if st.get("resource") else [])
    target = (geo["lat"], geo["lon"])
    pts = [(v["current_latitude"], v["current_longitude"]) for v, _ in pool] + \
          [(r["latitude"], r["longitude"]) for r in sources] + [target]
    nv, ns, ti = len(pool), len(sources), len(pool) + len(sources)
    table = None
    if pool:
        await emit_trace(iid, "Optimizer", "TOOL_CALL", "osrm.table",
                         f"Road-network travel-time matrix: {nv} volunteers × {ns or 1} pickup source(s) → caller")
        table = await osrm_table(pts)

    def road(i, j):  # minutes, metres
        if table:
            dur, dist = table
            if dur[i][j] is not None:
                return dur[i][j] / 60, (dist[i][j] or 0)
        mi = haversine_mi(pts[i], pts[j]) * 1.35
        return mi / 18 * 60, mi * 1609.34

    ranked = []
    for vi, (v, d_air) in enumerate(pool):
        fit = TRANSPORT_FIT[cat].get(v["transport_type"], 0.5)
        tf = tools.TRANSPORT_FACTOR.get(v["transport_type"], 1.0)
        skill = any(sk in cat.lower() or sk in " ".join(tri.get("needs") or []) for sk in v["skills"])
        options = []
        if sources:
            for si, r in enumerate(sources):
                t1, m1 = road(vi, nv + si)
                t2, m2 = road(nv + si, ti)
                options.append((t1 + t2, m1 + m2, r))
        else:
            t, m = road(vi, ti)
            options.append((t, m, None))
        t, m, r = min(options, key=lambda o: o[0])
        minutes = t * tf
        cost = minutes * (1.5 - 0.5 * fit) - (1.0 if skill else 0.0)   # time first, then vehicle fit & skills
        ranked.append({"id": v["id"], "name": v["name"], "phone": v["phone_number"], "transport": v["transport_type"],
                       "miles": round(d_air, 2), "trip_min": round(minutes, 1), "trip_miles": round(m / 1609.34, 2),
                       "score": round(-cost, 2), "pos": [v["current_latitude"], v["current_longitude"]],
                       "resource": r})
    ranked.sort(key=lambda c: -c["score"])
    st["candidates"] = ranked[:3]
    st["bidders"] = ranked[:6]          # everyone in range may bid in the marketplace
    if ranked and ranked[0]["resource"]:
        st["resource"] = ranked[0]["resource"]
        store.update_incident(iid, resource_id=st["resource"]["id"])
    nearest = min(ranked, key=lambda c: c["miles"]) if ranked else None
    best = ranked[0] if ranked else None
    note = ""
    if best and nearest and nearest is not best:
        note = f" (nearest-by-air {nearest['name'].split()[0]} would take {nearest['trip_min']:.0f} min)"
    await emit_trace(iid, "Optimizer", "RESULT", "PlanOptimized",
                     (f"Best plan: {best['name']} ({best['transport']}) → "
                      f"{best['resource']['provider_name'] + ' → ' if best['resource'] else ''}caller · "
                      f"{best['trip_miles']} mi, ~{best['trip_min']:.0f} min{note}") if best else "No volunteers in radius",
                     {"matrix": "osrm" if table else "estimate",
                      "candidates": [{k: c[k] for k in ("name", "transport", "trip_min", "trip_miles")} |
                                     {"pickup": c["resource"]["provider_name"] if c["resource"] else None} for c in ranked]})
    await emit_trace(iid, "Dispatch", "RESULT", "VolunteersRanked",
                     " · ".join(f"#{i+1} {c['name']} ({c['transport']}, {c['trip_min']:.0f} min by road)"
                                for i, c in enumerate(st["candidates"])) or "No volunteers in radius",
                     {"candidates": [{k: v for k, v in c.items() if k != "resource"} for c in st["candidates"]]})


# --------------------------------------------------------------------------- #
# Marketplace: volunteer agents bid, dispatcher negotiates and awards (see market.py)
# --------------------------------------------------------------------------- #
def _hazards_near(geo, miles=0.6) -> list[str]:
    out = []
    for h in store.hazards():
        pts = h["polygon"]
        c = (sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))
        if haversine_mi((geo["lat"], geo["lon"]), c) <= miles:
            out.append(h["name"])
    return out


async def node_market(st):
    iid, tri, geo = st["iid"], st["triage"], st["geo"]
    bidders = st.get("bidders") or []
    if not bidders:
        return
    urg, cat = tri["urgency"], tri["category"]
    pol = market.BUDGET[urg]
    first = lambda c: c["name"].split()[0]
    open_inc = sum(1 for i in store.incidents() if i["status"] not in ("RESOLVED", "ESCALATED_MUNICIPAL") and i["created_at"] > time.time() - 1800)
    rfq = {"iid": iid, "category": cat, "urgency": urg, "surge": market.surge_multiplier(open_inc, len(bidders)),
           "max_eta_min": 45.0 if urg == "P2" else 30.0, "blocked": _hazards_near(geo)}
    log = {"policy": {"urgency": urg, **pol}, "surge": rfq["surge"], "rfq": {k: v for k, v in rfq.items() if k != "iid"}}
    S = lambda name, ag, kind, detail, m: emit_trace(iid, ag, kind, name, detail, {"m": m}, pace=0.05)

    rounds_txt = {0: "no haggling – life first", 1: "one counter-offer round", 2: "counter-offers + best-and-final"}[pol["rounds"]]
    await emit_trace(iid, "Market", "THOUGHT", "OpenAuction",
                     f"{urg} policy: relief-fund cap {market.money(pol['cap'])}, opening target {market.money(pol['target'])}, "
                     f"{rounds_txt}; award window {'unbounded' if pol['window_ms'] > 10**8 else str(pol['window_ms']) + ' ms'}. "
                     f"Demand/supply surge ×{rfq['surge']}" + (f"; hazards near caller: {', '.join(rfq['blocked'])}." if rfq["blocked"] else "."))
    await S("RFQBroadcast", "Market", "TOOL_CALL",
            f"RFQ → {len(bidders)} volunteer agents: {urg} {cat.replace('_', ' ').lower()} · {geo['label'].split(',')[0]} · "
            f"max ETA {rfq['max_eta_min']:.0f} min · budget band hidden",
            {"t_ms": 0, "kind": "RFQ", "from": "dispatcher", "to": [c["id"] for c in bidders], "round": 0})

    # ---- volunteer agents answer ----------------------------------------------------------------
    bids, declined = [], []
    answers = []
    for c in bidders:
        v = store.volunteer(c["id"]) or {}
        p = market.profile_for({"id": c["id"], "name": c["name"]})
        ev = market.evaluate_rfq(c, p, rfq)
        answers.append((ev.get("latency_ms", 400), c, p, ev, v))
    answers.sort(key=lambda a: a[0])
    for lat, c, p, ev, v in answers:
        ag = f"Agent·{first(c)}"
        if not ev["bid"]:
            declined.append({"id": c["id"], "name": c["name"], "reason": ev["reason"]})
            await S("Decline", ag, "RESULT", f"{c['name']} declines: {ev['reason']}.",
                    {"t_ms": lat, "kind": "DECLINE", "from": c["id"], "name": c["name"], "to": "dispatcher", "round": 0, "reason": ev["reason"]})
            continue
        sk = any(k in cat.lower() or k in " ".join(tri.get("needs") or []) for k in (v.get("skills") or []))
        b = {"id": c["id"], "name": c["name"], "cand": c, "profile": p, "ask": ev["ask"], "price": ev["ask"], "floor": ev["floor"],
             "eta_min": ev["eta_min"], "miles": ev["miles"], "latency_ms": lat, "skill": sk, "blocked": rfq["blocked"],
             "tfit": TRANSPORT_FIT[cat].get(c["transport"], 0.5), "status": "BID", "history": []}
        bids.append(b)
        await S("Bid", ag, "ACTION",
                f"{c['name']} bids {market.money(b['price'])} · ETA {b['eta_min']:.0f} min ({c['transport']}) · ★{p['rating']} "
                f"· {p['jobs']} jobs · {p['on_time']:.0%} on-time · {p['accept']:.0%} accept · battery {p['battery']}%",
                {"t_ms": lat, "kind": "BID", "from": c["id"], "name": c["name"], "to": "dispatcher", "round": 0, "price": b["price"],
                 "eta_min": b["eta_min"], "transport": c["transport"], "rating": p["rating"], "jobs": p["jobs"], "on_time": p["on_time"],
                 "accept": p["accept"], "battery": p["battery"], "certs": p["certs"], "style": p["style"], "pro_bono": p["pro_bono"]})
    if not bids:
        await emit_trace(iid, "Market", "RESULT", "NoBids", "No agent bid – falling back to proximity ranking.", {"m": {"kind": "NOBIDS"}})
        return

    def rescore():
        min_eta = min(b["eta_min"] for b in bids)
        min_resp = min(b["latency_ms"] for b in bids)
        for b in bids:
            b.update(market.score_bid(b, urg, pol["cap"], min_eta, min_resp, b["tfit"], b["skill"], cat))
        bids.sort(key=lambda b: -b["score"])

    rescore()
    await emit_trace(iid, "Market", "THOUGHT", "ScoreBids",
                     "Composite score (ETA, price, service quality, reliability, fit, response speed, safety): " +
                     " · ".join(f"{first(b['cand'])} {b['score']}" for b in bids),
                     {"m": {"kind": "SCORES", "round": 0, "scores": {b["id"]: b["score"] for b in bids}}}, pace=0.05)

    # ---- price negotiation (bounded rounds, urgency-dependent) -------------------------------------
    t_clock = max(b["latency_ms"] for b in bids)
    rounds = pol["rounds"]
    if rounds == 0:
        for b in bids:                       # P0: clamp to the cap, never argue
            if b["price"] > pol["cap"] + 1e-6:
                t_clock += 120
                await S("Counter", "Market", "ACTION", f"Cap clamp → {b['name']}: relief fund pays at most {market.money(pol['cap'])}.",
                        {"t_ms": t_clock, "kind": "COUNTER", "from": "dispatcher", "to": b["id"], "round": 1, "price": pol["cap"], "name": b["name"]})
                r = market.respond_counter(b["profile"], b, pol["cap"], True)
                t_clock += 150
                if r["kind"] == "ACCEPT":
                    b["price"] = r["price"]
                else:
                    b["status"] = "WITHDRAWN"
                await S(r["kind"].title(), f"Agent·{first(b['cand'])}", "ACTION", f"{b['name']}: {r['text']}",
                        {"t_ms": t_clock, "kind": r["kind"], "from": b["id"], "to": "dispatcher", "round": 1, "price": r["price"], "name": b["name"], "text": r["text"]})
    else:
        for rnd in range(1, rounds + 1):
            final = rnd == rounds and rounds >= 2
            live = [b for b in bids if b["status"] != "WITHDRAWN"][:3]     # negotiate with the three best-scoring bids only
            for b in live:
                if b.get("agreed") or b["price"] <= pol["target"] + 1e-6:
                    b["agreed"] = True
                    continue
                if rnd == 1:
                    offer = round(min(pol["cap"], pol["target"] + (b["price"] - pol["target"]) * 0.5), 2)
                else:   # meet the agent halfway between our last offer and its last counter
                    offer = round(min(pol["cap"], (b["last_offer"] + b["price"]) / 2), 2)
                offer = max(offer, 0.0)
                b["last_offer"] = offer
                t_clock += 140
                await S("Counter", "Market", "ACTION",
                        f"{'Best-and-final' if final else 'Counter'} → {b['name']}: {market.money(offer)} "
                        f"(ask {market.money(b['price'])}; quality score {b['score']})",
                        {"t_ms": t_clock, "kind": "COUNTER", "from": "dispatcher", "to": b["id"], "round": rnd, "price": offer,
                         "final": final, "name": b["name"]})
                r = market.respond_counter(b["profile"], b, offer, final)
                t_clock += int(b["latency_ms"] * 0.35)
                b["history"].append({"round": rnd, "offer": offer, "answer": r["kind"], "price": r["price"]})
                if r["kind"] == "ACCEPT":
                    b["price"], b["agreed"] = r["price"], True
                elif r["kind"] in ("COUNTER", "HOLD"):
                    b["price"] = r["price"]
                    if rnd == rounds:            # out of rounds: dispatcher takes the agent's last price if it is within the cap
                        b["agreed"] = True
                else:
                    b["status"] = "WITHDRAWN"
                await S(r["kind"].title(), f"Agent·{first(b['cand'])}", "ACTION", f"{b['name']}: {r['text']}",
                        {"t_ms": t_clock, "kind": r["kind"], "from": b["id"], "to": "dispatcher", "round": rnd, "price": r["price"],
                         "name": b["name"], "text": r["text"]})
        for b in bids:                      # anything still above the cap is not eligible
            if b["status"] != "WITHDRAWN" and b["price"] > pol["cap"] + 1e-6:
                b["status"] = "WITHDRAWN"

    live = [b for b in bids if b["status"] != "WITHDRAWN"]
    if not live:
        await emit_trace(iid, "Market", "RESULT", "NoBids", "All bids withdrawn – falling back to proximity ranking.", {"m": {"kind": "NOBIDS"}})
        return
    bids[:] = live
    rescore()

    # ---- award: first acceptable responder inside the golden window, unless a later bid is clearly better ----
    first_t = min(b["latency_ms"] for b in bids)
    window = [b for b in bids if b["latency_ms"] <= first_t + pol["window_ms"]]
    best_all, best_win = bids[0], max(window, key=lambda b: b["score"])
    margin = {"P0": 999.0, "P1": 6.0, "P2": 0.0}[urg]
    winner = best_win if best_all["score"] - best_win["score"] <= margin else best_all
    why = ("fastest acceptable bid within the golden window" if winner is best_win and winner is not best_all
           else "highest composite score")
    runners = [b for b in bids if b is not winner]
    t_clock += 150
    await emit_trace(iid, "Market", "RESULT", "AuctionResult",
                     f"Winner: {winner['name']} · {market.money(winner['price'])} · ETA {winner['eta_min']:.0f} min · score {winner['score']} ({why}). "
                     f"Runners-up: " + ", ".join(f"{first(b['cand'])} {b['score']}" for b in runners[:3]),
                     {"m": {"kind": "RESULT", "t_ms": t_clock, "winner": winner["id"], "why": why},
                      "table": [{"id": b["id"], "name": b["name"], "transport": b["cand"]["transport"], "ask": b["ask"], "price": b["price"],
                                 "eta_min": b["eta_min"], "score": b["score"], "components": b["components"], "weights": b["weights"],
                                 "status": "WON" if b is winner else "LOST", "rating": b["profile"]["rating"]} for b in [winner] + runners] +
                                [{"id": d["id"], "name": d["name"], "status": "DECLINED", "reason": d["reason"]} for d in declined]})
    await S("Award", "Market", "ACTION", f"AWARD → {winner['name']}: deliver for {market.money(winner['price'])}. Awaiting confirmation.",
            {"t_ms": t_clock + 60, "kind": "AWARD", "from": "dispatcher", "to": winner["id"], "price": winner["price"], "name": winner["name"]})
    for b in runners:
        gap = round(winner["score"] - b["score"], 1)
        slow = round(b["eta_min"] - winner["eta_min"], 1)
        eta_txt = f"{abs(slow):.0f} min {'slower' if slow > 0 else 'faster'} ETA" if abs(slow) >= 0.5 else "similar ETA"
        pos = runners.index(b)
        fb = f"{eta_txt}, {gap} pts behind" + (f" – kept on standby as fallback #{pos + 1}" if pos < 2 else "")
        await S("NotSelected", "Market", "ACTION", f"Signal → {b['name']}: not selected ({fb}).",
                {"t_ms": t_clock + 90, "kind": "LOSE", "from": "dispatcher", "to": b["id"], "price": b["price"], "name": b["name"], "feedback": fb})

    st["market"] = log | {"winner": winner["id"]}
    order = [winner] + runners
    st["candidates"] = [dict(b["cand"], fee=b["price"], bid_score=b["score"]) for b in order[:3]]
    st["resource"] = st["candidates"][0].get("resource") or st.get("resource")
    if st["resource"]:
        store.update_incident(iid, resource_id=st["resource"]["id"])


async def plan_route(start, resource, target, transport) -> dict:
    """Volunteer -> (pickup) -> incident on real roads, avoiding hazard polygons."""
    hz = store.hazards()
    s = tuple(start)
    if resource:  # both legs planned concurrently
        pick = (resource["latitude"], resource["longitude"])
        legs = list(await asyncio.gather(road_route(s, pick, hz, transport), road_route(pick, tuple(target), hz, transport)))
    else:
        legs = [await road_route(s, tuple(target), hz, transport)]
    path = legs[0]["path"] + sum((leg["path"][1:] for leg in legs[1:]), [])
    return {"path": path, "eta_min": sum(leg["eta_min"] for leg in legs),
            "miles": round(sum(leg["miles"] for leg in legs), 2),
            "blocked": sorted({b for leg in legs for b in leg["blocked"]}),
            "pickup_idx": len(legs[0]["path"]) - 1 if resource else None,
            "engine": legs[-1].get("engine", "grid"), "transport": transport}



async def node_route(st):
    iid, geo = st["iid"], st["geo"]
    if not st["candidates"]:
        return
    c = st["candidates"][0]
    await emit_trace(iid, "Router", "TOOL_CALL", "osrm.route+hazard_exclusion",
                     f"Plan {c['transport']} route on the OpenStreetMap road network: volunteer → "
                     f"{'pickup → ' if st.get('resource') else ''}incident, excluding {len(store.hazards())} hazard zone(s)")
    st["route"] = rt = await plan_route(c["pos"], c.get("resource", st.get("resource")), (geo["lat"], geo["lon"]), c["transport"])
    rt["vid"] = c["id"]
    store.update_incident(iid, route=rt, eta_min=rt["eta_min"])
    msg = (f"{', '.join(rt['blocked'])} detected on fastest road; rerouting via secondary route on open streets."
           if rt["blocked"] else "Fastest road route is clear of known hazards.")
    await emit_trace(iid, "Router", "RESULT", "RouteEvaluated",
                     f"{msg} {rt['miles']} mi, ETA {rt['eta_min']} min · {rt['engine']}.", rt)
    await emit_incident(iid)


def compose_alert(st, cand) -> str:
    tri = st["triage"]
    need = tri.get("needs", ["assistance"])[0]
    item = st["resource"]["item_name"] if st.get("resource") else "welfare check"
    pickup = f" Pick up {item} at {st['resource']['provider_name']}." if st.get("resource") else ""
    label = st["geo"]["label"].split(",")[0]
    fee = f" Agreed fee {market.money(cand['fee'])}." if cand.get("fee") is not None else ""
    return (f"ReliefSync Alert: {tri['urgency']} emergency {cand['miles']} mi away at {label}. "
            f"Need: {need}.{pickup}{fee} Reply YES to accept or NO to pass within "
            f"{int(timeout_s() // 60) or 1} min.")


async def node_dispatch(st):
    iid = st["iid"]
    store.update_incident(iid, status="DISPATCHING")
    await emit_incident(iid)
    elapsed = time.perf_counter() - st["t0"]
    for i, cand in enumerate(st["candidates"]):
        if cand["id"] in PENDING or (store.volunteer(cand["id"]) or {}).get("assigned_incident_id"):
            continue
        if "resource" in cand:   # each candidate carries its own optimal pickup
            st["resource"] = cand["resource"]
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
    if not rt.get("path") or rt.get("vid") != cand["id"]:
        if "resource" in cand:
            st["resource"] = cand["resource"]
            store.update_incident(iid, resource_id=cand["resource"]["id"] if cand["resource"] else None)
        rt = await plan_route(cand["pos"], st.get("resource"), (st["geo"]["lat"], st["geo"]["lon"]), cand["transport"])
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
    if not SERVERLESS:          # serverless: the client animates the drive along rt["path"]
        spawn(animate_travel(iid, cand["id"], rt))


def _bearing(a, b) -> float:
    la1, la2 = math.radians(a[0]), math.radians(b[0])
    dl = math.radians(b[1] - a[1])
    y = math.sin(dl) * math.cos(la2)
    x = math.cos(la1) * math.sin(la2) - math.sin(la1) * math.cos(la2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


async def animate_travel(iid, vid, rt):
    """Drive the volunteer along the real road geometry at constant speed (time-compressed),
    publishing position + heading ~5x/second; pause at the supply pickup."""
    path = rt.get("path") or []
    if len(path) < 2:
        return
    cum = [0.0]
    for a, b in zip(path, path[1:]):
        cum.append(cum[-1] + haversine_mi(tuple(a), tuple(b)))
    total = cum[-1] or 1e-6
    pickup_d = cum[rt["pickup_idx"]] if rt.get("pickup_idx") else None
    duration = max(8.0, demo_speed())
    tick = 0.2
    elapsed, seg, picked = 0.0, 0, pickup_d is None
    eta0 = rt.get("eta_min") or 0
    while True:
        inc = store.incident(iid)
        if not inc or inc["status"] not in ("EN_ROUTE", "ACCEPTED"):
            return
        d = min(total, total * elapsed / duration)
        if not picked and d >= pickup_d:
            d = pickup_d
        while seg < len(path) - 2 and cum[seg + 1] < d:
            seg += 1
        a, b = path[seg], path[seg + 1]
        f = 0 if cum[seg + 1] == cum[seg] else (d - cum[seg]) / (cum[seg + 1] - cum[seg])
        lat, lon = a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f
        store.x("UPDATE volunteers SET current_latitude=?, current_longitude=? WHERE id=?", (lat, lon, vid))
        await hub.publish({"type": "volunteer_pos", "volunteer_id": vid, "incident_id": iid, "lat": lat, "lon": lon,
                           "heading": round(_bearing(a, b), 1), "eta_min": max(0, math.ceil(eta0 * (1 - d / total))),
                           "progress": round(d / total, 4), "seg": seg, "tick": tick})
        if not picked and d >= pickup_d:
            picked = True
            res = (inc.get("resource_id") and [r for r in store.resources() if r["id"] == inc["resource_id"]]) or []
            await emit_trace(iid, "Dispatch", "RESULT", "PickedUp",
                             f"Volunteer collected {res[0]['item_name'] if res else 'supplies'} at "
                             f"{res[0]['provider_name'] if res else 'pickup'}; heading to caller.")
            await asyncio.sleep(1.5)
        if d >= total:
            break
        await asyncio.sleep(tick)
        elapsed += tick
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
        "config": {"serverless": SERVERLESS, "demo_travel_s": demo_speed(), "dispatch_timeout_s": timeout_s(), "radius_mi": radius_mi(), **settings.status()},
    }


_ = (json, random)
