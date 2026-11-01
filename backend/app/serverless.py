"""Serverless mode (Vercel and similar).

Serverless functions are frozen as soon as they answer, and consecutive requests can land on
different instances with different /tmp databases. So instead of a background agent graph pushing
events over a socket, each request runs its part of the pipeline to completion and returns every
event it produced; the browser replays them live and carries the dispatch context (`ctx`) between
requests:

  POST /api/sl/run      SOS text -> ingest, triage, geo, supply, optimise, route, first dispatch offer
  POST /api/sl/respond  {ctx, index, answer: ACCEPT|DECLINE|TIMEOUT} -> accept or escalate
  POST /api/sl/resolve  {ctx} -> delivered + PII scrub
"""
from __future__ import annotations

import asyncio
import time
import uuid

from . import engine, store


def _drain(q: asyncio.Queue) -> list[dict]:
    out = []
    while not q.empty():
        out.append(q.get_nowait())
    return out


def _ctx(st: dict) -> dict:
    keys = ("iid", "text", "triage", "geo", "resource", "supply_options", "candidates", "route", "flags")
    ctx = {k: st.get(k) for k in keys}
    ctx["incident_row"] = store.q("SELECT * FROM incidents WHERE id=?", (st["iid"],))[0]
    return ctx


def _restore(ctx: dict) -> dict:
    """Rehydrate the incident on whichever instance this request landed on."""
    store.ensure_seeded()
    row = ctx["incident_row"]
    if not store.incident(row["id"]):
        cols = ",".join(row.keys())
        store.x(f"INSERT INTO incidents ({cols}) VALUES ({','.join('?' * len(row))})", tuple(row.values()))
    st = {k: ctx.get(k) for k in ("iid", "text", "triage", "geo", "resource", "supply_options", "candidates", "route", "flags")}
    st["t0"] = time.perf_counter()
    return st


async def _offer(st: dict, i: int) -> int | None:
    """Send the dispatch offer to candidate i. Returns i, or None after municipal handoff."""
    iid = st["iid"]
    cands = st.get("candidates") or []
    if i >= len(cands):
        store.update_incident(iid, status="ESCALATED_MUNICIPAL")
        await engine.emit_trace(iid, "Dispatch", "ACTION", "MunicipalHandoff",
                                "All candidates exhausted → ticket routed to municipal emergency services (911 CAD webhook).")
        await engine.emit_incident(iid)
        return None
    cand = cands[i]
    if "resource" in cand:
        st["resource"] = cand["resource"]
    body = engine.compose_alert(st, cand)
    await engine.emit_trace(iid, "Dispatch", "ACTION", "DispatchSent",
                            f"SMS → {cand['name']} ({cand['phone'][-4:].rjust(len(cand['phone']), '•')}): {body}",
                            {"volunteer": {k: v for k, v in cand.items() if k != "resource"}, "attempt": i + 1})
    channel = await engine.send_sms(cand["phone"], body, iid)
    await engine.hub.publish({"type": "offer", "incident_id": iid, "volunteer_id": cand["id"], "volunteer": cand["name"],
                              "body": body, "timeout_s": engine.timeout_s(),
                              "incident": engine.public_incident(store.incident(iid))})
    return i if channel else i


async def run(text: str, lat=None, lon=None) -> dict:
    q = engine.hub.subscribe()
    try:
        store.ensure_seeded()
        iid = str(uuid.uuid4())
        store.x("INSERT INTO incidents (id,transcription,status,created_at,updated_at,source,latitude,longitude) "
                "VALUES (?,?,?,?,?,?,?,?)", (iid, text, "RECEIVED", time.time(), time.time(), "app-text", lat, lon))
        await engine.emit_incident(iid)
        st = {"iid": iid, "gps": (lat, lon) if lat is not None and lon is not None else None, "t0": time.perf_counter()}
        await engine.node_ingest(st, text, None, None)
        await engine.node_triage(st)
        await engine.node_geo(st)
        index = None
        if st.get("geo"):
            await engine.node_supply(st)
            await engine.node_rank(st)
            await engine.node_route(st)
            store.update_incident(iid, status="DISPATCHING")
            await engine.emit_incident(iid)
            index = await _offer(st, 0)
            secs = time.perf_counter() - st["t0"]
            await engine.emit_trace(iid, "Supervisor", "RESULT", "LatencyBudget",
                                    f"Ingest→dispatch {secs:.2f}s (budget 8.0s)", {"seconds": round(secs, 2)})
        return {"events": _drain(q), "ctx": _ctx(st), "index": index}
    finally:
        engine.hub.unsubscribe(q)


async def respond(ctx: dict, index: int, answer: str) -> dict:
    q = engine.hub.subscribe()
    try:
        st = _restore(ctx)
        cand = (st.get("candidates") or [])[index]
        out = {"route": None, "volunteer_id": cand["id"], "index": index}
        if answer == "ACCEPT":
            await engine.on_accept(st, cand)
            out["route"] = store.incident(st["iid"]).get("route")
        else:
            reason = "declined" if answer == "DECLINE" else f"no reply in {int(engine.timeout_s())}s"
            await engine.emit_trace(st["iid"], "Dispatch", "THOUGHT", "Escalate",
                                    f"{cand['name']} {reason}. Escalating to next candidate.", {"answer": answer})
            out["index"] = await _offer(st, index + 1)
        out["events"] = _drain(q)
        out["ctx"] = _ctx(st)
        return out
    finally:
        engine.hub.unsubscribe(q)


async def resolve(ctx: dict) -> dict:
    q = engine.hub.subscribe()
    try:
        st = _restore(ctx)
        inc = await engine.resolve(st["iid"])
        return {"events": _drain(q), "incident": inc}
    finally:
        engine.hub.unsubscribe(q)
