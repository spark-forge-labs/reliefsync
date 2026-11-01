# ReliefSync

Autonomous, agent-driven disaster dispatch. A voice SOS goes in; a chain of micro-agents transcribes it, triages it, geocodes the landmark, finds matching supplies, ranks nearby volunteers, routes around flood zones, and texts a volunteer — with no human dispatcher in the loop. Every Thought → Tool Call → Action → Result step streams live to the web Ops Room and the Android app.

```
reliefsync/
├── backend/   FastAPI agent orchestration (REST + WebSocket + SSE), SQLite, Twilio, tests
├── web/       Agent Operations Room – live map, trace, SOS recorder, volunteer-phone simulator (PWA)
├── android/   Native Kotlin + Jetpack Compose app (SOS, live agent trace, map, volunteer mode)
└── docs/      Architecture + demo-day script
```

## Quick start (2 minutes)

```bash
./run.sh            # macOS / Linux   (Windows: run.bat)
```
Open **http://localhost:8077** and press **▶ Run demo scenario** — or tap **SOS**, speak the demo line, and watch the agents work. The volunteer phone on the right buzzes; tap **YES** and the incident flips to *Dispatched – Volunteer En Route*.

**Demo & simulate mode needs no API keys.** Open ⚙ Settings in the web app to add any of them (stored in `backend/settings.json`, applied instantly, each with a Test button):

| Key | Turns on | Without it |
|---|---|---|
| Anthropic API key | Claude triage | rule-based triage |
| OpenAI API key | Whisper transcription of voice notes | browser / on-device speech-to-text |
| Twilio SID + token + number | real SMS to volunteers' phones | simulated phone in the web app |

No API keys are needed: the agents run deterministically offline. Add keys in `backend/.env` (copy `.env.example`) to switch on Claude triage, Whisper transcription, real Twilio SMS and Nominatim geocoding.

Run the end-to-end test suite:
```bash
cd backend && python tests/test_e2e.py      # or: pytest -q
```

## Marketplace – agents negotiate and award the job (Uber-style)

After triage and routing-aware ranking, a **dispatcher agent** broadcasts a request-for-quote (RFQ) and a **volunteer agent** for every volunteer in range answers with a bid – or declines with a reason (battery, vehicle can't carry an evacuation, ETA over limit). Each agent carries the volunteer's service record: rating, jobs, on-time / acceptance / cancellation rates, certifications, battery, price floor and negotiating style (`backend/app/market.py`).

| Step | What happens |
|---|---|
| Signal | RFQ → all agents in range (hidden budget band) |
| Bid | price = (base + per-mile + time + pickup fee) × demand/supply surge × hazard risk, opening ask above the agent's walk-away floor; pro-bono volunteers bid $0 |
| Negotiate | urgency policy – **P0** no haggling (clamped to the relief-fund cap), **P1** one counter round, **P2** counter + best-and-final; agents concede by style and may hold firm on a top service record |
| Score | weighted ETA · price · quality · reliability · fit · response speed · safety (weights shift with urgency) |
| Award | first acceptable responder inside a *golden window*, unless a later bid is clearly better; winner gets the SMS with the agreed fee, runners-up are told why they lost and stay on standby as escalation fallbacks |

On the **map** the auction plays out spatially: an RFQ ripple and search-radius ring from the incident, dashed signal lines to each volunteer, bid / offer / counter / deal bubbles pinned to the volunteers, the camera framing the bidders and then the winner, a green award line, plus a HUD (Signal → Bids → Negotiate → Award, bid count, best price, fastest ETA) and a ↻ Replay button.

Watch it live in the web Ops Room → **Negotiation room** tab (bidder cards, price history, score breakdown, message feed). All randomness is seeded per incident so runs replay identically. Works in serverless mode too.

## Android app

Open `android/` in Android Studio (Ladybug or newer), let Gradle sync, and run on a device/emulator (minSdk 26, targets Android 15).

- Emulator: works out of the box against `http://10.0.2.2:8077`.
- Physical phone: ⚙ Settings → set the server to your laptop's LAN address (printed by `run.sh`).

Android capabilities used:

| Feature | How |
|---|---|
| Edge recording engine | `MediaRecorder` AAC 16 kHz/32 kbps, 30 s cap, live amplitude waveform |
| On-device speech-to-text | `SpeechRecognizer.createOnDeviceSpeechRecognizer` (Android 12+) – works with no network |
| Offline-first queue | SQLite queue + WorkManager expedited job with `NetworkType.CONNECTED`; auto-flush on reconnect |
| Live agent trace | OkHttp WebSocket with ping + exponential-backoff reconnect |
| Connectivity awareness | `ConnectivityManager.registerDefaultNetworkCallback` → offline banner, instant resync |
| SMS fallback | Pre-filled `HELP …` SMS to the gateway when there's no data (no SEND_SMS permission needed) |
| Volunteer dispatch | High-priority heads-up notification with countdown chronometer and inline ACCEPT/DECLINE actions |
| Caller status | Ongoing progress notification: triage → dispatching → en route (ETA, code) → resolved |
| One-tap SOS | Quick Settings tile + launcher app shortcuts (long-press icon → Record SOS) |
| Maps | osmdroid OpenStreetMap (no API key), night-inverted tiles, hazard polygons, live route & volunteer tracking, offline tile cache |
| Visuals | Material 3, Material You tonal containers, edge-to-edge, predictive back, haptics, animated pulse/waveform |

## Web app

Served by the backend at `/`. Installable PWA with an offline shell; SOS recorded while offline is kept in IndexedDB and auto-sent on reconnect. Uses `MediaRecorder` + Web Audio waveform + Web Speech live transcript, Leaflet (bundled locally) on live OpenStreetMap tiles (tile.openstreetmap.org). The volunteer navigation link texted on acceptance (`/#nav/<incident>`) opens the safe route.

## How the agents map to the spec

| Spec | Implementation (`backend/app`) |
|---|---|
| 3.1 Ingestion & triage | `engine.node_ingest/node_triage` – Whisper (optional) or on-device transcript; Claude JSON extraction cross-checked by a deterministic safety rule engine |
| 3.2 Geo & resources | `tools.geocode` (gazetteer → Nominatim → GPS), embedding vector search over inventory, A* routing with hazard-polygon exclusion |
| 3.3 Dispatch & negotiation | top-3 volunteers within 2 mi, SMS via Twilio/simulator, timeout/decline escalation, municipal handoff, nav link + confirmation token |
| 3.4 Live visualizer | every node emits THOUGHT/TOOL_CALL/ACTION/RESULT over `/ws` and `/api/events` (SSE) |
| 4.2 Offline & SMS | client queues; `/api/sms/twilio` handles `HELP`, `STATUS`, `YES/ACCEPT`, `NO`, `DONE` |
| 4.3 Privacy & guardrails | PII scrubbed/tokenized on RESOLVED; injection patterns neutralised before triage and flagged in the trace |

See `docs/ARCHITECTURE.md` for the production swap-ins (PostGIS, pgvector, Redis, LangGraph).

## Deploying

**Render (recommended: full real-time):** New → Blueprint → pick this repo. `render.yaml` runs the FastAPI server with WebSockets and the background dispatch/escalation loop.

**Vercel (demo-grade):** import the repo with Root Directory left blank. `vercel.json` defines two FastAPI services: `app` (repo root, `index.py`: web UI + pages) and `reliefsync` (`backend/`: everything under `/api/*`). `index.py` exposes the FastAPI app and the web UI; the client falls back to Server-Sent Events. Serverless limits apply: no WebSockets, state lives in `/tmp` per instance, and long-running dispatch timers may be cut short. Use Render or any VM/container host for production.
