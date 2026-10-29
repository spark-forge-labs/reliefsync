# ReliefSync architecture

```
 Android app / Web Ops Room / SMS (Twilio)
        │  POST /api/sos (AAC + transcript + GPS)      ▲  WebSocket /ws · SSE /api/events
        ▼                                              │  (trace, incident, sms, offer, volunteer_pos)
 ┌──────────────────────── Agent graph (engine.py) ────────────────────────┐
 │ ingest → triage → geo_resolve → supply_match → volunteer_rank → route →  │
 │ dispatch ⟲ (timeout/decline → next candidate) → municipal handoff        │
 │                         ↘ accept → token + nav link → en route → resolve │
 └──────────────────────────────────────────────────────────────────────────┘
        │ tools.py: sanitize · rule/LLM triage · geocode · embed+cosine · A* hazard routing
        ▼
 SQLite (incidents, resources+embeddings, volunteers, hazards, trace, messages)
```

## Prototype → production swap-ins

| Prototype | Production | Notes |
|---|---|---|
| SQLite + haversine | PostgreSQL + PostGIS `ST_DWithin` / `ST_Distance` | `store.py` is the only module touching SQL |
| CRC-hashed bag-of-words vectors | pgvector / ChromaDB with a 1536-d embedding model | `tools.embed` / `cosine` |
| asyncio Events for volunteer timeouts | Redis keys with TTL + pub/sub | `engine.PENDING`, `Hub` |
| Hand-rolled state graph | LangGraph `StateGraph` (one node per function) | node functions already take/return a shared state dict |
| Grid A* around hazard polygons | OSRM with `exclude` areas | `tools.route` |
| Gazetteer | Mapbox / Nominatim | enable with `GEOCODE_ONLINE=1` |

## Latency budget (measured in tests)
Text SOS → first volunteer SMS ≈ 1.8 s locally (budget 8 s). Trace events are paced at 120 ms so judges can follow them; set to 0 for raw speed.

## Demo-day script
1. `./run.sh`, open the web Ops Room on the projector, Android app on the presenter phone (Settings → laptop IP).
2. Optional: set `TWILIO_*` and `DEMO_PHONE_OVERRIDE=<second phone>` so Maya's dispatch SMS lands on a real handset; point the Twilio number's webhook at `<public-url>/api/sms/twilio` (e.g. via ngrok). Set `DISPATCH_TIMEOUT_S=45`.
3. Tap **SOS**, read the panic line. Agents stream: AudioTranscribed → EntityExtracted (P0, POWER) → GeoResolved (37.7749, -122.4194) → SupplyLocated (Portable Power Station 1000Wh) → RouteEvaluated (Main Ave flooded, reroute) → DispatchSent.
4. Second phone buzzes; reply **YES** (or tap YES on the web phone / the Android heads-up notification).
5. Presenter app flips to **Dispatched – Volunteer En Route (ETA N mins)**; volunteer marker animates along the safe route.
6. Tap **Mark delivered** → PII scrubbed event closes the loop.
